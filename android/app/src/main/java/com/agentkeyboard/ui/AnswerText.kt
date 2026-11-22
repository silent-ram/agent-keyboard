package com.agentkeyboard.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

private val answerParser = Parser.builder()
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

private data class BoldRange(val start: Int, val end: Int, val delimiterLength: Int)

/** Keep the original text layout and remove only delimiters parsed as strong emphasis. */
internal fun formatAnswerText(raw: String): AnnotatedString {
    if (!raw.contains("**") && !raw.contains("__")) return AnnotatedString(raw)

    val bold = mutableListOf<BoldRange>()
    answerParser.parse(raw).accept(object : AbstractVisitor() {
        override fun visit(strongEmphasis: StrongEmphasis) {
            val spans = strongEmphasis.sourceSpans
            if (spans.isNotEmpty()) {
                val last = spans.last()
                bold.add(BoldRange(spans.first().inputIndex, last.inputIndex + last.length, strongEmphasis.openingDelimiter.length))
            }
            visitChildren(strongEmphasis)
        }
    })
    if (bold.isEmpty()) return AnnotatedString(raw)

    val delimiters = BooleanArray(raw.length)
    for ((start, end, length) in bold) {
        delimiters.fill(true, start, start + length)
        delimiters.fill(true, end - length, end)
    }
    return buildAnnotatedString {
        val offsets = IntArray(raw.length + 1)
        raw.forEachIndexed { index, char ->
            if (!delimiters[index]) append(char)
            offsets[index + 1] = this.length
        }
        for ((start, end) in bold) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), offsets[start], offsets[end])
        }
    }
}
