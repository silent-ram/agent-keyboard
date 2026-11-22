package com.agentkeyboard.ime

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelInputBufferTest {
    @Test
    fun lineBreakReplacesSelectionAndPreservesFollowingText() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("before remove after", TextRange(7, 14)))
        buffer.insertLineBreak()
        assertEquals("before \nafter", buffer.text)
        assertEquals(TextRange(8), buffer.value.selection)
    }

    @Test
    fun lineBreakDoesNotCommitPendingPinyin() {
        val buffer = PanelInputBuffer { }
        buffer.updatePreedit("ni")
        buffer.insertLineBreak()
        assertEquals("ni", buffer.text)
        buffer.commit("\u4f60")
        buffer.insertLineBreak()
        assertEquals("\u4f60\n", buffer.text)
    }

    @Test
    fun candidateReplacesSelectionAtTheVisibleCursor() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("查看旧答案", TextRange(2, 3)))

        buffer.updatePreedit("xin")
        buffer.commit("新")

        assertEquals("查看新答案", buffer.text)
        assertEquals(TextRange(3), buffer.value.selection)
    }

    @Test
    fun backspaceDeletesBeforeCursorAndPreservesFollowingText() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("A😀B", TextRange(3)))

        buffer.backspace()

        assertEquals("AB", buffer.text)
        assertEquals(TextRange(1), buffer.value.selection)
    }

    @Test
    fun deletingSelectionDoesNotDeleteTheLastCharacter() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("保留删除结尾", TextRange(4, 2)))

        buffer.backspace()

        assertEquals("保留结尾", buffer.text)
        assertEquals(TextRange(2), buffer.value.selection)
    }

    @Test
    fun preeditAppearsAtCursorAndCommitKeepsFollowingText() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("前后", TextRange(1)))

        buffer.updatePreedit("zhong")
        assertEquals("前zhong后", buffer.text)
        buffer.commit("中")

        assertEquals("前中后", buffer.text)
        assertEquals(TextRange(2), buffer.value.selection)
    }

    @Test
    fun deleteSurroundingUsesTheCursorAndKeepsTextAfterIt() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("前中后", TextRange(2)))

        buffer.deleteSurrounding(before = 1, after = 0)

        assertEquals("前后", buffer.text)
        assertEquals(TextRange(1), buffer.value.selection)
    }

    @Test
    fun deleteSurroundingDuringPreeditFreezesPreeditThenDeletesAroundVisibleCursor() {
        val buffer = PanelInputBuffer { }
        buffer.setValue(TextFieldValue("AB", TextRange(2)))

        buffer.updatePreedit("pin")
        // 可见光标在 selection.min + preedit.length(即 "ABpin|");删除应围绕可见光标,
        // 而不是按 committed 坐标删到组合区之前
        buffer.deleteSurrounding(before = 1, after = 0)

        assertEquals("ABpi", buffer.text)
        assertEquals(TextRange(4), buffer.value.selection)
    }

    @Test
    fun routerKeepsPanelTextOutOfHostEditor() {
        val values = mutableListOf<String>()
        val buffer = PanelInputBuffer(onChanged = values::add)
        val router = ImeTextInputRouter(buffer)
        var hostCommits = 0

        router.target = ImeTextInputRouter.Target.ASSISTANT
        assertTrue(router.preedit("ni") { hostCommits++ })
        assertTrue(router.commit("你") { hostCommits++ })
        assertEquals("你", buffer.text)
        assertEquals(0, hostCommits)

        router.target = ImeTextInputRouter.Target.HOST
        assertFalse(router.commit("好") { hostCommits++ })
        assertEquals(1, hostCommits)
        assertEquals("你", buffer.text)
    }

    @Test
    fun backspaceDeletesOneUnicodeCodePoint() {
        val values = mutableListOf<String>()
        val buffer = PanelInputBuffer(onChanged = values::add)
        val router = ImeTextInputRouter(buffer)
        router.target = ImeTextInputRouter.Target.ASSISTANT
        router.commit("答案😀") {}

        router.backspace {}

        assertEquals("答案", buffer.text)
    }

    @Test
    fun preeditIsReplacedByCommittedCandidate() {
        val values = mutableListOf<String>()
        val buffer = PanelInputBuffer(onChanged = values::add)
        buffer.commit("查")
        buffer.updatePreedit("zhao")
        buffer.commit("找")

        assertEquals("查找", buffer.text)
        assertEquals("查zhao", values[1])
        assertEquals("查找", values[2])
    }

    @Test
    fun routerCanTargetAnInPanelSettingsField() {
        val assistantValues = mutableListOf<String>()
        val fieldValues = mutableListOf<String>()
        val assistant = PanelInputBuffer(onChanged = assistantValues::add)
        val field = PanelInputBuffer(onChanged = fieldValues::add)
        val router = ImeTextInputRouter(assistant)
        router.target = ImeTextInputRouter.Target.ASSISTANT
        router.panelBuffer = field

        router.commit("new-key") {}
        router.backspace {}

        assertEquals("", assistant.text)
        assertEquals("new-ke", field.text)
    }
}
