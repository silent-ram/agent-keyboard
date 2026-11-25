package com.agentkeyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.agent.AgentKit
import com.agentkeyboard.agent.AgentProfileStore
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.agent.ProfileSaveResult
import com.agentkeyboard.agent.ProfileSuggestion
import com.agentkeyboard.agent.TaskType
import com.agentkeyboard.agent.knowledgeAskTask
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.model.ModelConfigLogic
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.ime.panel.ConfirmationDecision
import com.agentkeyboard.ime.panel.EditorSelectionSnapshot
import com.agentkeyboard.ime.panel.PanelMessage
import com.agentkeyboard.ime.panel.PanelReducer
import com.agentkeyboard.ime.panel.UndoAlwaysController
import com.agentkeyboard.ime.panel.sessionToListItem
import com.agentkeyboard.tools.ToolIds
import com.agentkeyboard.ui.formatAnswerText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import java.util.concurrent.atomic.AtomicLong

/** Hosts the existing knowledge chat UI inside the active Fcitx input view. */
internal class FcitxAgentPanelController(
    private val service: FcitxInputMethodService,
) {
    val state = PanelUiState().apply { imeInputMode = true }

    private val appContext = service.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = SettingsRepository(appContext)
    private val profileStore by lazy { AgentProfileStore(AgentDatabase.get(appContext), settings) }
    private val inputBuffer = PanelInputBuffer(
        onChanged = { state.input = it },
        onValueChanged = { state.inputFieldValue = it },
    )
    private val inactiveManageBuffer = PanelInputBuffer { }
    val inputRouter = ImeTextInputRouter(inputBuffer)
    private val undoAlways = UndoAlwaysController()
    private val elapsedStartedAt = AtomicLong(0L)

    private var agentKit: AgentKit? = null
    private var eventJob: Job? = null
    private var requestPreparationJob: Job? = null
    private var historyOpenJob: Job? = null
    private var requestToken = 0L
    private var lastEditorGeneration: Long? = null
    private var visible = false
    private var activeSessionId: Long? = null
    private var submittedQuestion: String? = null
    private var manageEditorBuffer: PanelInputBuffer? = null
    private var profileEditorBuffer: PanelInputBuffer? = null
    private var activeDecision: CompletableDeferred<ConfirmationDecision>? = null
    private var requestGeneration: Long? = null
    private var requestSelection: EditorSelectionSnapshot? = null
    private var visibilityListener: ((Boolean) -> Unit)? = null

    val isVisible: Boolean get() = visible

    private val panelResolver = ConfirmationResolver { request ->
        state.confirmation = request
        val deferred = CompletableDeferred<ConfirmationDecision>()
        activeDecision = deferred
        val decision = try {
            deferred.await()
        } finally {
            if (activeDecision === deferred) activeDecision = null
            state.confirmation = null
        }
        if (decision.allowed && decision.rememberAlways) {
            agentKit?.gate?.setAlwaysAllow(request.toolId)
            undoAlways.mark(request.toolId)
            state.undoAlwaysToolId = request.toolId
        }
        decision.allowed
    }

    fun createView(context: Context): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(service)
        setViewTreeViewModelStoreOwner(service)
        setViewTreeSavedStateRegistryOwner(service)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            FcitxPanelTheme { PanelContent(state, panelCallbacks, 0.dp) }
        }
    }

    fun setVisibilityListener(listener: (Boolean) -> Unit) {
        visibilityListener = listener
        listener(visible)
    }

    fun toggle() {
        if (visible) close() else open()
    }

    fun open() {
        if (service.isAgentPanelBlocked()) return
        // 输入法服务 directBootAware:开机未解锁时也会运行,但 agent.db / DataStore / Keystore 密文都在
        // 凭据加密存储中,此时读取会抛异常。未解锁一律不打开面板,解锁后再用。
        if (isUserLocked()) {
            android.widget.Toast.makeText(appContext, R.string.agent_panel_locked, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        visible = true
        state.imeInputMode = true
        inputBuffer.setText(state.input)
        inputRouter.panelBuffer = inputBuffer
        inputRouter.target = ImeTextInputRouter.Target.ASSISTANT
        // P1-2 单槽恢复:确认条在面板不可见期间出现时,重开面板可恢复(与服务版 showPanel 同口径)
        if (state.confirmation == null) {
            state.confirmation = agentKit?.pendingConfirmation?.value
        }
        visibilityListener?.invoke(true)
        refreshPanelConfig()
    }

    private fun isUserLocked(): Boolean =
        appContext.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false

    fun close(clearDraft: Boolean = false) {
        historyOpenJob?.cancel()
        state.profileSuggestion = null
        closeProfile()
        if (clearDraft) {
            state.input = ""
            inputBuffer.clear()
        } else {
            inputBuffer.setText(state.input)
        }
        inputRouter.target = ImeTextInputRouter.Target.HOST
        state.imeInputMode = false
        if (!visible) return
        visible = false
        visibilityListener?.invoke(false)
    }

    fun onEditorChanged(generation: Long, sensitive: Boolean) {
        val sessionChanged = lastEditorGeneration != null && lastEditorGeneration != generation
        lastEditorGeneration = generation
        if (sensitive) {
            close(clearDraft = true)
            cancelRequestPreparation()
            agentKit?.engine?.cancel()
            requestGeneration = null
            requestSelection = null
            return
        }
        if (sessionChanged) {
            close(clearDraft = true)
            cancelRequestPreparation()
            agentKit?.engine?.cancel()
            requestGeneration = null
            requestSelection = null
        }
    }

    private fun cancelRequestPreparation() {
        requestToken += 1
        val pending = requestPreparationJob
        requestPreparationJob = null
        if (pending != null) {
            pending.cancel()
            state.busy = false
        }
    }

    fun onPreedit(text: String) = inputRouter.panelBuffer.updatePreedit(text)

    fun onCommit(text: String) = inputRouter.panelBuffer.commit(text)

    fun onBackspace() = inputRouter.panelBuffer.backspace()

    fun onDeleteSurrounding(before: Int, after: Int) = inputRouter.panelBuffer.deleteSurrounding(before, after)

    fun onHardwareKey(keyCode: Int, unicode: Int, keyDown: Boolean) {
        if (!keyDown) return
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> onBackspace()
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> insertLineBreak()
            else -> if (unicode > 0) onCommit(String(Character.toChars(unicode)))
        }
    }

    fun insertLineBreak() {
        if (visible && state.profileOpen && !service.isAgentPanelBlocked()) {
            profileEditorBuffer?.insertLineBreak()
            return
        }
        if (!visible || !state.canUseChatComposer || service.isAgentPanelBlocked()) return
        inputBuffer.insertLineBreak()
    }

    private fun stopCurrentRequest() {
        cancelRequestPreparation()
        agentKit?.engine?.cancel()
    }

    fun closeRuntime() {
        activeDecision?.cancel()
        agentKit?.engine?.cancel()
        scope.cancel()
    }

    private fun ensureAgentKit(): AgentKit {
        agentKit?.let { return it }
        val created = AgentFactory.createKnowledgeAssistant(appContext, panelResolver)
        agentKit = created
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.events.collect(::onAgentEvent)
        }
        return created
    }

    private fun refreshPanelConfig() {
        state.configured = null
        state.modelLabel = null
        scope.launch {
            val store = ModelStore(appContext)
            val file = withContext(Dispatchers.IO) { store.load() }
            val active = ModelConfigLogic.resolveDefaultModel(file)
            state.chipLabel = ModelConfigLogic.chipLabel(active) ?: "未连接"
            state.modelLabel = active?.display()
            state.fcDegraded = active?.effectiveFc() == false
            state.configured = active != null
            state.kbEnabled = true
            if (state.chipSheetOpen) loadChipData(store, file)
        }
    }

    private fun sendToAgent(rawText: String) {
        if (!visible || !state.canUseChatComposer || service.isAgentPanelBlocked()) return
        val text = rawText.trim()
        if (text.isEmpty() || state.busy) return
        if (state.savingProfileSuggestion) return
        if (ProfileSuggestion.hasRememberCommand(text)) {
            val memory = ProfileSuggestion.explicitMemory(text)
            if (memory == null) {
                showProfileResult(ProfileSaveResult.INVALID)
                return
            }
            state.profileSuggestion = null
            saveProfileSuggestion(memory, explicitDraft = rawText)
            return
        }
        state.profileSuggestion = null
        val targetGeneration = service.currentAgentEditorGeneration
        val selection = service.currentInputSelection
        requestGeneration = targetGeneration
        requestSelection = EditorSelectionSnapshot(selection.start, selection.end)
        state.busy = true

        val token = ++requestToken
        val preparation = scope.launch(start = CoroutineStart.LAZY) {
            var handedToEngine = false
            try {
                val configured = withContext(Dispatchers.IO) {
                    ModelStore(appContext).loadActiveModel() != null
                }
                if (!isCurrentRequest(token, targetGeneration)) return@launch
                if (!configured) {
                    state.configured = false
                    return@launch
                }
                if (state.input == rawText) inputBuffer.clear()
                state.replaceUndo = null
                state.replaceUndoHintExpired = false
                submittedQuestion = text
                elapsedStartedAt.set(android.os.SystemClock.elapsedRealtime())
                handedToEngine = true
                requestPreparationJob = null
                ensureAgentKit().engine.start(
                    knowledgeAskTask(text, activeSessionId, allowProfileSuggestion = true),
                )
            } finally {
                if (requestToken == token) {
                    requestPreparationJob = null
                    if (!handedToEngine) state.busy = false
                }
            }
        }
        requestPreparationJob = preparation
        preparation.start()
    }

    private fun isCurrentRequest(token: Long, targetGeneration: Long): Boolean =
        AgentRequestStartGuard.allows(
            panelVisible = visible && state.canUseChatComposer,
            editorBlocked = service.isAgentPanelBlocked(),
            requestId = token,
            currentRequestId = requestToken,
            targetGeneration = targetGeneration,
            currentGeneration = service.currentAgentEditorGeneration,
        )

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ContentDelta -> {
                // 草稿上屏:只影响面板展示,不进入状态判定,完成/未完成事件仍以审核后的文本为准
                replaceMessages(PanelReducer.apply(state.messages.toList(), event, state.modelLabel, null))
            }
            is AgentEvent.ContentReset -> {
                replaceMessages(PanelReducer.apply(state.messages.toList(), event, state.modelLabel, null))
                // 重试会重新计时:撤回草稿同时重置起点,避免把失败尝试的耗时计入最终结果
                elapsedStartedAt.set(android.os.SystemClock.elapsedRealtime())
            }
            is AgentEvent.Started -> {
                submittedQuestion?.let { state.messages.add(PanelMessage.UserText(it)) }
                submittedQuestion = null
                activeSessionId = event.sessionId
            }
            is AgentEvent.Finished -> {
                val elapsed = ((android.os.SystemClock.elapsedRealtime() - elapsedStartedAt.get()) / 1000)
                    .takeIf { it >= 1 }
                replaceMessages(
                    PanelReducer.onFinished(
                        state.messages.toList(),
                        event.content,
                        event.citations,
                        state.modelLabel,
                        elapsed,
                        editorGeneration = requestGeneration,
                        editorSelection = requestSelection,
                        summary = event.summary,
                    ),
                )
                activeSessionId = event.sessionId
                state.busy = false
                if (visible && requestGeneration == service.currentAgentEditorGeneration && !service.isAgentPanelBlocked()) {
                    state.profileSuggestion = event.profileSuggestion
                }
            }
            is AgentEvent.Incomplete -> {
                replaceMessages(
                    PanelReducer.onIncomplete(
                        state.messages.toList(), event.content, event.citations, state.modelLabel, null, event.reason,
                        editorGeneration = requestGeneration,
                        editorSelection = requestSelection,
                        summary = event.summary,
                    ),
                )
                activeSessionId = event.sessionId
                state.busy = false
            }
            is AgentEvent.Failed -> {
                submittedQuestion = null
                replaceMessages(PanelReducer.apply(state.messages.toList(), event, state.modelLabel, null))
                state.busy = false
            }
            is AgentEvent.Stopped -> {
                submittedQuestion = null
                state.confirmation = null
                activeDecision?.cancel()
                activeDecision = null
                replaceMessages(PanelReducer.apply(state.messages.toList(), event, state.modelLabel, null))
                state.busy = false
            }
            else -> replaceMessages(PanelReducer.apply(state.messages.toList(), event, state.modelLabel, null))
        }
    }

    private fun replaceMessages(messages: List<PanelMessage>) {
        state.messages.clear()
        state.messages.addAll(messages)
    }

    private fun insertionVerdict(message: PanelMessage.Result): KnowledgeInsertGuard.Verdict {
        if (service.isAgentPanelBlocked()) return KnowledgeInsertGuard.Verdict.SENSITIVE
        val currentSelection = service.currentInputSelection
        return KnowledgeInsertGuard.verify(
            mode = ImeContextProvider.detect(service.currentInputEditorInfo),
            requestedGeneration = message.editorGeneration,
            currentGeneration = service.currentAgentEditorGeneration,
            requestedSelection = message.editorSelection,
            currentSelection = EditorSelectionSnapshot(currentSelection.start, currentSelection.end),
        )
    }

    private fun openModelManage() {
        state.modelManageOpen = true
        state.chipSheetOpen = false
        manageEditorBuffer = null
        inputRouter.panelBuffer = inactiveManageBuffer
    }

    private fun closeModelManage() {
        state.modelManageOpen = false
        manageEditorBuffer = null
        inputRouter.panelBuffer = inputBuffer
        inputBuffer.setText(state.input)
        refreshPanelConfig()
    }

    private fun openProfile() {
        if (!visible || state.busy || service.isAgentPanelBlocked()) return
        state.chipSheetOpen = false
        state.profileOpen = true
        inputRouter.panelBuffer = inactiveManageBuffer
    }

    private fun closeProfile() {
        if (!state.profileOpen) return
        state.profileOpen = false
        profileEditorBuffer = null
        state.profileEditValue = null
        inputRouter.panelBuffer = inputBuffer
    }

    private fun showProfileResult(result: ProfileSaveResult) {
        val label = when (result) {
            ProfileSaveResult.SAVED -> R.string.panel_profile_saved
            ProfileSaveResult.ALREADY_PRESENT -> R.string.panel_profile_duplicate
            ProfileSaveResult.INVALID -> R.string.panel_profile_invalid
        }
        Toast.makeText(service, label, Toast.LENGTH_SHORT).show()
    }

    private fun saveProfileSuggestion(suggestion: ProfileSuggestion, explicitDraft: String? = null) {
        if (state.savingProfileSuggestion) return
        state.savingProfileSuggestion = true
        scope.launch {
            try {
                val safety = AgentFactory.historySafety(appContext)
                val result = if (safety == null || safety.hasConfig(suggestion.text)) ProfileSaveResult.INVALID
                    else profileStore.accept(suggestion)
                if (result != ProfileSaveResult.INVALID) {
                    if (state.profileSuggestion === suggestion) state.profileSuggestion = null
                    if (explicitDraft != null && visible && state.input == explicitDraft) inputBuffer.clear()
                }
                showProfileResult(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(service, R.string.panel_profile_failed, Toast.LENGTH_SHORT).show()
            } finally {
                state.savingProfileSuggestion = false
            }
        }
    }

    private fun bindModelManageInput(initial: String, onChanged: (String) -> Unit) {
        val buffer = PanelInputBuffer(onChanged)
        manageEditorBuffer = buffer
        inputRouter.panelBuffer = buffer
        buffer.setText(initial)
    }

    private fun startNewConversation() {
        if (state.busy) return
        historyOpenJob?.cancel()
        state.profileSuggestion = null
        activeSessionId = null
        state.messages.clear()
        state.input = ""
        inputBuffer.clear()
        state.drawerTab = null
        state.replaceUndo = null
        state.replaceUndoHintExpired = false
    }

    private fun loadChipData(store: ModelStore, file: com.agentkeyboard.data.model.ModelConfigFile) {
        val providerName = { id: String -> file.providers.firstOrNull { it.id == id }?.name ?: "已删除" }
        val items = file.models.filter { !it.offlineFlag }.map { model ->
            com.agentkeyboard.ime.ChipItem(
                model.ref,
                ModelConfigLogic.chipLabel(model) ?: model.display(),
                providerName(model.providerId),
            )
        }
        state.chipDefaultRef = file.defaultModelRef.ifBlank { null }
        state.chipRecent = file.models.filter { it.lastUsedAt > 0 }
            .sortedByDescending { it.lastUsedAt }
            .take(3)
            .map { model ->
                com.agentkeyboard.ime.ChipItem(
                    model.ref,
                    ModelConfigLogic.chipLabel(model) ?: model.display(),
                    providerName(model.providerId),
                )
            }
        state.chipGroups = file.providers.map { provider ->
            com.agentkeyboard.ime.ChipGroup(
                providerName = provider.name,
                items = items.filter { it.ref.startsWith("${provider.id}/") },
            )
        }
    }

    private val panelCallbacks = object : PanelCallbacks {
        override fun onClosePanel() = close()

        override fun onSend(text: String) = sendToAgent(text)

        override fun onInputValueChanged(value: androidx.compose.ui.text.input.TextFieldValue) =
            inputBuffer.setValue(value)

        override fun onNewConversation() = startNewConversation()

        override fun onInsert(message: PanelMessage.Result) {
            if (!visible || !message.canInsert) return
            val blockedMessage = when (insertionVerdict(message)) {
                KnowledgeInsertGuard.Verdict.ALLOWED -> null
                KnowledgeInsertGuard.Verdict.SENSITIVE -> R.string.panel_insert_sensitive
                KnowledgeInsertGuard.Verdict.EDITOR_CHANGED -> R.string.quick_insert_dropped
                KnowledgeInsertGuard.Verdict.SELECTION_CHANGED -> R.string.panel_insert_cursor_changed
            }
            if (blockedMessage != null) {
                Toast.makeText(service, blockedMessage, Toast.LENGTH_SHORT).show()
                return
            }
            val text = formatAnswerText(message.text).text
            if (service.currentInputConnection?.commitText(text, 1) != true) {
                Toast.makeText(service, R.string.panel_insert_editor_rejected, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(service, R.string.panel_inserted_toast, Toast.LENGTH_SHORT).show()
            close()
        }

        override fun onCopy(text: String) {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("agent_keyboard", text))
            Toast.makeText(service, R.string.panel_copied_toast, Toast.LENGTH_SHORT).show()
        }

        override fun onOpenSettings() = openModelManage()

        override fun onOpenProfile() = openProfile()

        override fun onCloseProfile() = closeProfile()

        override fun onStartProfileEdit(text: String) {
            if (!state.profileOpen || !visible || service.isAgentPanelBlocked()) return
            val buffer = PanelInputBuffer(onChanged = {}, onValueChanged = { state.profileEditValue = it })
            profileEditorBuffer = buffer
            inputRouter.panelBuffer = buffer
            buffer.setText(text)
        }

        override fun onStopProfileEdit() {
            profileEditorBuffer = null
            state.profileEditValue = null
            if (state.profileOpen) inputRouter.panelBuffer = inactiveManageBuffer
        }

        override fun onProfileInputChanged(value: androidx.compose.ui.text.input.TextFieldValue) {
            profileEditorBuffer?.setValue(value)
        }

        override fun onProfileSuggestionDecision(suggestion: ProfileSuggestion, save: Boolean) {
            if (!visible || state.busy || service.isAgentPanelBlocked() || state.profileSuggestion !== suggestion) return
            if (save) saveProfileSuggestion(suggestion) else state.profileSuggestion = null
        }

        override fun onDismissCard(message: PanelMessage.Result) {
            state.messages.removeAll { it === message }
        }

        override fun onStop() {
            stopCurrentRequest()
        }

        override fun onDecision(request: ConfirmationRequest, decision: ConfirmationDecision) {
            if (state.confirmation === request) activeDecision?.complete(decision)
        }

        override fun onUndoAlways(toolId: String) {
            scope.launch {
                agentKit?.gate?.reset(toolId)
                undoAlways.clear()
                state.undoAlwaysToolId = null
            }
        }

        override fun onUndoExpire() {
            undoAlways.clear()
            state.undoAlwaysToolId = null
        }

        override fun onOpenHistory() {
            if (state.drawerTab == null) {
                state.drawerTab = 0
                onLoadDrawerData()
            } else {
                onCloseDrawer()
            }
        }

        override fun onCloseDrawer() {
            historyOpenJob?.cancel()
            state.drawerTab = null
        }

        override fun onDrawerTab(tab: Int) {
            historyOpenJob?.cancel()
            state.drawerTab = tab
            onLoadDrawerData()
        }

        override fun onLoadDrawerData() {
            scope.launch {
                val db = AgentDatabase.get(appContext)
                val safety = AgentFactory.historySafety(appContext)
                state.sessions = db.sessionDao().recentByTaskTypeWithMessages(TaskType.KNOWLEDGE_ASK.name, 50).map { session ->
                    val messages = db.messageDao().bySession(session.id)
                    val firstUser = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
                    sessionToListItem(session, PanelReducer.sessionTitle(safety?.redact(firstUser) ?: "历史对话"))
                }
                state.activity = db.activityLogDao().recent(200).first()
            }
        }

        override fun onOpenSession(sessionId: Long) {
            if (state.busy || !visible || state.drawerTab != 0) return
            historyOpenJob?.cancel()
            val generation = service.currentAgentEditorGeneration
            historyOpenJob = scope.launch {
                val safety = AgentFactory.historySafety(appContext)
                val stored = AgentDatabase.get(appContext).messageDao().bySession(sessionId)
                val rows = safety?.forDisplay(stored).orEmpty()
                if (!visible || state.busy || state.drawerTab != 0 ||
                    service.currentAgentEditorGeneration != generation || service.isAgentPanelBlocked()
                ) return@launch
                if (activeSessionId != sessionId) inputBuffer.clear()
                state.profileSuggestion = null
                replaceMessages(PanelReducer.sessionToPanelMessages(rows, state.modelLabel))
                activeSessionId = sessionId
                state.drawerTab = null
            }
        }

        override fun onDeleteSession(sessionId: Long) {
            if (state.busy) return
            scope.launch {
                AgentDatabase.get(appContext).sessionDao().delete(sessionId)
                if (activeSessionId == sessionId) {
                    state.profileSuggestion = null
                    activeSessionId = null
                    state.messages.clear()
                }
                onLoadDrawerData()
            }
        }

        override fun onToggleChipSheet() {
            state.chipSheetOpen = !state.chipSheetOpen
            if (state.chipSheetOpen) {
                scope.launch {
                    val store = ModelStore(appContext)
                    loadChipData(store, withContext(Dispatchers.IO) { store.load() })
                }
            }
        }

        override fun onSelectModel(ref: String) {
            scope.launch {
                val store = ModelStore(appContext)
                val file = withContext(Dispatchers.IO) { store.load() }
                val model = file.models.firstOrNull { it.ref == ref } ?: return@launch
                withContext(Dispatchers.IO) {
                    store.setDefaultModel(ref)
                    store.markUsed(ref)
                }
                state.chipDefaultRef = ref
                state.chipLabel = ModelConfigLogic.chipLabel(model)
                state.modelLabel = model.display()
                state.fcDegraded = model.effectiveFc() == false
                state.chipSheetOpen = false
            }
        }

        override fun onOpenModelManage() = openModelManage()

        override fun onCloseModelManage() = closeModelManage()

        override fun onBindModelManageInput(initial: String, onChanged: (String) -> Unit) =
            bindModelManageInput(initial, onChanged)

        override fun onReplace(message: PanelMessage.Result) = onInsert(message)

        override fun onRestoreReplace() = Unit

    }
}
