package com.agentkeyboard.ime.pinyin

/**
 * 九宫格拼音的纯逻辑辅助。
 *
 * 9 键输入不是把一个数字直接当作拼音字母，而是先按键位反查候选拼音。
 * 词库索引由 [PinyinDecoder] 建立；这里仅负责稳定的键位映射和受限展开，
 * 便于在没有 Android 依赖的 JVM 单测中回归。
 */
object T9Pinyin {
    private val lettersByDigit = mapOf(
        '2' to "abc",
        '3' to "def",
        '4' to "ghi",
        '5' to "jkl",
        '6' to "mno",
        '7' to "pqrs",
        '8' to "tuv",
        '9' to "wxyz",
    )

    fun signature(pinyin: String): String = buildString {
        pinyin.lowercase().forEach { c ->
            val digit = lettersByDigit.entries.firstOrNull { c in it.value }?.key
            if (digit != null) append(digit)
        }
    }

    fun isPinyinDigit(digit: Char): Boolean = digit in lettersByDigit

    /**
     * 展开只用于词库前缀/单字回退，限制结果数量避免长串造成指数级内存增长。
     */
    fun expand(digits: String, limit: Int = 4096): List<String> {
        if (digits.isEmpty()) return emptyList()
        var result = listOf("")
        for (digit in digits) {
            val letters = lettersByDigit[digit] ?: return emptyList()
            val next = ArrayList<String>(minOf(limit, result.size * letters.length))
            result.forEach { prefix ->
                letters.forEach { letter ->
                    if (next.size < limit) next += prefix + letter
                }
            }
            result = next
            if (result.isEmpty()) break
        }
        return result
    }

    /**
     * 先按合法拼音音节切分数字串，再展开同一 T9 签名下的音节。
     * 比全量字母笛卡尔积小几个数量级，例如 64426 只保留能组成
     * `nihao` 的路径，避免候选刷新阻塞输入线程。
     */
    fun segmentedExpand(
        digits: String,
        syllables: Set<String>,
        limit: Int = 512,
    ): List<String> {
        if (digits.isEmpty() || limit <= 0) return emptyList()
        val bySignature = syllables
            .asSequence()
            .map { it.lowercase() to signature(it) }
            .filter { (_, encoded) -> encoded.isNotEmpty() }
            .groupBy({ it.second }, { it.first })
            .mapValues { (_, values) ->
                values.distinct().sortedWith(
                    compareByDescending<String> { it.length }.thenBy { it },
                )
            }
        val memo = HashMap<Int, List<String>>()

        fun visit(offset: Int): List<String> {
            if (offset == digits.length) return listOf("")
            memo[offset]?.let { return it }
            val output = ArrayList<String>()
            val maxEnd = minOf(digits.length, offset + MAX_SYLLABLE_DIGITS)
            for (end in maxEnd downTo offset + 1) {
                val options = bySignature[digits.substring(offset, end)].orEmpty()
                if (options.isEmpty()) continue
                val tails = visit(end)
                for (syllable in options) {
                    for (tail in tails) {
                        output += syllable + tail
                        if (output.size >= limit) {
                            val result = output.take(limit)
                            memo[offset] = result
                            return result
                        }
                    }
                }
            }
            val result = output.take(limit)
            memo[offset] = result
            return result
        }

        return visit(0)
    }

    private const val MAX_SYLLABLE_DIGITS = 6
}
