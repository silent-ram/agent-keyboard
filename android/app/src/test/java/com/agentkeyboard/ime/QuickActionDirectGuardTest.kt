package com.agentkeyboard.ime

import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.UserContentFlags
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0(评审修正单,权限红线):直出路径 llm_call 首次明示守卫(QuickActionDirectGuard)。
 * 未明示 → 直出被重定向至面板路径(mock gate),不发起调用、不落日志;
 * 已明示 → 直出放行且 llm_call 活动日志落一条(fc 探测同口径:preview 脱敏 + CONTEXT 标注)。
 */
class QuickActionDirectGuardTest {

    @Test
    fun `未明示 - 直出被重定向至面板路径,不发起调用不落日志`() = runTest {
        // mock gate:llm_call 尚未明示(UNASKED,permission_state 无 ALWAYS_ALLOW 行)
        val guard = QuickActionDirectGuard(
            llmDisclosed = { false },
            logLlmCall = { _ -> org.junit.Assert.fail("未明示时不应落 llm_call 日志") },
        )
        var directRan = false
        var panelRan = false
        val wentDirect = guard.dispatch(
            QuickAction.HELP_WRITE,
            source = "客户问发货进度,帮我回复",
            direct = { directRan = true }, // 宿主直出路径(runQuickComplete)——绝不发生
            toPanel = { panelRan = true }, // 宿主转投 startQuickAction 面板路径
        )
        assertFalse("未明示时不得直出", wentDirect)
        assertFalse("直出调用不应发起", directRan)
        assertTrue("应转投面板路径(首次明示确认条在面板呈现)", panelRan)
    }

    @Test
    fun `已明示 - 直出放行且 llm_call 日志落一条(脱敏预览 + CONTEXT 标注 + AUTO_ALLOW)`() = runTest {
        val logs = mutableListOf<ActivityLogEntity>()
        val guard = QuickActionDirectGuard(
            llmDisclosed = { true }, // mock gate:已 ALWAYS_ALLOW
            logLlmCall = { logs += it },
            clock = { 1_000L },
        )
        var directRan = false
        var panelRan = false
        val wentDirect = guard.dispatch(
            QuickAction.HELP_WRITE,
            source = "回复 13812345678 的催单请求",
            direct = { directRan = true },
            toPanel = { panelRan = true },
        )
        assertTrue("已明示时直出放行", wentDirect)
        assertTrue(directRan)
        assertFalse("已明示不再走面板路径", panelRan)
        // 日志恰一条,口径与 fc 探测同源(llm_call + AUTO_ALLOW),用户内容标注 CONTEXT
        assertEquals(1, logs.size)
        val entry = logs[0]
        assertEquals("llm_call", entry.toolId)
        assertEquals(LogOutcomes.AUTO_ALLOW, entry.outcome)
        assertEquals(UserContentFlags.CONTEXT, entry.containsUserContent)
        assertEquals("模型服务", entry.target)
        assertEquals(1_000L, entry.ts)
        // preview 走 LogSanitizer:手机号必须打码(直出源文本含输入框原文)
        assertTrue(entry.preview.contains("****"))
        assertFalse("preview 不应含未脱敏手机号", entry.preview.contains("13812345678"))
    }
}
