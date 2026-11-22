package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-A 替换执行核心单测(ReplaceExecutor:面板「🔁 替换」与快捷动作直出共用):
 * 快照失效降级插入 / code point 对齐删除 / 选区路径。
 */
class ReplaceExecutorTest {

    /** 记录型 fake:维护简单文本状态 + 操作序列,语义对齐 InputConnection */
    private class FakeOps(
        initialText: String,
        var selStart: Int = initialText.length,
        var selEnd: Int = selStart,
    ) : ReplaceExecutor.ReplaceOps {
        var text = initialText
        val ops = mutableListOf<String>()

        override fun selectedText(): String? =
            if (selStart == selEnd) null else text.substring(selStart, selEnd)

        override fun textBefore(maxChars: Int): String? =
            text.substring(0, selStart).takeLast(maxChars)

        override fun deleteBefore(javaChars: Int, codePoints: Int) {
            ops += "delete(cp=$codePoints)"
            text = text.substring(0, selStart - javaChars) + text.substring(selStart)
            selStart -= javaChars
            selEnd = selStart
        }

        override fun commit(t: String) {
            ops += "commit"
            text = text.substring(0, selStart) + t + text.substring(selEnd)
            selStart += t.length
            selEnd = selStart
        }
    }

    // ==== 无选区:快照匹配 → code point 对齐删除后插入 ====

    @Test
    fun `无选区 - 快照匹配时删除旧文本并插入新文本`() {
        val ops = FakeOps("今天天气不错") // 光标在末尾,快照 = 全部上文
        val outcome = ReplaceExecutor.execute(ops, "今天天气不错", "今天天气很好", fromSelection = false)
        assertTrue(outcome.applied)
        assertNull(outcome.degradedReason)
        assertEquals(listOf("delete(cp=6)", "commit"), ops.ops)
        assertEquals("今天天气很好", ops.text)
    }

    @Test
    fun `无选区 - 快照匹配时 emoji 按 code point 对齐删除`() {
        val ops = FakeOps("世界好😀") // 快照 = 光标前尾部 "好😀"(emoji 紧贴光标)
        val outcome = ReplaceExecutor.execute(ops, "好😀", "棒", fromSelection = false)
        assertTrue(outcome.applied)
        // "好😀" = 3 Java chars / 2 code points → 按 cp=2 删除,不截断代理对
        assertEquals(listOf("delete(cp=2)", "commit"), ops.ops)
        assertEquals("世界棒", ops.text)
    }

    // ==== 无选区:快照失效 → 降级插入(不覆盖原文)====

    @Test
    fun `无选区 - 快照失效降级为插入并给出去因`() {
        val ops = FakeOps("用户等待期间改了内容")
        val outcome = ReplaceExecutor.execute(ops, "原快照文本", "新文本", fromSelection = false)
        assertTrue(outcome.applied)
        assertEquals("原文已变化,已改为插入", outcome.degradedReason)
        assertEquals(listOf("commit"), ops.ops) // 无删除,仅插入
        assertEquals("用户等待期间改了内容新文本", ops.text)
    }

    // ==== 有选区:快照匹配 → commitText 标准行为(删选区并插入)====

    @Test
    fun `有选区 - 选区与快照一致时替换选区`() {
        val ops = FakeOps("abcdef", selStart = 2, selEnd = 5) // 选区 "cde"
        val outcome = ReplaceExecutor.execute(ops, "cde", "XYZ", fromSelection = true)
        assertTrue(outcome.applied)
        assertNull(outcome.degradedReason)
        assertEquals(listOf("commit"), ops.ops)
        assertEquals("abXYZf", ops.text)
    }

    @Test
    fun `有选区 - 选区已变化降级插入`() {
        val ops = FakeOps("abcdef", selStart = 0, selEnd = 2) // 用户改选区为 "ab"
        val outcome = ReplaceExecutor.execute(ops, "cde", "XYZ", fromSelection = true)
        assertTrue(outcome.applied)
        assertEquals("原文选区已变化,已改为插入", outcome.degradedReason)
        // commitText 语义 = 删除当前选区并插入(降级插入即替换当前选区,不触碰原快照内容)
        assertEquals("XYZcdef", ops.text)
    }

    @Test
    fun `有选区 - 编辑器不报告选区时降级插入`() {
        val ops = FakeOps("文本")
        val outcome = ReplaceExecutor.execute(ops, "原文", "新文", fromSelection = true)
        assertTrue(outcome.applied)
        assertTrue(outcome.degradedReason!!.contains("改为插入"))
    }

    // ==== 参数防御 ====

    @Test
    fun `空快照不执行`() {
        val ops = FakeOps("文本")
        val outcome = ReplaceExecutor.execute(ops, "", "新文本", fromSelection = false)
        assertFalse(outcome.applied)
        assertTrue(ops.ops.isEmpty())
    }
}
