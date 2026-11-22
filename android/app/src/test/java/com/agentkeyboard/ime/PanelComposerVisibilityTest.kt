package com.agentkeyboard.ime

import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelComposerVisibilityTest {
    @Test
    fun `keyboard enter is available only on the configured chat surface`() {
        val state = PanelUiState()
        assertFalse(state.canUseChatComposer)
        state.configured = true
        assertTrue(state.canUseChatComposer)

        state.drawerTab = 0
        assertFalse(state.canUseChatComposer)
        state.drawerTab = 1
        assertFalse(state.canUseChatComposer)
        state.drawerTab = null
        state.chipSheetOpen = true
        assertFalse(state.canUseChatComposer)
        state.chipSheetOpen = false
        state.modelManageOpen = true
        assertFalse(state.canUseChatComposer)
        state.modelManageOpen = false
        state.confirmation = ConfirmationRequest(
            toolId = "web_search", kind = ConfirmationKind.FIRST_TIME,
            title = "Allow search", body = "", paramsPreview = "", targetLabel = "", reason = "", canRemember = true,
        )
        assertFalse(state.canUseChatComposer)
        state.confirmation = null
        assertTrue(state.canUseChatComposer)
    }
}
