package com.agentkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManageNavigationTest {
    @Test
    fun `default picker returns to model management and model details return to the provider`() {
        assertEquals(ManagePage.LIST, ManagePage.PICK_DEFAULT.parent)
        assertEquals(ManagePage.PROVIDER_DETAIL, ManagePage.MODEL_DETAIL.parent)
    }
}
