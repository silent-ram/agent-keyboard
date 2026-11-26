package com.agentkeyboard.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答前自检与轻量规划(只靠提示,不另发模型请求):资料问答与查证带,其余任务类型都不带 */
class PlanningAndSelfCheckPromptTest {

    @Test
    fun `资料问答与查证 - 带轻量规划与回答前自检`() {
        for (type in listOf(TaskType.KNOWLEDGE_ASK, TaskType.VERIFY)) {
            val prompt = SystemPrompts.build(type, "", "问一下")
            assertTrue("$type 应提示先想清楚再检索", prompt.contains(SystemPrompts.LIGHT_PLANNING))
            assertTrue("$type 应提示回答前对照资料自检", prompt.contains(SystemPrompts.SELF_CHECK))
        }
    }

    @Test
    fun `自由问答与快捷动作都不带新段落`() {
        val ask = SystemPrompts.build(TaskType.ASK, "", "问")
        assertFalse(ask.contains(SystemPrompts.LIGHT_PLANNING))
        assertFalse(ask.contains(SystemPrompts.SELF_CHECK))
        for (type in listOf(TaskType.HELP_WRITE, TaskType.REWRITE, TaskType.TRANSLATE)) {
            val quick = SystemPrompts.build(type, "", "你好")
            assertFalse("$type 不带规划", quick.contains(SystemPrompts.LIGHT_PLANNING))
            assertFalse("$type 不带自检", quick.contains(SystemPrompts.SELF_CHECK))
        }
    }

    @Test
    fun `规划与自检不放宽数据边界`() {
        val prompt = SystemPrompts.build(TaskType.KNOWLEDGE_ASK, "", "问")
        // 与 KnowledgeAssistantPromptTest 的禁用措辞同口径:新加的段落不能把旧的硬性要求改写成软建议
        for (banned in listOf("Never include private passages", "before loading private evidence", "use it without public tools")) {
            assertFalse(banned, prompt.contains(banned))
        }
        assertTrue(prompt.contains(SystemPrompts.DATA_BOUNDARY))
        assertTrue(prompt.contains("Never invent"))
    }
}
