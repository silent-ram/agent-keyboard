package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 替换语义纯逻辑(12 号设计 §5 替换实现路径):
 * 快照失效降级 / code point 删除对齐 / 恢复校验 / 源文本选择。
 */
class ReplaceLogicTest {

    // ==== 有选区替换:快照比对失效降级 ====

    @Test
    fun `有选区 - 选区与快照一致可替换`() {
        val c = ReplaceLogic.checkSelection("原句内容", "原句内容")
        assertTrue(c.valid)
        assertFalse(c.degraded)
        assertNull(c.reason)
    }

    @Test
    fun `有选区 - 选区已变化降级插入并提示`() {
        val c = ReplaceLogic.checkSelection("原句内容", "原句内容(用户改过)")
        assertTrue(c.valid) // 仍执行,但降级
        assertTrue(c.degraded)
        assertTrue(c.reason!!.contains("改为插入"))
    }

    @Test
    fun `有选区 - 编辑器不报告选区(null)降级插入`() {
        val c = ReplaceLogic.checkSelection("原句", null)
        assertTrue(c.degraded)
    }

    @Test
    fun `有选区 - 空快照不执行`() {
        assertFalse(ReplaceLogic.checkSelection("", "任何").valid)
    }

    // ==== 无选区替换:光标前文本快照比对 ====

    @Test
    fun `无选区 - 光标前文本以快照结尾可替换`() {
        val c = ReplaceLogic.checkBefore("原文abc", "光标前的更早内容原文abc")
        assertTrue(c.valid)
        assertFalse(c.degraded)
    }

    @Test
    fun `无选区 - 光标前文本已变化降级插入`() {
        val c = ReplaceLogic.checkBefore("原文", "被用户改过的光标前内容")
        assertTrue(c.degraded)
        assertTrue(c.reason!!.contains("改为插入"))
    }

    @Test
    fun `无选区 - getTextBeforeCursor 返回 null 降级插入`() {
        val c = ReplaceLogic.checkBefore("原文", null)
        assertTrue(c.degraded)
    }

    // ==== code point 删除对齐(emoji/代理对不截断) ====

    @Test
    fun `删除长度 - 纯中文按 Java char 与 code point 相等`() {
        assertEquals(4 to 4, ReplaceLogic.deletionLengths("时间会议"))
    }

    @Test
    fun `删除长度 - emoji 增补字符 2 个 Java char 1 个 code point`() {
        // 😀 = U+1F600(增补区,占 2 Java char)
        val (chars, cps) = ReplaceLogic.deletionLengths("a😀b")
        assertEquals(4, chars) // a + 2 + b
        assertEquals(3, cps) // a + 😀 + b
    }

    @Test
    fun `删除长度 - 混排中文emoji不截断代理对`() {
        val text = "会议😀时间"
        val (chars, cps) = ReplaceLogic.deletionLengths(text)
        // 会议(2)+😀(2)+时间(2) = 6 Java chars;会/议/😀/时/间 = 5 code points
        assertEquals(6, chars)
        assertEquals(5, cps)
    }

    @Test
    fun `删除长度 - 空串`() {
        assertEquals(0 to 0, ReplaceLogic.deletionLengths(""))
    }

    // ==== 恢复原文校验 ====

    @Test
    fun `恢复 - 光标前以新文本结尾可反向替换`() {
        assertTrue(ReplaceLogic.checkRestore("新文本", "更早的内容新文本"))
    }

    @Test
    fun `恢复 - 位置已变化不可恢复`() {
        assertFalse(ReplaceLogic.checkRestore("新文本", "用户已经改写"))
        assertFalse(ReplaceLogic.checkRestore("新文本", null))
        assertFalse(ReplaceLogic.checkRestore("", "新文本"))
    }

    // ==== 源文本选择 ====

    @Test
    fun `源选择 - 选区优先`() {
        assertEquals("选中的话", ReplaceLogic.pickSource("选中的话", "光标前上文", 200))
    }

    @Test
    fun `源选择 - 无选区取光标前上文并截断到上限`() {
        val before = "前".repeat(300)
        assertEquals("前".repeat(200), ReplaceLogic.pickSource(null, before, 200))
    }

    @Test
    fun `源选择 - 选区为空白串视为无选区`() {
        assertEquals("上文", ReplaceLogic.pickSource("   ", "上文", 200))
    }

    @Test
    fun `源选择 - 皆空返回 null`() {
        assertNull(ReplaceLogic.pickSource(null, "", 200))
        assertNull(ReplaceLogic.pickSource(null, "   ", 200))
    }
}
