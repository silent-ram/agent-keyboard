package com.agentkeyboard.ime

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTextCommandsTest {
    @Test
    fun onlyLocalEditingCommandsAreAllowed() {
        assertTrue(isLocalPanelTextCommand(TextContextMenuKeys.CopyKey))
        assertTrue(isLocalPanelTextCommand(TextContextMenuKeys.CutKey))
        assertTrue(isLocalPanelTextCommand(TextContextMenuKeys.PasteKey))
        assertTrue(isLocalPanelTextCommand(TextContextMenuKeys.SelectAllKey))
        assertFalse(isLocalPanelTextCommand(Any()))
    }
}
