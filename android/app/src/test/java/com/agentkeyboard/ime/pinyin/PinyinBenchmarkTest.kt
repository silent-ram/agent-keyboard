package com.agentkeyboard.ime.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil

class PinyinBenchmarkTest {
    private companion object {
        const val WARMUP_RUNS = 30
        const val MEASURED_RUNS = 101
        const val PINNED_AB_BASELINE_SHA256 =
            "0D3BBA7A5C9229B47531C4FD38DB04B33588997F1B23C67144EEC785F680F5F3"
    }

    @Test
    fun `fixed corpus reports candidate quality and decode latency`() {
        val corpus = javaClass.getResourceAsStream("/pinyin-benchmark.tsv")
            ?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line ->
                        val fields = line.split('\t')
                        require(fields.size == 6) { "Invalid benchmark row: $line" }
                        BenchmarkCase(
                            id = fields[0],
                            category = fields[1],
                            input = fields[2],
                            expected = fields[3],
                            baselineTop1 = fields[4].toBooleanStrict(),
                            baselineTop5 = fields[5].toBooleanStrict(),
                        )
                    }
                    .toList()
            }
            ?: error("Missing pinyin-benchmark.tsv")
        val decoder = loadRealDecoder()
        val isProductionDictionary = System.getenv("PINYIN_BENCH_DICTIONARY").isNullOrBlank()
        val comparisonDictionaryOverride = System.getenv("PINYIN_BENCH_COMPARE_DICTIONARY")
            ?.takeIf { it.isNotBlank() }
        val usesPinnedComparison = comparisonDictionaryOverride == null
        val comparisonDecoder = comparisonDictionaryOverride?.let { loadDictionary(File(it)) }
            ?: run {
                val baselineBytes = javaClass
                    .getResourceAsStream("/pinyin-dictionary-ab-baseline.tsv")
                    ?.use { it.readBytes() }
                    ?: error("Missing test-only pinyin A/B baseline")
                assertEquals(
                    "A/B baseline SHA-256 changed",
                    PINNED_AB_BASELINE_SHA256,
                    sha256(baselineBytes),
                )
                loadDictionary(StringReader(String(baselineBytes, Charsets.UTF_8)))
            }

        if (isProductionDictionary) {
            assertEquals("nihao smoke check", "你好", decoder.candidates("nihao").first())
        }
        assertTrue("benchmark corpus should cover at least 10 cases", corpus.size >= 10)

        var top1Hits = 0
        var top5Hits = 0
        var comparisonTop1Hits = 0
        var comparisonTop5Hits = 0
        corpus.forEach { sample ->
            repeat(WARMUP_RUNS) { decoder.candidates(sample.input) }
            val timingsNs = LongArray(MEASURED_RUNS) {
                val started = System.nanoTime()
                decoder.candidates(sample.input)
                System.nanoTime() - started
            }.sortedArray()
            val candidates = decoder.candidates(sample.input)
            val top1 = candidates.firstOrNull() == sample.expected
            val top5 = sample.expected in candidates.take(5)
            if (top1) top1Hits++
            if (top5) top5Hits++
            val comparisonCandidates = comparisonDecoder.candidates(sample.input)
            val comparisonTop1 = comparisonCandidates.firstOrNull() == sample.expected
            val comparisonTop5 = sample.expected in comparisonCandidates.take(5)
            if (comparisonTop1) comparisonTop1Hits++
            if (comparisonTop5) comparisonTop5Hits++
            if (isProductionDictionary) {
                assertTrue("${sample.id} lost a baseline Top-1 hit", !sample.baselineTop1 || top1)
                assertTrue("${sample.id} lost a baseline Top-5 hit", !sample.baselineTop5 || top5)
                if (usesPinnedComparison) {
                    assertEquals(
                        "${sample.id} A/B baseline Top-1 changed",
                        sample.baselineTop1,
                        comparisonTop1,
                    )
                    assertEquals(
                        "${sample.id} A/B baseline Top-5 changed",
                        sample.baselineTop5,
                        comparisonTop5,
                    )
                }
            }
            val p50 = percentile(timingsNs, 0.50) / 1_000.0
            val p95 = percentile(timingsNs, 0.95) / 1_000.0
            println(
                String.format(
                    Locale.US,
                    "PINYIN_BENCH,%s,%s,top1=%s,top5=%s,p50_us=%.3f,p95_us=%.3f",
                    sample.id,
                    sample.category,
                    top1,
                    top5,
                    p50,
                    p95,
                ),
            )
            println("PINYIN_TOP_CANDIDATES,${sample.id},${candidates.take(5).joinToString("|")}")
            println(
                "PINYIN_COMPARE,${sample.id},top1=$comparisonTop1,top5=$comparisonTop5," +
                    "top=${comparisonCandidates.take(5).joinToString("|")}",
            )
        }
        println(
            "PINYIN_BENCH_SUMMARY,top1=$top1Hits/${corpus.size}," +
                "top5=$top5Hits/${corpus.size}",
        )
        println(
            "PINYIN_COMPARE_SUMMARY,top1=$comparisonTop1Hits/${corpus.size}," +
                "top5=$comparisonTop5Hits/${corpus.size}",
        )
        if (isProductionDictionary && usesPinnedComparison) {
            assertEquals("Pinned A/B baseline Top-1 score", 4, comparisonTop1Hits)
            assertEquals("Pinned A/B baseline Top-5 score", 4, comparisonTop5Hits)
        }
    }

    private fun loadRealDecoder(): PinyinDecoder {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
        val dictionary = System.getenv("PINYIN_BENCH_DICTIONARY")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(assets, "pinyin_dict.txt")
        return loadDictionary(dictionary)
    }

    private fun loadDictionary(dictionary: File): PinyinDecoder {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
        return loadDictionary(dictionary.reader(Charsets.UTF_8), File(assets, "pinyin_chars.txt"))
    }

    private fun loadDictionary(dictionary: Reader): PinyinDecoder {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
        return loadDictionary(dictionary, File(assets, "pinyin_chars.txt"))
    }

    private fun loadDictionary(dictionary: Reader, chars: File): PinyinDecoder {
        return PinyinDecoder.load(
            dictionary,
            chars.reader(Charsets.UTF_8),
        )
    }

    private fun percentile(samples: LongArray, percentile: Double): Long =
        samples[(ceil(samples.size * percentile).toInt() - 1).coerceIn(samples.indices)]

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }

    private data class BenchmarkCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: String,
        val baselineTop1: Boolean,
        val baselineTop5: Boolean,
    )
}
