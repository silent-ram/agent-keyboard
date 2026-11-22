package com.agentkeyboard.ime

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Buffers Fcitx commits for the assistant composer without touching the host editor. */
internal class PanelInputBuffer(
    private val onChanged: (String) -> Unit,
    private val onValueChanged: (TextFieldValue) -> Unit,
) {
    constructor(onChanged: (String) -> Unit) : this(onChanged, {})

    private var committed = ""
    private var preedit = ""
    private var selection = TextRange.Zero

    val value: TextFieldValue
        get() = TextFieldValue(
            text,
            if (preedit.isEmpty()) selection else TextRange(selection.min + preedit.length),
        )

    fun setValue(value: TextFieldValue) {
        if (preedit.isEmpty()) {
            committed = value.text
            selection = clamp(value.selection, committed.length)
        } else {
            val composingStart = selection.min
            val composingEnd = composingStart + preedit.length
            val replacedLength = selection.max - selection.min
            val newSelection = when {
                value.selection.max <= composingStart -> value.selection
                value.selection.min >= composingEnd -> TextRange(
                    value.selection.start - preedit.length + replacedLength,
                    value.selection.end - preedit.length + replacedLength,
                )
                else -> selection
            }
            selection = clamp(newSelection, committed.length)
        }
        publish()
    }

    val text: String
        get() = if (preedit.isEmpty()) committed else committed.replaceRange(selection.min, selection.max, preedit)

    fun updatePreedit(value: String) {
        preedit = value
        publish()
    }

    fun commit(value: String) {
        if (value.isEmpty()) {
            preedit = ""
            publish()
            return
        }
        val start = selection.min
        val end = selection.max
        committed = committed.replaceRange(start, end, value)
        selection = TextRange(start + value.length)
        preedit = ""
        publish()
    }

    fun insertLineBreak() {
        // Fcitx handles candidate confirmation before forwarding Return.
        if (preedit.isEmpty()) commit("\n")
    }

    fun setText(value: String) {
        committed = value
        preedit = ""
        selection = TextRange(value.length)
        publish()
    }

    fun backspace() {
        if (preedit.isNotEmpty()) {
            preedit = ""
        } else if (!selection.collapsed) {
            committed = committed.removeRange(selection.min, selection.max)
            selection = TextRange(selection.min)
        } else if (selection.start > 0) {
            val previousCodePoint = committed.offsetByCodePoints(selection.start, -1)
            committed = committed.removeRange(previousCodePoint, selection.start)
            selection = TextRange(previousCodePoint)
        }
        publish()
    }

    fun deleteSurrounding(before: Int, after: Int) {
        if (preedit.isNotEmpty()) {
            // 组合期先固化 preedit 再删除:value 呈现的光标在 selection.min + preedit.length,
            // 直接按 committed 坐标删会错位删到组合区之前;固化后光标即可见光标。
            commit(preedit)
        }
        var start = selection.min
        var end = selection.max
        if (before > 0) {
            repeat(before.coerceAtMost(committed.codePointCount(0, start))) {
                start = committed.offsetByCodePoints(start, -1)
            }
        }
        if (after > 0) {
            repeat(after.coerceAtMost(committed.codePointCount(end, committed.length))) {
                end = committed.offsetByCodePoints(end, 1)
            }
        }
        committed = committed.removeRange(start, end)
        selection = TextRange(start)
        publish()
    }

    fun clear() {
        committed = ""
        preedit = ""
        selection = TextRange.Zero
        publish()
    }

    private fun clamp(range: TextRange, length: Int) = TextRange(
        range.start.coerceIn(0, length),
        range.end.coerceIn(0, length),
    )

    private fun publish() {
        onChanged(text)
        onValueChanged(value)
    }
}

/** Chooses exactly one destination for text produced by the active IME. */
internal class ImeTextInputRouter(
    assistantBuffer: PanelInputBuffer,
) {
    enum class Target { HOST, ASSISTANT }

    var panelBuffer: PanelInputBuffer = assistantBuffer

    var target: Target = Target.HOST

    fun preedit(value: String, host: () -> Unit): Boolean = route(
        assistant = { panelBuffer.updatePreedit(value) },
        host = host,
    )

    fun commit(value: String, host: () -> Unit): Boolean = route(
        assistant = { panelBuffer.commit(value) },
        host = host,
    )

    fun backspace(host: () -> Unit): Boolean = route(
        assistant = panelBuffer::backspace,
        host = host,
    )

    fun deleteSurrounding(before: Int, after: Int, host: () -> Unit): Boolean = route(
        assistant = { panelBuffer.deleteSurrounding(before, after) },
        host = host,
    )

    private fun route(assistant: () -> Unit, host: () -> Unit): Boolean {
        if (target == Target.ASSISTANT) {
            assistant()
            return true
        }
        host()
        return false
    }
}
