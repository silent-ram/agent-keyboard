package com.agentkeyboard.agent

import com.agentkeyboard.ime.QuickAction
import com.agentkeyboard.ime.QuickActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 快捷动作 prompt 构建与消息流映射(12 号设计 §5 三动作语义表)。
 */
class QuickActionPromptsTest {

    // ==== SystemPrompts:三动作单轮 system prompt ====

    @Test
    fun `帮写 - 系统提示词含帮写助手语义与上文`() {
        val p = SystemPrompts.build(TaskType.HELP_WRITE, "今晚加班到九点,你的测试结果呢", "")
        assertTrue(p.contains("帮写助手"))
        assertTrue(p.contains("今晚加班到九点"))
        assertTrue(p.contains(SystemPrompts.DATA_BOUNDARY))
    }

    @Test
    fun `改写 - 系统提示词含改写语义且输出全文`() {
        val p = SystemPrompts.build(TaskType.REWRITE, "", "这话不中听,你重新想想")
        assertTrue(p.contains("改写"))
        assertTrue(p.contains("正式得体"))
        assertTrue(p.contains("输出改写后全文"))
    }

    @Test
    fun `翻译 - 中文源翻译为英文`() {
        val p = SystemPrompts.build(TaskType.TRANSLATE, "", "今天下午三点开会")
        assertTrue(p.contains("翻译为英文"))
    }

    @Test
    fun `翻译 - 英文源翻译为中文`() {
        val p = SystemPrompts.build(TaskType.TRANSLATE, "", "The meeting starts at 3pm")
        assertTrue(p.contains("翻译为中文"))
    }

    @Test
    fun `翻译目标语规则 - 含CJK为英文,纯拉丁为中文`() {
        assertEquals("英文", SystemPrompts.targetLang("明天开会"))
        assertEquals("中文", SystemPrompts.targetLang("see you tomorrow"))
        assertEquals("中文", SystemPrompts.targetLang(""))
    }

    // ==== QuickActions:消息流映射(用户气泡 / 模型 userText) ====

    @Test
    fun `动作映射 - 帮写为插入语义,改写翻译为替换语义`() {
        assertFalse(QuickAction.HELP_WRITE.replaceMode)
        assertTrue(QuickAction.REWRITE.replaceMode)
        assertTrue(QuickAction.TRANSLATE.replaceMode)
    }

    @Test
    fun `动作映射 - tag 与 taskType 对应`() {
        assertEquals(TaskType.HELP_WRITE, QuickAction.HELP_WRITE.taskType)
        assertEquals(TaskType.REWRITE, QuickAction.REWRITE.taskType)
        assertEquals(TaskType.TRANSLATE, QuickAction.TRANSLATE.taskType)
        assertEquals("✨帮写", QuickAction.HELP_WRITE.tag)
        assertEquals("🔁改写", QuickAction.REWRITE.tag)
        assertEquals("🌐翻译", QuickAction.TRANSLATE.tag)
    }

    @Test
    fun `用户气泡 - 帮写为固定话术,改写为原文摘要`() {
        assertTrue(QuickActions.userDisplay(QuickAction.HELP_WRITE, "上文若干").contains("回复"))
        assertEquals("这句话语气太冲", QuickActions.userDisplay(QuickAction.REWRITE, "这句话语气太冲"))
    }

    @Test
    fun `模型userText - 帮写为话术,改写翻译为源文本本身`() {
        assertTrue(QuickActions.modelUserText(QuickAction.HELP_WRITE, "x").contains("帮我写"))
        assertEquals("源文本", QuickActions.modelUserText(QuickAction.REWRITE, "源文本"))
        assertEquals("源文本", QuickActions.modelUserText(QuickAction.TRANSLATE, "源文本"))
    }

    @Test
    fun `摘要 - 去换行截32字,空文本占位`() {
        assertEquals("abc def", QuickActions.summarize("abc\ndef"))
        assertEquals("(空)", QuickActions.summarize("  "))
        assertEquals(32, QuickActions.summarize("长".repeat(50)).length)
    }
}
