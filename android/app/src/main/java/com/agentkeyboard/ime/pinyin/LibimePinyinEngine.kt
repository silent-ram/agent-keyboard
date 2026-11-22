package com.agentkeyboard.ime.pinyin

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * libime 拼音引擎的 Android 适配层。
 *
 * 生产输入路径只依赖这一层，不再把自研 DP 解码器作为候选来源。
 * 词典、语言模型和用户历史均存放在应用私有 filesDir，不读取宿主文本。
 */
class LibimePinyinEngine private constructor(
    private val dataDir: File,
    private val t9Syllables: Set<String>,
    private val t9Lexicon: Map<String, List<T9LexiconEntry>>,
) : Closeable, PinyinCandidateEngine {

    private val lock = ReentrantLock()
    private var lastInput = ""
    private val candidateInputs = LinkedHashMap<String, String>()
    private var closed = false

    init {
        val dictionary = File(dataDir, "sc.dict")
        val model = File(dataDir, "zh_CN.lm")
        val history = File(dataDir, "user.history")
        val extraDictionary = File(dataDir, "extb.dict")
        nativeInit(
            dictionary.absolutePath,
            model.absolutePath,
            history.absolutePath,
            extraDictionary.absolutePath,
        )
    }

    fun candidates(input: String, limit: Int = MAX_CANDIDATES): List<String> =
        lock.withLock {
            checkOpen()
            if (input.isBlank()) return emptyList()
            nativeSetInput(input)
            val result = nativeCandidates(limit).orEmpty().toList()
            lastInput = input
            candidateInputs.clear()
            result.forEach { candidateInputs.putIfAbsent(it, input) }
            result
        }

    /**
     * 九宫格仍复用成熟整句解码：只展开到有限候选，按 libime 的整句结果排序去重。
     * 该路径不是默认 26 键路径，限制展开规模避免长串造成指数级开销。
     */
    fun candidatesForT9(input: String, limit: Int = MAX_CANDIDATES): List<String> =
        lock.withLock {
            checkOpen()
            if (input.isBlank()) return emptyList()
            val ordered = LinkedHashMap<String, String>()
            t9Lexicon[input].orEmpty().forEach { entry ->
                ordered.putIfAbsent(entry.word, entry.pinyin)
            }
            if (ordered.size >= limit) {
                val result = ordered.keys.take(limit)
                lastInput = input
                candidateInputs.clear()
                result.forEach { candidateInputs[it] = ordered[it].orEmpty() }
                return result
            }
            val scores = LinkedHashMap<String, RankedCandidate>()
            val perExpansionLimit = maxOf(limit, MAX_CANDIDATES)
            var sequence = 0
            val expansions = T9Pinyin.segmentedExpand(
                input,
                t9Syllables,
                T9_EXPANSION_LIMIT,
            ).ifEmpty {
                T9Pinyin.expand(input, T9_EXPANSION_LIMIT)
            }
            expansions.forEach { pinyin ->
                nativeSetInput(pinyin)
                val candidates = nativeCandidates(perExpansionLimit).orEmpty().toList()
                val nativeScores = nativeCandidateScores(perExpansionLimit) ?: FloatArray(0)
                candidates.forEachIndexed { index, candidate ->
                    val score = nativeScores.getOrNull(index) ?: (limit - index).toFloat()
                    val current = scores[candidate]
                    if (current == null || score > current.score) {
                        scores[candidate] = RankedCandidate(candidate, score, sequence, pinyin)
                    }
                }
                sequence += 1
            }
            val result = scores.values
                .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.sequence })
                .map { it.word }
                .fold(ordered) { all, word ->
                    all.putIfAbsent(word, scores[word]?.sourceInput.orEmpty())
                    all
                }
                .keys
                .take(limit)
            lastInput = input
            candidateInputs.clear()
            result.forEach { candidateInputs[it] = ordered[it].orEmpty() }
            result
        }

    override fun topCandidate(input: String): String? =
        lock.withLock {
            checkOpen()
            if (input.isBlank()) return null
            nativeSetInput(input)
            val result = nativeTopCandidate()
            lastInput = input
            candidateInputs.clear()
            result?.let { candidateInputs[it] = input }
            result
        }

    override fun topCandidateForT9(input: String): String? =
        candidatesForT9(input, 1).firstOrNull()

    /**
     * 按候选文本选词并学习。候选栏使用当前 native context 的顺序，
     * 因此不会因为 Kotlin 侧二次排序而把学习索引错位。
     */
    fun selectCandidate(word: String): Boolean =
        lock.withLock {
            checkOpen()
            val sourceInput = candidateInputs[word] ?: lastInput
            if (sourceInput.isBlank()) return false
            nativeSetInput(sourceInput)
            val candidates = nativeCandidates(MAX_CANDIDATES).orEmpty()
            val index = candidates.indexOfFirst { it == word }
            if (index < 0) return false
            nativeSelectCandidate(index) != null
        }

    fun reset() = lock.withLock {
        if (!closed) {
            nativeReset()
            lastInput = ""
            candidateInputs.clear()
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            nativeSaveHistory()
            nativeDestroy()
            lastInput = ""
            candidateInputs.clear()
            closed = true
        }
    }

    private fun checkOpen() {
        check(!closed) { "libime engine is closed" }
    }

    private data class RankedCandidate(
        val word: String,
        val score: Float,
        val sequence: Int,
        val sourceInput: String,
    )

    private data class T9LexiconEntry(
        val word: String,
        val pinyin: String,
        val frequency: Long,
    )

    companion object {
        const val MAX_BUFFER = 64
        const val MAX_CANDIDATES = 24
        private const val T9_EXPANSION_LIMIT = 512
        private const val T9_LEXICON_LIMIT = 32
        private const val DATA_DIR_NAME = "libime"

        init {
            System.loadLibrary("agentime")
        }

        fun create(context: Context): LibimePinyinEngine {
            val dataDir = File(context.filesDir, DATA_DIR_NAME).apply { mkdirs() }
            copyAssetIfNeeded(context, "libime/sc.dict", File(dataDir, "sc.dict"))
            copyAssetIfNeeded(context, "libime/zh_CN.lm", File(dataDir, "zh_CN.lm"))
            copyAssetIfNeeded(
                context,
                "libime/zh_CN.lm.predict",
                File(dataDir, "zh_CN.lm.predict"),
            )
            copyAssetIfNeeded(context, "libime/extb.dict", File(dataDir, "extb.dict"))
            val t9Syllables = context.assets.open("pinyin_chars.txt").bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.substringBefore('\t').trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            val t9Lexicon = context.assets.open("pinyin_dict.txt").bufferedReader().use { reader ->
                val grouped = LinkedHashMap<String, MutableList<T9LexiconEntry>>()
                reader.lineSequence().forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size < 3) return@forEach
                    val pinyin = fields[0].trim().lowercase()
                    val word = fields[1].trim()
                    val frequency = fields[2].trim().toLongOrNull() ?: return@forEach
                    val signature = T9Pinyin.signature(pinyin)
                    if (signature.isNotEmpty() && word.isNotEmpty()) {
                        grouped.getOrPut(signature) { ArrayList() }
                            .add(T9LexiconEntry(word, pinyin, frequency))
                    }
                }
                grouped.mapValues { (_, entries) ->
                    entries
                        .sortedWith(
                            compareByDescending<T9LexiconEntry> { it.frequency }
                                .thenBy { it.word },
                        )
                        .distinctBy { it.word }
                        .take(T9_LEXICON_LIMIT)
                }
            }
            return LibimePinyinEngine(dataDir, t9Syllables, t9Lexicon)
        }

        private fun copyAssetIfNeeded(context: Context, assetPath: String, target: File) {
            val expectedSize = context.assets.open(assetPath).use { it.available().toLong() }
            if (target.isFile && target.length() == expectedSize) return
            val temp = File(target.parentFile, "${target.name}.tmp")
            context.assets.open(assetPath).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            check(temp.renameTo(target) || temp.copyTo(target, overwrite = true).let { temp.delete(); true }) {
                "failed to install libime asset $assetPath"
            }
        }

        @JvmStatic
        private external fun nativeInit(
            dictionaryPath: String,
            modelPath: String,
            historyPath: String,
            extraDictionaryPath: String,
        ): Boolean

        @JvmStatic
        private external fun nativeDestroy()

        @JvmStatic
        private external fun nativeSetInput(input: String): Boolean

        @JvmStatic
        private external fun nativeCandidates(limit: Int): Array<String>?

        @JvmStatic
        private external fun nativeCandidateScores(limit: Int): FloatArray?

        @JvmStatic
        private external fun nativeSelectCandidate(index: Int): String?

        @JvmStatic
        private external fun nativeTopCandidate(): String?

        @JvmStatic
        private external fun nativeReset()

        @JvmStatic
        private external fun nativeSaveHistory()
    }
}
