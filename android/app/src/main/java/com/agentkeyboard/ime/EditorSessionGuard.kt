package com.agentkeyboard.ime

import com.agentkeyboard.ime.panel.EditorSelectionSnapshot

/** Non-content identity for the editor that requested a knowledge answer. */
internal data class EditorTargetIdentity(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val privateImeOptions: String?,
)

internal class EditorSessionTracker {
    var generation: Long = 0L
        private set

    private var target: EditorTargetIdentity? = null

    fun onStartInput(next: EditorTargetIdentity?, restarting: Boolean): Long {
        if (!restarting || next != target) generation += 1
        target = next
        return generation
    }
}

internal object KnowledgeInsertGuard {
    enum class Verdict { ALLOWED, SENSITIVE, EDITOR_CHANGED, SELECTION_CHANGED }

    fun verify(
        mode: Mode,
        requestedGeneration: Long?,
        currentGeneration: Long,
        requestedSelection: EditorSelectionSnapshot?,
        currentSelection: EditorSelectionSnapshot?,
    ): Verdict = when {
        mode == Mode.PASSWORD -> Verdict.SENSITIVE
        requestedGeneration == null || requestedGeneration != currentGeneration -> Verdict.EDITOR_CHANGED
        requestedSelection == null || requestedSelection != currentSelection -> Verdict.SELECTION_CHANGED
        else -> Verdict.ALLOWED
    }
}

internal object AgentRequestStartGuard {
    fun allows(
        panelVisible: Boolean,
        editorBlocked: Boolean,
        requestId: Long,
        currentRequestId: Long,
        targetGeneration: Long,
        currentGeneration: Long,
    ): Boolean =
        panelVisible &&
            !editorBlocked &&
            requestId == currentRequestId &&
            targetGeneration == currentGeneration
}
