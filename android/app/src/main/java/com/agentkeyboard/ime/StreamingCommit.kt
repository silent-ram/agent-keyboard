package com.agentkeyboard.ime

/**
 * 流式上屏(FR-D2)的纯逻辑判定部分(M1-T6,可单测):
 * - 每次更新前校验光标前文本仍以已写入内容结尾(用户没动编辑器);
 * - setComposingText 后校验编辑器是否保留 composing(不保留 → 自动退回预览)。
 */
object ComposingVerifier {

    enum class Verdict { OK, USER_EDITED, NOT_SUPPORTED }

    /**
     * 更新前判定:[expected] 为已写入并固化的内容(可能为空串),
     * [beforeCursor] 为光标前文本(null = 编辑器不支持查询)。
     */
    fun verify(expected: String, beforeCursor: String?): Verdict = when {
        beforeCursor == null -> Verdict.NOT_SUPPORTED
        expected.isEmpty() -> Verdict.OK
        beforeCursor.endsWith(expected) -> Verdict.OK
        else -> Verdict.USER_EDITED
    }

    /** setComposingText 之后:composing 文本应出现在光标前;否则编辑器不保留 composing */
    fun keepsComposing(text: String, beforeCursor: String?): Boolean =
        beforeCursor != null && beforeCursor.endsWith(text)
}
