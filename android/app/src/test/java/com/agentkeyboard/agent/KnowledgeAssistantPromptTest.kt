package com.agentkeyboard.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAssistantPromptTest {
    @Test
    fun `knowledge assistant can supplement private evidence with public web search`() {
        val prompt = SystemPrompts.build(TaskType.KNOWLEDGE_ASK, "", "")

        assertTrue(prompt.contains("kb_search"))
        assertTrue(prompt.contains("Never invent"))
        assertTrue(prompt.contains("private evidence is missing"))
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("private document passages"))
        assertTrue(prompt.contains("when the user explicitly asks you to send them"))
        assertTrue(prompt.contains("Use public topic terms in web_search by default"))
        assertFalse(prompt.contains("Never include private passages"))
        assertFalse(prompt.contains("before loading private evidence"))
        assertFalse(prompt.contains("use it without public tools"))
        assertFalse(prompt.contains("read_context"))
        assertFalse(prompt.contains("input box"))
    }

    @Test
    fun `profile - userMd与显式记忆注入工具任务`() {
        val prompt = SystemPrompts.build(
            TaskType.KNOWLEDGE_ASK, "", "",
            AgentProfile(userMd = "制造业质量管理", memories = listOf("偏好简洁回答", "常用英文术语")),
        )
        assertTrue(prompt.contains("User background (written by the user in user.md)"))
        assertTrue(prompt.contains("制造业质量管理"))
        assertTrue(prompt.contains("Facts the user previously asked to remember"))
        assertTrue(prompt.contains("- 偏好简洁回答"))
        assertTrue(prompt.contains("- 常用英文术语"))
    }

    @Test
    fun `profile - 空画像不产出文本 快捷动作不注入画像`() {
        assertFalse(
            SystemPrompts.build(TaskType.ASK, "", "").contains("user.md"),
        )
        val quick = SystemPrompts.build(
            TaskType.HELP_WRITE, "上文", "",
            AgentProfile(userMd = "秘密背景", memories = listOf("秘密事实")),
        )
        assertFalse(quick.contains("秘密背景"))
        assertFalse(quick.contains("秘密事实"))
    }

    @Test
    fun `profile - 过长画像被截断 不撑爆上下文窗口`() {
        val huge = "x".repeat(SystemPrompts.PROFILE_USER_MD_MAX_CHARS + 5_000)
        val prompt = SystemPrompts.build(
            TaskType.ASK, "", "",
            AgentProfile(userMd = huge, memories = List(200) { "记忆$it" }),
        )
        assertTrue("超长 user.md 应被截断", prompt.contains("user.md 已截断"))
        assertTrue("记忆条数应受限", prompt.contains("条记忆因长度限制未注入"))
        assertTrue("单条记忆应截断", prompt.contains("已截断"))
    }
}
