package com.agentkeyboard.ime

import com.agentkeyboard.ime.pinyin.PinyinCandidateEngine

/**
 * Resolves text that must be committed before changing the pinyin mode/layout.
 * A mode switch is an input boundary: dropping a non-empty composition would
 * silently lose user input, so fall back to the raw composition when decoding
 * has no candidate.
 */
internal fun pendingPinyinCommit(
    pinyinMode: Boolean,
    buffer: String,
    layout: KeyboardLayout,
    decoder: PinyinCandidateEngine?,
): String? {
    if (!pinyinMode || buffer.isEmpty()) return null
    val candidate = when (layout) {
        KeyboardLayout.QWERTY -> decoder?.topCandidate(buffer)
        KeyboardLayout.T9 -> decoder?.topCandidateForT9(buffer)
    }
    return candidate ?: buffer
}
