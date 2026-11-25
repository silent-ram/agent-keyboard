package com.agentkeyboard

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.agent.AgentKit
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.Task
import com.agentkeyboard.agent.TaskType
import com.agentkeyboard.data.KeyboardHeight
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.MessageRoles
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.CitationJson
import com.agentkeyboard.tools.ToolIds
import com.agentkeyboard.ui.AppTheme
import com.agentkeyboard.ui.AssistantIcon
import com.agentkeyboard.ui.AssistantIconButton
import com.agentkeyboard.ui.AssistantPageBar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

private enum class KnowledgePage(val label: Int, val icon: Int) {
    CHAT(R.string.qa_tab_chat, R.drawable.assistant_chat),
    AGENT(R.string.qa_tab_agent, R.drawable.ic_baseline_tune_24),
    LIBRARY(R.string.qa_tab_library, R.drawable.ic_baseline_library_books_24),
    SETTINGS(R.string.qa_tab_settings, R.drawable.ic_baseline_keyboard_24),
    MODEL_MANAGE(R.string.qa_tab_agent, R.drawable.ic_baseline_tune_24),
}

private data class ChatLine(
    val role: String,
    val text: String,
    val citations: List<Citation> = emptyList(),
    val streaming: Boolean = false,
)

class DisclosurePromptHost {
    private val _request = kotlinx.coroutines.flow.MutableStateFlow<ConfirmationRequest?>(null)
    val request: kotlinx.coroutines.flow.StateFlow<ConfirmationRequest?> = _request
    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    suspend fun resolve(request: ConfirmationRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _request.value = request
        return try {
            deferred.await()
        } finally {
            decision = null
            _request.value = null
        }
    }

    fun answer(allowed: Boolean) {
        decision?.complete(allowed)
    }
}

@Composable
fun KnowledgeAssistantRoot(
    activity: ComponentActivity,
    agentKit: AgentKit,
    disclosureHost: DisclosurePromptHost,
    requestedOpen: String? = null,
) {
    val context = LocalContext.current
    val db = remember { AgentDatabase.get(context.applicationContext) }
    val modelStore = remember { ModelStore(context.applicationContext) }
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val knowledgeSettingsStore = remember { com.agentkeyboard.rag.ZhipuKnowledgeSettingsStore(context.applicationContext, modelStore) }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatLine>() }

    var page by remember { mutableStateOf(routeToPage(requestedOpen)) }
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    /** 对话管理两级:列表(false) / 具体对话视图(true) */
    var chatViewing by remember { mutableStateOf(false) }
    /** 模型管理的返回目标:从配置页或(旧路径)设置页进入,返回回原页 */
    var manageReturnPage by remember { mutableStateOf(KnowledgePage.AGENT) }
    var readyDocumentCount by remember { mutableStateOf<Int?>(null) }
    var knowledgeBaseConfigured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var submittedQuestion by remember { mutableStateOf<String?>(null) }
    var selectedCitation by remember { mutableStateOf<Citation?>(null) }
    val pendingDisclosure by disclosureHost.request.collectAsState()

    LaunchedEffect(requestedOpen) {
        page = routeToPage(requestedOpen)
    }

    LaunchedEffect(Unit) {
        val kb = runCatching { knowledgeSettingsStore.load() }.getOrNull()
        readyDocumentCount = kb?.documentCount
        knowledgeBaseConfigured = !kb?.knowledgeId.isNullOrBlank()
        // 2026-11-22 交互改版:不再自动进入最新会话——默认停在 Agent 配置页,
        // 对话管理 tab 提供列表/新建/进入(见 ConversationManagerScreen)
    }

    LaunchedEffect(agentKit) {
        agentKit.events.collect { event ->
            when (event) {
                is AgentEvent.Started -> {
                    submittedQuestion?.let { messages.add(ChatLine(MessageRoles.USER, it)) }
                    submittedQuestion = null
                    activeSessionId = event.sessionId
                    status = context.getString(R.string.qa_status_searching)
                }
                is AgentEvent.Thinking -> if (status == null) {
                    status = context.getString(R.string.qa_status_forming)
                }
                is AgentEvent.StepStarted -> {
                    if (event.call.toolId == ToolIds.KB_SEARCH) {
                        status = context.getString(R.string.qa_status_searching)
                    }
                }
                is AgentEvent.StepFinished -> {
                    if (event.call.toolId == ToolIds.KB_SEARCH) {
                        status = context.getString(R.string.qa_status_forming)
                    }
                }
                is AgentEvent.ContentDelta -> {
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) {
                        messages[messages.lastIndex] = last.copy(text = event.accumulated)
                    } else {
                        messages.add(ChatLine(MessageRoles.ASSISTANT, event.accumulated, streaming = true))
                    }
                }
                is AgentEvent.Finished -> {
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) messages.removeAt(messages.lastIndex)
                    messages.add(ChatLine(MessageRoles.ASSISTANT, event.content, event.citations))
                    activeSessionId = event.sessionId
                    busy = false
                    status = null
                }
                is AgentEvent.Incomplete -> {
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) messages.removeAt(messages.lastIndex)
                    val visibleContent = event.content.ifBlank {
                        if (event.citations.isNotEmpty()) "已获得部分资料，任务未完成。" else ""
                    }
                    if (visibleContent.isNotBlank()) {
                        messages.add(ChatLine(MessageRoles.ASSISTANT, visibleContent, event.citations))
                    }
                    messages.add(ChatLine(MessageRoles.SYSTEM, "未完成：${event.reason}"))
                    activeSessionId = event.sessionId
                    busy = false
                    status = null
                }
                is AgentEvent.Failed -> {
                    submittedQuestion = null
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) messages.removeAt(messages.lastIndex)
                    if (
                        event.error.kind in setOf("LLM_DENIED", "KNOWLEDGE_TOOLS_UNSUPPORTED") &&
                        messages.lastOrNull()?.role == MessageRoles.USER
                    ) {
                        messages.removeAt(messages.lastIndex)
                    }
                    messages.add(ChatLine(MessageRoles.SYSTEM, chatErrorText(context, event.error.kind)))
                    if (event.error.kind == "NOT_CONFIGURED") page = KnowledgePage.AGENT
                    busy = false
                    status = null
                }
                is AgentEvent.Stopped -> {
                    submittedQuestion = null
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) messages.removeAt(messages.lastIndex)
                    messages.add(ChatLine(MessageRoles.SYSTEM, context.getString(R.string.qa_status_stopped)))
                    busy = false
                    status = null
                }
                is AgentEvent.ContentReset -> {
                    // 草稿撤回(检查未通过 / 转入工具调用 / 重试):丢弃未审核的流式半截文字
                    val last = messages.lastOrNull()
                    if (last?.role == MessageRoles.ASSISTANT && last.streaming) messages.removeAt(messages.lastIndex)
                }
                is AgentEvent.ConfirmationRequired -> Unit
            }
        }
    }

    LaunchedEffect(page) {
        if (page == KnowledgePage.CHAT) {
            val kb = runCatching { knowledgeSettingsStore.load() }.getOrNull()
            readyDocumentCount = kb?.documentCount
            knowledgeBaseConfigured = !kb?.knowledgeId.isNullOrBlank()
        }
    }

    val sendQuestion: (String) -> Unit = { raw ->
        val question = raw.trim()
        if (question.isNotEmpty() && !busy) {
            busy = true
            scope.launch {
                val kb = runCatching { knowledgeSettingsStore.load() }.getOrNull()
                readyDocumentCount = kb?.documentCount
                knowledgeBaseConfigured = !kb?.knowledgeId.isNullOrBlank()
                val configured = runCatching {
                    modelStore.loadActiveModel() != null
                }.getOrDefault(false)
                if (!configured) {
                    page = KnowledgePage.AGENT
                    status = context.getString(R.string.qa_status_configure_model)
                    busy = false
                    return@launch
                }
                submittedQuestion = question
                input = ""
                status = context.getString(R.string.qa_status_preparing)
                agentKit.engine.start(
                    Task(
                        type = TaskType.KNOWLEDGE_ASK,
                        userText = question,
                        sessionId = activeSessionId,
                        kbEnabled = true,
                    )
                )
            }
        }
    }

    val inConversation = page == KnowledgePage.CHAT && chatViewing
    if (page == KnowledgePage.MODEL_MANAGE) {
        Box(
            Modifier.fillMaxSize().background(AppTheme.colors.surface)
                .statusBarsPadding().navigationBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                ModelManageScreen(
            repo = settingsRepository,
            store = modelStore,
                    onBack = { page = manageReturnPage },
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surface)
                .statusBarsPadding()
                .imePadding(),
        ) {
            AssistantPageBar(
                title = stringResource(if (inConversation) R.string.qa_conversation_title else page.label),
                backLabel = if (inConversation) stringResource(R.string.qa_tab_chat) else null,
                backEnabled = !busy,
                onBack = { chatViewing = false },
            ) {
                if (inConversation) {
                    AssistantIconButton(
                        icon = R.drawable.ic_baseline_plus_24,
                        description = stringResource(R.string.qa_new_conversation_desc),
                        onClick = {
                            if (!busy) {
                                activeSessionId = null
                                messages.clear()
                                input = ""   // 新对话不带草稿
                                status = null
                            }
                        },
                        enabled = !busy,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    when (page) {
                KnowledgePage.CHAT -> if (chatViewing) {
                    ChatScreen(
                        messages = messages,
                        input = input,
                        status = status,
                        busy = busy,
                        readyDocumentCount = readyDocumentCount,
                        knowledgeBaseConfigured = knowledgeBaseConfigured,
                        onInputChange = { input = it },
                        onSend = sendQuestion,
                        onStop = { agentKit.engine.cancel() },
                        onOpenLibrary = { page = KnowledgePage.LIBRARY },
                        onCitation = { selectedCitation = it },
                    )
                } else {
                    ConversationManagerScreen(
                        busy = busy,
                        onNew = {
                            if (!busy) {
                                activeSessionId = null
                                messages.clear()
                                input = ""   // 新对话不带草稿
                                status = null
                                chatViewing = true
                            }
                        },
                        onOpenSession = { id ->
                            if (!busy) {
                                scope.launch {
                                    activeSessionId = id
                                    messages.clear()
                                    val safety = AgentFactory.historySafety(context)
                                    val rows = db.messageDao().bySession(id)
                                    messages.addAll(loadChatLines(context, safety?.forDisplay(rows).orEmpty()))
                                    input = ""   // 历史会话不带上一话题草稿
                                    status = null
                                    chatViewing = true
                                }
                            }
                        },
                        onActivity = { id ->
                            activity.startActivity(
                                Intent(activity, RecentActivityActivity::class.java)
                                    .putExtra(RecentActivityActivity.EXTRA_SESSION_ID, id),
                            )
                        },
                    )
                }
                KnowledgePage.AGENT -> AgentConfigScreen(
                    store = modelStore,
                    settingsRepository = settingsRepository,
                    agentKit = agentKit,
                    onOpenManage = {
                        manageReturnPage = KnowledgePage.AGENT
                        page = KnowledgePage.MODEL_MANAGE
                    },
                )
                KnowledgePage.LIBRARY -> LibraryScreen()
                KnowledgePage.SETTINGS -> SettingsScreen(
                    settingsRepository = settingsRepository,
                    onOpenActivity = {
                        activity.startActivity(Intent(activity, RecentActivityActivity::class.java))
                    },
                )
                KnowledgePage.MODEL_MANAGE -> Unit
                    }
                }
            }
            if (inConversation) {
                Spacer(Modifier.fillMaxWidth().navigationBarsPadding())
            } else {
                HorizontalDivider(color = AppTheme.colors.lineGray)
                NavigationBar(containerColor = AppTheme.colors.surface, tonalElevation = 0.dp) {
                    KnowledgePage.entries.filterNot { it == KnowledgePage.MODEL_MANAGE }.forEach { target ->
                        val selected = page == target
                        NavigationBarItem(
                            selected = selected,
                            enabled = !busy || selected,
                            onClick = { if (!busy) page = target },
                            icon = { AssistantIcon(target.icon, tint = if (selected) PrimaryBlue else MutedText) },
                            label = { Text(stringResource(target.label), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = AppTheme.colors.chipBg),
                        )
                    }
                }
            }
        }
    }

    pendingDisclosure?.let { request ->
        AlertDialog(
            onDismissRequest = { disclosureHost.answer(false) },
            title = { Text(request.title) },
            text = {
                Text(request.body)
            },
            confirmButton = {
                TextButton(onClick = { disclosureHost.answer(true) }) {
                    Text(stringResource(R.string.qa_disclosure_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { disclosureHost.answer(false) }) {
                    Text(stringResource(R.string.qa_disclosure_cancel))
                }
            },
        )
    }

    selectedCitation?.let { citation ->
        AlertDialog(
            onDismissRequest = { selectedCitation = null },
            title = {
                Text(
                    citation.title.substringBefore(" §"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(citation.title.substringAfter(" §", ""), color = MutedText, fontSize = 13.sp)
                    Text(citation.snippet.ifBlank { stringResource(R.string.qa_citation_snippet_fallback) })
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCitation = null }) {
                    Text(stringResource(R.string.qa_close))
                }
            },
        )
    }

    BackHandler(enabled = page == KnowledgePage.LIBRARY || page == KnowledgePage.SETTINGS) {
        page = KnowledgePage.CHAT
    }
    BackHandler(enabled = inConversation) {
        if (!busy) chatViewing = false
    }
}

@Composable
private fun ChatScreen(
    messages: List<ChatLine>,
    input: String,
    status: String?,
    busy: Boolean,
    readyDocumentCount: Int?,
    knowledgeBaseConfigured: Boolean,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenLibrary: () -> Unit,
    onCitation: (Citation) -> Unit,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceAlt)
                .clickable(onClick = onOpenLibrary)
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistantIcon(R.drawable.ic_baseline_library_books_24, tint = PrimaryBlue)
            Text(stringResource(R.string.qa_scope_all_docs), color = BodyText, modifier = Modifier.weight(1f), fontSize = 14.sp)
            AssistantIcon(R.drawable.ic_baseline_keyboard_arrow_right_24)
        }
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            if (!knowledgeBaseConfigured || readyDocumentCount == 0) R.string.qa_empty_import else R.string.qa_empty_with_docs
                        ),
                        color = BodyText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    if (knowledgeBaseConfigured && readyDocumentCount != 0) {
                        TextButton(
                            onClick = { onSend(context.getString(R.string.qa_summarize_docs)) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.qa_summarize_docs))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(messages) { _, line ->
                    ChatLineView(line, onCitation)
                }
                if (busy && messages.none { it.streaming }) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(status.orEmpty(), color = MutedText, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (status != null && !busy) {
            Text(status, color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        HorizontalDivider(color = AppTheme.colors.lineGray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.qa_input_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank() && !busy) {
                        onSend(input)
                        focusManager.clearFocus()
                    }
                }),
            )
            AssistantIconButton(
                icon = if (busy) R.drawable.assistant_stop else R.drawable.ic_baseline_arrow_upward_24,
                description = stringResource(if (busy) R.string.qa_stop else R.string.qa_send),
                onClick = if (busy) onStop else {
                    { if (input.isNotBlank()) { onSend(input); focusManager.clearFocus() } }
                },
                enabled = busy || input.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                emphasized = !busy,
                danger = busy,
            )
        }
    }
}

@Composable
private fun ChatLineView(line: ChatLine, onCitation: (Citation) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    when (line.role) {
        MessageRoles.USER -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(modifier = Modifier.widthIn(max = 440.dp), color = AppTheme.colors.chipBg, shape = RoundedCornerShape(8.dp)) {
                Text(line.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = BodyText)
            }
        }
        MessageRoles.ASSISTANT -> Column(
            modifier = Modifier.fillMaxWidth(0.96f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 2026-11-22:回答正文可直接长按选择复制;下方另有显式「复制」按钮
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(line.text, color = BodyText, fontSize = 15.sp, lineHeight = 24.sp, textAlign = TextAlign.Start)
            }
            // 显式复制按钮:历史对话中的旧回答同样保留(与面板端一致)
            AssistantIconButton(
                R.drawable.ic_baseline_content_copy_24,
                stringResource(R.string.panel_copy),
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(line.text)) },
            )
            line.citations.forEachIndexed { index, citation ->
                HorizontalDivider(color = AppTheme.colors.lineGray)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCitation(citation) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${index + 1}", color = OkGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        Text(citation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BodyText, fontSize = 13.sp)
                        if (citation.snippet.isNotBlank()) {
                            Text(citation.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MutedText, fontSize = 12.sp)
                        }
                    }
                    AssistantIcon(R.drawable.ic_baseline_keyboard_arrow_right_24)
                }
            }
        }
        else -> Text(line.text, color = MutedText, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LibraryScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HostedKnowledgeSection()
    }
}

@Composable
private fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onOpenActivity: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var keyboardHeightScale by remember { mutableStateOf(KeyboardHeight.DEFAULT_SCALE) }
    var privacyNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsRepository) {
        keyboardHeightScale = settingsRepository.keyboardHeightScale()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 2026-11-22 改版:模型/MCP 属 Agent 配置(见「配置」tab),本页仅输入法相关设置
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_keyboard_section),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SectionText,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_keyboard_height),
                    color = BodyText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    keyboardHeightLabel(keyboardHeightScale),
                    color = MutedText,
                    fontSize = 13.sp,
                )
            }
            Slider(
                value = keyboardHeightScale,
                onValueChange = { keyboardHeightScale = KeyboardHeight.normalize(it) },
                onValueChangeFinished = {
                    val normalized = KeyboardHeight.normalize(keyboardHeightScale)
                    keyboardHeightScale = normalized
                    scope.launch { settingsRepository.setKeyboardHeightScale(normalized) }
                },
                valueRange = KeyboardHeight.MIN_SCALE..KeyboardHeight.MAX_SCALE,
                steps = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "键盘高度 ${keyboardHeightLabel(keyboardHeightScale)}"
                    },
            )
        }
        Column {
            Text(
                stringResource(R.string.qa_privacy_section),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SectionText,
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenActivity).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.qa_recent_activity), color = BodyText, modifier = Modifier.weight(1f))
                Text("›", color = MutedText, fontSize = 18.sp)
            }
        }
        Column {
            Text(
                stringResource(R.string.settings_project_about, stringResource(R.string.app_name), Const.maintainer),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SectionText,
            )
            val projectLinks = listOf(
                R.string.settings_project_source to Const.githubRepo,
                R.string.settings_project_feedback to Const.issuesUrl,
                R.string.privacy_policy to null,
            )
            projectLinks.forEach { (label, url) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        try {
                            if (url == null) privacyNotice = Const.readPrivacyNotice(context.assets)
                            else uriHandler.openUri(url)
                        } catch (_: IllegalArgumentException) {
                            android.widget.Toast.makeText(context, R.string.settings_project_link_error, android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: java.io.IOException) {
                            android.widget.Toast.makeText(context, R.string.settings_project_link_error, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(label), color = BodyText, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null,
                        tint = MutedText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    privacyNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { privacyNotice = null },
            title = { Text(stringResource(R.string.privacy_policy)) },
            text = { Text(notice, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { privacyNotice = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

private fun keyboardHeightLabel(scale: Float): String =
    when (KeyboardHeight.normalize(scale)) {
        KeyboardHeight.MIN_SCALE -> "紧凑"
        KeyboardHeight.MAX_SCALE -> "宽松"
        else -> "标准"
    }

private fun loadChatLines(context: Context, rows: List<AgentMessageEntity>): List<ChatLine> =
    rows.mapNotNull { row ->
        when (row.role) {
            MessageRoles.USER -> ChatLine(MessageRoles.USER, row.content)
            MessageRoles.ASSISTANT -> row.content.takeIf { it.isNotBlank() }?.let {
                ChatLine(MessageRoles.ASSISTANT, it, CitationJson.decode(row.citationsJson))
            }
            MessageRoles.SYSTEM -> row.content.takeIf { it.startsWith("已停止") }?.let {
                ChatLine(MessageRoles.SYSTEM, context.getString(R.string.qa_status_stopped))
            }
            else -> null
        }
    }

private fun chatErrorText(context: Context, kind: String): String =
    context.getString(
        when (kind) {
            "NOT_CONFIGURED" -> R.string.qa_error_not_configured
            "LLM_DENIED" -> R.string.qa_error_disclosure_denied
            "UNSAFE_INPUT" -> R.string.qa_unsafe_input
            "UNSAFE_OUTPUT" -> R.string.qa_unsafe_output
            "KNOWLEDGE_TOOLS_UNSUPPORTED" -> R.string.qa_error_knowledge_tools_unsupported
            "NO_NETWORK" -> R.string.qa_error_no_network
            "BAD_KEY" -> R.string.qa_error_bad_key
            "TIMEOUT" -> R.string.qa_error_timeout
            "RATE_LIMIT" -> R.string.qa_error_rate_limit
            "BALANCE" -> R.string.qa_error_balance
            else -> R.string.qa_error_generic
        }
    )

private fun routeToPage(route: String?): KnowledgePage = when (route) {
    MainActivity.OPEN_LIBRARY -> KnowledgePage.LIBRARY
    MainActivity.OPEN_SETTINGS -> KnowledgePage.SETTINGS
    MainActivity.OPEN_MANAGE -> KnowledgePage.MODEL_MANAGE
    // 2026-11-22 改版:默认入口为 Agent 完整配置界面(模型/MCP/Skills/Memory/user.md)
    else -> KnowledgePage.AGENT
}

/**
 * 对话管理(2026-11-22 改版,原"对话问答"):
 * 新建对话 / 历史会话列表(点击进入独立对话视图)/ 每个对话绑定独立 AI 活动记录。
 */
@Composable
private fun ConversationManagerScreen(
    busy: Boolean,
    onNew: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onActivity: (Long) -> Unit,
) {
    val context = LocalContext.current
    val db = remember { AgentDatabase.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    // 会话 id → (标题=首条用户提问, 状态, 时间):列表要能看出"这是哪个对话",
    // 只有类型标签没有实用价值;DB 读取在协程内完成,不阻塞主线程
    data class ConversationRow(val id: Long, val title: String, val status: String, val createdAt: Long)
    var rows by remember { mutableStateOf<List<ConversationRow>>(emptyList()) }

    fun refresh() {
        scope.launch {
            val list = db.sessionDao().recentAgentConversations(
                com.agentkeyboard.data.db.RetentionPolicy.MAX_SESSIONS,
            )
            val safety = AgentFactory.historySafety(context)
            rows = list.mapNotNull { s ->
                val title = db.messageDao().firstUserMessage(s.id)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                ConversationRow(s.id, safety?.redact(title)?.take(40) ?: "历史对话", s.status, s.createdAt)
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(onClick = onNew, enabled = !busy, shape = RoundedCornerShape(8.dp)) {
            AssistantIcon(R.drawable.ic_baseline_plus_24, tint = androidx.compose.material3.LocalContentColor.current)
            Text(stringResource(R.string.qa_new_conversation_desc), modifier = Modifier.padding(start = 8.dp))
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.qa_conversation_empty),
                color = MutedText,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            // weight(1f) 而非 fillMaxSize():Column 内 fillMaxSize 会撑破高度,把上方「＋新建对话」挤出可视区
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                rows.forEach { row ->
                    item(key = row.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onOpenSession(row.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.title,
                                    color = BodyText,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${sessionStatusLabel(row.status)} · ${formatSessionTime(row.createdAt)}",
                                    color = MutedText,
                                    fontSize = 12.sp,
                                )
                            }
                            Text(
                                "活动 ›",
                                color = PrimaryBlue,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { onActivity(row.id) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        HorizontalDivider(color = AppTheme.colors.lineGray)
                    }
                }
            }
        }
    }
}

private fun taskTypeLabel(taskType: String): String = when (taskType) {
    TaskType.KNOWLEDGE_ASK.name -> "知识问答"
    TaskType.ASK.name -> "自由问答"
    TaskType.VERIFY.name -> "查证"
    TaskType.HELP_WRITE.name -> "帮写"
    TaskType.REWRITE.name -> "改写"
    TaskType.TRANSLATE.name -> "翻译"
    else -> taskType
}

private fun sessionStatusLabel(status: String): String = when (status) {
    com.agentkeyboard.data.db.SessionStatus.DONE -> "已完成"
    com.agentkeyboard.data.db.SessionStatus.INCOMPLETE -> "未完成"
    com.agentkeyboard.data.db.SessionStatus.FAILED -> "失败"
    com.agentkeyboard.data.db.SessionStatus.STOPPED -> "已停止"
    else -> "进行中"
}

private fun formatSessionTime(createdAt: Long): String =
    runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt))
    }.getOrDefault("")
