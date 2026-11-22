package com.agentkeyboard.ime.pinyin

/**
 * 拼音组合提交所需的最小候选接口。
 *
 * 生产实现是 [LibimePinyinEngine]；旧 [PinyinDecoder] 仅保留为离线
 * 回归夹具，避免 UI/组合态测试依赖 native 运行时。
 */
interface PinyinCandidateEngine {
    fun topCandidate(input: String): String?

    fun topCandidateForT9(input: String): String?
}
