package com.agentkeyboard.ime.pinyin

import java.io.BufferedReader
import java.io.Reader

/**
 * 轻量拼音解码器(M1-T5,纯 Kotlin):
 * - 词库:assets/pinyin_dict.txt,格式为 拼音串\t词\t权重;
 * - 单字:assets/pinyin_chars.txt(音节\t候选字串,按频序,现 402 行);
 * - 解码:精确拼音、简拼、模糊音按来源权重排序,再补充音节切分的单字组合;
 * - 解码器不依赖 Android 框架(Reader 加载),可直接 JVM 单测。
 */
class PinyinDecoder private constructor(
    private val dict: Map<String, List<WeightedWord>>,
    private val syllableChars: Map<String, String>,
) : PinyinCandidateEngine {

    private data class WeightedWord(val word: String, val frequency: Long)
    private data class CandidateScore(
        val word: String,
        val score: Long,
        val sourcePriority: Int,
        val sequence: Int,
    )

    val syllables: Set<String> = syllableChars.keys
    private var t9Dict: Map<String, List<String>> = emptyMap()
    /** 多音节简拼索引,例如 nihao -> nh。首次按键前在后台解码加载阶段构建。 */
    private var abbreviationDict: Map<String, List<WeightedWord>> = emptyMap()

    private fun prepareIndexes() {
        val grouped = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        dict.forEach { (pinyin, words) ->
            val signature = T9Pinyin.signature(pinyin)
            if (signature.isNotEmpty()) {
                val target = grouped.getOrPut(signature) { LinkedHashMap() }
                words.forEach { word ->
                    target[word.word] = maxOf(target[word.word] ?: 0L, word.frequency)
                }
            }
        }
        t9Dict = grouped.mapValues { (_, words) ->
            words.entries.sortedByDescending { it.value }.map { it.key }
        }

        val abbreviations = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        dict.forEach { (pinyin, words) ->
            val split = bestSplit(pinyin)
            if (split.size < 2 || split.any { it.any(Char::isDigit) }) return@forEach
            val abbreviation = split.joinToString(separator = "") { it.first().toString() }
            if (abbreviation.length < 2) return@forEach
            val target = abbreviations.getOrPut(abbreviation) { LinkedHashMap() }
            words.forEach { word ->
                target[word.word] = maxOf(target[word.word] ?: 0L, word.frequency)
            }
        }
        abbreviationDict = abbreviations.mapValues { (_, words) ->
            words.entries.sortedByDescending { it.value }
                .map { WeightedWord(it.key, it.value) }
        }
    }

    companion object {
        const val MAX_CANDIDATES = 24
        const val MAX_BUFFER = 24
        private const val MAX_FUZZY_VARIANTS = 24
        private const val MAX_FUZZY_DEPTH = 2
        private val FUZZY_REPLACEMENTS = listOf(
            "zh" to "z",
            "ch" to "c",
            "sh" to "s",
            "iang" to "ian",
            "uang" to "uan",
            "eng" to "en",
            "ing" to "in",
            "ang" to "an",
            "z" to "zh",
            "c" to "ch",
            "s" to "sh",
            "ian" to "iang",
            "uan" to "uang",
            "en" to "eng",
            "in" to "ing",
            "an" to "ang",
        )

        fun load(dict: Reader, chars: Reader): PinyinDecoder {
            val raw = LinkedHashMap<String, LinkedHashMap<String, Long>>()
            BufferedReader(dict).forEachLine { line ->
                val fields = line.trim().split('\t')
                if (fields.size < 2) return@forEachLine
                val py = fields[0].trim()
                if (py.isEmpty()) return@forEachLine
                val second = fields[1].trim()
                val third = fields.getOrNull(2)?.trim().orEmpty()
                val frequencyFirst = second.toLongOrNull()
                val word = if (frequencyFirst != null && third.isNotEmpty()) third else second
                val frequency = if (frequencyFirst != null && third.isNotEmpty()) {
                    frequencyFirst
                } else {
                    third.toLongOrNull() ?: 0L
                }
                if (word.isEmpty()) return@forEachLine
                val words = raw.getOrPut(py) { LinkedHashMap() }
                words[word] = maxOf(
                    words[word] ?: 0L,
                    frequency.coerceIn(0L, Long.MAX_VALUE / 100),
                )
            }
            val d = raw.mapValues { (_, words) ->
                words.entries.sortedByDescending { it.value }
                    .map { WeightedWord(it.key, it.value) }
            }
            val s = HashMap<String, String>()
            BufferedReader(chars).forEachLine { line ->
                val i = line.indexOf('\t')
                if (i <= 0) return@forEachLine
                s[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            return PinyinDecoder(d, s).apply { prepareIndexes() }
        }
    }

    /** 输入是否可能仍在音节前缀上(用于判断是否继续缓冲) */
    fun hasSyllablePrefix(s: String): Boolean {
        if (s.isEmpty()) return true
        val split = bestSplit(s)
        if (split.isEmpty()) return false
        if (split.dropLast(1).any { it !in syllables }) return false
        val last = split.last()
        return last in syllables || syllables.any { it.startsWith(last) }
    }

    /** 最优音节切分(word-break DP,保一条);不可完全切分时返回最长可切前缀 + 剩余字母 */
    fun bestSplit(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val n = input.length
        val from = IntArray(n + 1) { -1 } // from[i] = i 之前的切点
        val reach = BooleanArray(n + 1)
        reach[0] = true
        for (i in 0 until n) {
            if (!reach[i]) continue
            for (j in i + 1..minOf(n, i + 6)) {
                if (reach[j]) continue
                if (input.substring(i, j) in syllables) {
                    reach[j] = true
                    from[j] = i
                }
            }
        }
        if (reach[n]) {
            val out = ArrayDeque<String>()
            var i = n
            while (i > 0) {
                out.addFirst(input.substring(from[i], i))
                i = from[i]
            }
            return out.toList()
        }
        // 不可完全切分:取最长可切前缀,剩余字母原样附加
        var last = n
        while (last > 0 && !reach[last]) last--
        if (last == 0) return listOf(input)
        val out = ArrayDeque<String>()
        var i = last
        while (i > 0) {
            out.addFirst(input.substring(from[i], i))
            i = from[i]
        }
        out.add(input.substring(last))
        return out
    }

    /** 候选栏拼音显示:ni hao → "ni'hao" */
    fun splitDisplay(input: String): String = bestSplit(input).joinToString("'")

    /**
     * 候选词:词库精确匹配(词优先)→ 音节切分的字级组合(首字笛卡尔,限幅)。
     * 例:ni → [你,尼,…];nihao → [你好,尼好,…,你…字组合]
     */
    fun candidates(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val ranked = LinkedHashMap<String, CandidateScore>()
        var sequence = 0
        fun add(words: List<WeightedWord>, sourcePriority: Int, penaltyPercent: Int) {
            words.forEach { word ->
                val candidate = CandidateScore(
                    word = word.word,
                    score = word.frequency * penaltyPercent / 100,
                    sourcePriority = sourcePriority,
                    sequence = sequence++,
                )
                val current = ranked[word.word]
                if (
                    current == null ||
                    candidate.score > current.score ||
                    (candidate.score == current.score && candidate.sourcePriority > current.sourcePriority)
                ) {
                    ranked[word.word] = candidate
                }
            }
        }

        add(dict[input].orEmpty(), sourcePriority = 3, penaltyPercent = 100)
        add(abbreviationDict[input].orEmpty(), sourcePriority = 2, penaltyPercent = 80)
        fuzzyVariants(input).forEach { variant ->
            add(dict[variant].orEmpty(), sourcePriority = 1, penaltyPercent = 90)
        }

        val out = ranked.values
            .sortedWith(
                compareByDescending<CandidateScore> { it.score }
                    .thenByDescending { it.sourcePriority }
                    .thenBy { it.sequence },
            )
            .take(MAX_CANDIDATES)
            .mapTo(mutableListOf()) { it.word }
        val seen = out.toHashSet()
        val split = bestSplit(input)
        if (split.isNotEmpty()) {
            // 首音节字列表 × 其余音节首字的字级组合(保序,限幅)
            val firstChars = syllableChars[split[0]].orEmpty()
            val restFirst = split.drop(1).map { syllableChars[it]?.take(1).orEmpty() }
            for (c in firstChars) {
                val combo = buildString { append(c); restFirst.forEach { append(it) } }
                if (seen.add(combo) && out.size < MAX_CANDIDATES) out.add(combo)
            }
        }
        return out.take(MAX_CANDIDATES)
    }

    /**
     * 九宫格候选:
     * 1. 先取完整键位签名对应的词条,保证完整词优先;
     * 2. 再取仍可能继续输入的词条前缀;
     * 3. 最后用受限字母展开回退到普通拼音候选。
     */
    fun candidatesForT9(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        t9Dict[digits].orEmpty().forEach { out += it }
        if (out.size < MAX_CANDIDATES) {
            t9Dict.asSequence()
                .filter { (signature, _) -> signature.startsWith(digits) && signature != digits }
                .sortedBy { it.key.length }
                .flatMap { it.value.asSequence() }
                .forEach {
                    if (out.size < MAX_CANDIDATES) out += it
                }
        }
        if (out.size < MAX_CANDIDATES) {
            T9Pinyin.expand(digits).forEach { pinyin ->
                candidates(pinyin).forEach {
                    if (out.size < MAX_CANDIDATES) out += it
                }
            }
        }
        return out.take(MAX_CANDIDATES)
    }

    /** 空格上屏的首选;无候选时返回 null(调用方回退为上屏字母串) */
    override fun topCandidate(input: String): String? = candidates(input).firstOrNull()

    /** 九宫格空格上屏的首选;无候选时调用方回退为数字串。 */
    override fun topCandidateForT9(digits: String): String? = candidatesForT9(digits).firstOrNull()

    private fun fuzzyVariants(input: String): List<String> {
        if (input.length < 2) return emptyList()
        val variants = LinkedHashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(input to 0)
        while (queue.isNotEmpty() && variants.size < MAX_FUZZY_VARIANTS) {
            val (current, depth) = queue.removeFirst()
            if (depth >= MAX_FUZZY_DEPTH) continue
            FUZZY_REPLACEMENTS.forEach { (from, to) ->
                var start = 0
                while (true) {
                    val index = current.indexOf(from, start)
                    if (index < 0) break
                    val next = current.replaceRange(index, index + from.length, to)
                    if (next != input && variants.add(next)) {
                        queue.add(next to (depth + 1))
                    }
                    start = index + from.length
                }
            }
        }
        return variants.toList()
    }

}
