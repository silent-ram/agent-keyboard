package com.agentkeyboard.ime

import com.agentkeyboard.ime.panel.EditorSelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSessionGuardTest {

    @Test
    fun editorGenerationChangesOnlyWhenTheInputSessionChanges() {
        val tracker = EditorSessionTracker()
        val first = EditorTargetIdentity("chat.example", 1, 1, null)
        val second = first.copy(fieldId = 2)

        assertEquals(1L, tracker.onStartInput(first, restarting = false))
        assertEquals(1L, tracker.onStartInput(first, restarting = true))
        assertEquals(2L, tracker.onStartInput(second, restarting = true))
        assertEquals(3L, tracker.onStartInput(second, restarting = false))
    }

    @Test
    fun insertionRequiresTheSameEditorGenerationAndSelection() {
        val requestedSelection = EditorSelectionSnapshot(4, 7)
        assertEquals(
            KnowledgeInsertGuard.Verdict.ALLOWED,
            KnowledgeInsertGuard.verify(
                Mode.NORMAL,
                requestedGeneration = 8,
                currentGeneration = 8,
                requestedSelection = requestedSelection,
                currentSelection = requestedSelection,
            ),
        )
        assertEquals(
            KnowledgeInsertGuard.Verdict.EDITOR_CHANGED,
            KnowledgeInsertGuard.verify(
                Mode.NORMAL,
                requestedGeneration = 8,
                currentGeneration = 9,
                requestedSelection = requestedSelection,
                currentSelection = requestedSelection,
            ),
        )
        assertEquals(
            KnowledgeInsertGuard.Verdict.SELECTION_CHANGED,
            KnowledgeInsertGuard.verify(
                Mode.NORMAL,
                requestedGeneration = 8,
                currentGeneration = 8,
                requestedSelection = requestedSelection,
                currentSelection = EditorSelectionSnapshot(4, 4),
            ),
        )
        assertEquals(
            KnowledgeInsertGuard.Verdict.SENSITIVE,
            KnowledgeInsertGuard.verify(
                Mode.PASSWORD,
                requestedGeneration = 8,
                currentGeneration = 8,
                requestedSelection = requestedSelection,
                currentSelection = requestedSelection,
            ),
        )
        assertEquals(
            KnowledgeInsertGuard.Verdict.EDITOR_CHANGED,
            KnowledgeInsertGuard.verify(
                Mode.NORMAL,
                requestedGeneration = null,
                currentGeneration = 8,
                requestedSelection = requestedSelection,
                currentSelection = requestedSelection,
            ),
        )
    }

    @Test
    fun requestPreparationStopsWhenPanelClosesEditorChangesOrFieldBecomesSensitive() {
        assertTrue(
            AgentRequestStartGuard.allows(
                panelVisible = true,
                editorBlocked = false,
                requestId = 2,
                currentRequestId = 2,
                targetGeneration = 8,
                currentGeneration = 8,
            ),
        )
        assertFalse(
            AgentRequestStartGuard.allows(
                panelVisible = false,
                editorBlocked = false,
                requestId = 2,
                currentRequestId = 2,
                targetGeneration = 8,
                currentGeneration = 8,
            ),
        )
        assertFalse(
            AgentRequestStartGuard.allows(
                panelVisible = true,
                editorBlocked = true,
                requestId = 2,
                currentRequestId = 2,
                targetGeneration = 8,
                currentGeneration = 8,
            ),
        )
        assertFalse(
            AgentRequestStartGuard.allows(
                panelVisible = true,
                editorBlocked = false,
                requestId = 2,
                currentRequestId = 3,
                targetGeneration = 8,
                currentGeneration = 8,
            ),
        )
        assertFalse(
            AgentRequestStartGuard.allows(
                panelVisible = true,
                editorBlocked = false,
                requestId = 2,
                currentRequestId = 2,
                targetGeneration = 8,
                currentGeneration = 9,
            ),
        )
    }
}
