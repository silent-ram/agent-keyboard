package com.agentkeyboard.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentkeyboard.tools.ToolIds
import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentFactoryKnowledgeAssistantTest {
    @Test
    fun `app agent exposes knowledge and web search tools`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kit = AgentFactory.createKnowledgeAssistant(
            context,
            ConfirmationResolver { true },
        )

        assertEquals(listOf(ToolIds.WEB_SEARCH, ToolIds.KB_SEARCH), kit.registry.schemas().map { it.id })
        assertTrue(kit.registry.get(ToolIds.WEB_SEARCH)!!.isOutbound)
        assertEquals("Tavily · api.tavily.com", kit.registry.get(ToolIds.WEB_SEARCH)!!.targetLabel)
        assertTrue(kit.registry.get(ToolIds.KB_SEARCH)!!.isOutbound)
        assertEquals("资料检索", kit.registry.get(ToolIds.KB_SEARCH)!!.targetLabel)
    }

    @Test
    fun `llm disclosure resource states that retrieved document text is sent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val body = context.getString(R.string.llm_disclosure_body)
        assertTrue(body.contains("相关片段"))
        org.junit.Assert.assertFalse(body.contains("智谱"))
        assertTrue(body.contains("外部模型服务"))
    }
}
