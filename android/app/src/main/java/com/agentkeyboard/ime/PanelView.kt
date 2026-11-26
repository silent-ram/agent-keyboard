package com.agentkeyboard.ime

import org.fcitx.fcitx5.android.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ProfileSuggestion
import com.agentkeyboard.ime.panel.ActivityEntryRow
import com.agentkeyboard.ime.panel.ConfirmationBar
import com.agentkeyboard.ime.panel.ConfirmationDecision
import com.agentkeyboard.ime.panel.HistoryDrawer
import com.agentkeyboard.ime.panel.PanelMessage
import com.agentkeyboard.ime.panel.PanelReducer
import com.agentkeyboard.ime.panel.RunPhase
import com.agentkeyboard.ime.panel.SessionListItem
import com.agentkeyboard.ime.panel.ToolCardState
import com.agentkeyboard.tools.InjectionScanner
import com.agentkeyboard.ModelManageScreen
import com.agentkeyboard.ImePanelTextFieldBinder
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.ui.AssistantIcon
import com.agentkeyboard.ui.AssistantIconButton
import com.agentkeyboard.ui.AssistantTheme
import com.agentkeyboard.ui.formatAnswerText
import kotlinx.coroutines.awaitCancellation

// ==================== 视觉常量(M3-C audit#7:抽为 AppTheme light/dark 双套) ====================
private val PrimaryBlue @Composable get() = AppTheme.componentColors.primary
private val ChipBg @Composable get() = AppTheme.componentColors.chipBg
private val ChipText @Composable get() = AppTheme.componentColors.chipText
private val CardBorder @Composable get() = AppTheme.componentColors.cardBorder
private val ErrorBg @Composable get() = AppTheme.componentColors.errorBg
private val ErrorBorder @Composable get() = AppTheme.componentColors.errorBorder
private val ErrorText @Composable get() = AppTheme.componentColors.errorText
private val BodyText @Composable get() = AppTheme.componentColors.bodyText
private val MutedText @Composable get() = AppTheme.componentColors.mutedText
private val InputBg @Composable get() = AppTheme.componentColors.inputBg
private val WarnBg @Composable get() = AppTheme.componentColors.warnBg
private val WarnBorder @Composable get() = AppTheme.componentColors.warnBorder
private val WarnText @Composable get() = AppTheme.componentColors.warnText
private val SystemBg @Composable get() = AppTheme.componentColors.systemBg
private val OkGreen @Composable get() = AppTheme.componentColors.okGreen
private val OkBg @Composable get() = AppTheme.componentColors.okBg
private val Surface @Composable get() = AppTheme.componentColors.surface
private val LineGray @Composable get() = AppTheme.componentColors.lineGray
private val ChipBorder @Composable get() = AppTheme.componentColors.chipBorder
private val OnPrimary @Composable get() = AppTheme.componentColors.onPrimary
private typealias AppTheme = com.agentkeyboard.ui.AppTheme

/** 面板 UI 状态(服务持有,生命周期跨面板开关保留;Agent 驱动) */
class PanelUiState {
    /** null=配置读取中,true=已配置,false=S9 未配置 */
    var configured by mutableStateOf<Boolean?>(null)
    /** 当前配置对应的模型显示名(pickModel 推断),未配置为 null */
    var modelLabel by mutableStateOf<String?>(null)
    /** 运行条/停止按钮显示 */
    var busy by mutableStateOf(false)
    var input by mutableStateOf("")
    var inputFieldValue by mutableStateOf(TextFieldValue())
    val messages = mutableStateListOf<PanelMessage>()

    /** 待决确认条(单槽区最高优先级;由 resolver 设置,用户决定后清空) */
    var confirmation by mutableStateOf<ConfirmationRequest?>(null)

    /** 「总是允许」撤销条(P1-1 状态提升):点「总是允许」后 5s 内可撤销,面板重开不丢 */
    var undoAlwaysToolId by mutableStateOf<String?>(null)

    /** 🕘 弹层:null=关闭;0=会话;1=最近活动 */
    var drawerTab by mutableStateOf<Int?>(null)
    var sessions by mutableStateOf<List<SessionListItem>>(emptyList())
    var activity by mutableStateOf<List<ActivityLogEntity>>(emptyList())

    var kbEnabled by mutableStateOf(true)
    /** Fcitx owns prompt keystrokes while the panel is open. */
    var imeInputMode by mutableStateOf(false)

    // ==== 模型 chip(P0/P1,10 号设计 §5)====
    var chipLabel by mutableStateOf<String?>(null)
    /** chip 弹层打开 */
    var chipSheetOpen by mutableStateOf(false)
    var modelManageOpen by mutableStateOf(false)
    internal var profileOpen by mutableStateOf(false)
    internal var profileEditValue by mutableStateOf<TextFieldValue?>(null)
    internal var profileSuggestion by mutableStateOf<ProfileSuggestion?>(null)
    internal var savingProfileSuggestion by mutableStateOf(false)
    internal val canUseChatComposer: Boolean
        get() = configured == true && confirmation == null && !modelManageOpen && !profileOpen && !chipSheetOpen && drawerTab == null
    /** 弹层数据:默认项 + 最近使用 + 按服务商分组 */
    var chipDefaultRef by mutableStateOf<String?>(null)
    var chipRecent by mutableStateOf<List<ChipItem>>(emptyList())
    var chipGroups by mutableStateOf<List<ChipGroup>>(emptyList())
    /** fc=NO 单轮降级顶部提示条(§4.2) */
    var fcDegraded by mutableStateOf(false)

    /**
     * M3 替换恢复(12 §5 应用内单步恢复):替换成功后内存暂存原文,
     * 提示条显示 8s([hintExpired]=提示条是否已过展示期,卡上「恢复原文」入口保留)。
     * 仅最近一次,新任务发起时清空。
     */
    var replaceUndo by mutableStateOf<ReplaceUndoState?>(null)
    var replaceUndoHintExpired by mutableStateOf(false)
}

/** 替换恢复暂存(仅内存,不落盘;仅最近一次) */
data class ReplaceUndoState(
    val oldText: String,
    val newText: String,
    val createdAtMs: Long = 0L,
)

/** chip 弹层条目(模型 + 所属服务商名消歧) */
data class ChipItem(val ref: String, val label: String, val providerName: String)

/** 按服务商分组(组头 = Provider name) */
data class ChipGroup(val providerName: String, val items: List<ChipItem>)

/** 面板对宿主服务的回调 */
interface PanelCallbacks {
    fun onClosePanel()
    fun onSend(text: String)
    fun onInputValueChanged(value: TextFieldValue) {}
    fun onNewConversation() {}
    fun onInsert(message: PanelMessage.Result)
    fun onCopy(text: String)
    fun onOpenSettings()
    fun onDismissCard(message: PanelMessage.Result)
    /** 错误卡「重试」:用同一个问题再发一次 */
    fun onRetry() {}
    /** 错误卡「复制诊断信息」:只有分类、状态码与计数,可直接贴进问题反馈 */
    fun onCopyDiagnostics(text: String) {}
    /** ■ 停止 = engine.cancel()(确认挂起中点停止即拒绝) */
    fun onStop()
    /** 确认条用户决定:服务侧组合「先 setAlwaysAllow 再 resolve(true)」契约 */
    fun onDecision(request: ConfirmationRequest, decision: ConfirmationDecision)
    /** 「总是允许」5s 内撤销 = gate.reset(toolId) */
    fun onUndoAlways(toolId: String)
    /** 撤销条 5s 到期(仅清状态,不动授权) */
    fun onUndoExpire()
    /** 🕘 弹层 */
    fun onOpenHistory()
    fun onCloseDrawer()
    fun onDrawerTab(tab: Int)
    fun onLoadDrawerData()
    fun onOpenSession(sessionId: Long)
    fun onDeleteSession(sessionId: Long)
    /** P1 chip 弹层开关 */
    fun onToggleChipSheet()
    /** 选择模型:仅影响下一次请求(§4.3);UNKNOWN 模型触发后台懒探测 */
    fun onSelectModel(ref: String)
    /** 弹层底部「管理模型 →」 */
    fun onOpenModelManage()
    fun onCloseModelManage() {}
    fun onBindModelManageInput(initial: String, onChanged: (String) -> Unit) {}
    fun onOpenProfile() {}
    fun onCloseProfile() {}
    fun onStartProfileEdit(text: String) {}
    fun onStopProfileEdit() {}
    fun onProfileInputChanged(value: TextFieldValue) {}
    fun onProfileSuggestionDecision(suggestion: ProfileSuggestion, save: Boolean) {}

    // ==== M3 快捷动作 / 替换语义 / S10 空库卡 ====
    /** 🔁 替换(替换型预览卡主按钮):快照比对 → 覆盖/降级插入(12 §5) */
    fun onReplace(message: PanelMessage.Result)
    /** 替换卡「恢复原文」入口(内存暂存原文反向替换;位置变化提示手动处理) */
    fun onRestoreReplace()
}

/**
 * 问答面板:模型选择、会话操作、授权确认、消息和输入行。
 */
@Composable
fun PanelScreen(state: PanelUiState, callbacks: PanelCallbacks, bottomInset: Dp) {
    AssistantTheme { PanelContent(state, callbacks, bottomInset) }
}

internal fun isLocalPanelTextCommand(key: Any): Boolean = when (key) {
    TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey,
    TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey -> true
    else -> false
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PanelContent(state: PanelUiState, callbacks: PanelCallbacks, bottomInset: Dp) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val settingsRepo = remember(appContext) { SettingsRepository(appContext) }
    val modelStore = remember(appContext) { ModelStore(appContext) }
    Surface(
        modifier = Modifier.fillMaxWidth().filterTextContextMenuComponents { isLocalPanelTextCommand(it.key) },
        color = Surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = bottomInset)) {
            Column(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                PanelTopBar(state = state, callbacks = callbacks)
                HorizontalDivider(color = LineGray)
                val drawer = state.drawerTab
                val confirmation = state.confirmation
                when {
                    confirmation != null -> {
                        ConfirmationBar(
                            request = confirmation,
                            onDecision = { decision -> callbacks.onDecision(confirmation, decision) },
                        )
                    }
                    state.profileOpen -> ProfilePanel(state, callbacks)
                    state.modelManageOpen -> Box(Modifier.weight(1f).fillMaxWidth()) {
                        ModelManageScreen(
                            repo = settingsRepo,
                            store = modelStore,
                            onBack = callbacks::onCloseModelManage,
                            panelInputBinder = ImePanelTextFieldBinder(callbacks::onBindModelManageInput),
                            handleSystemBack = false,
                        )
                    }
                    state.chipSheetOpen -> ChipSheet(state, callbacks)
                    drawer != null -> {
                        HistoryDrawer(
                            tab = drawer, onTab = callbacks::onDrawerTab,
                            sessions = state.sessions, activity = state.activity,
                            onOpenSession = callbacks::onOpenSession, onDeleteSession = callbacks::onDeleteSession,
                            canDelete = !state.busy,
                        )
                    }
                    state.configured == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PrimaryBlue, strokeWidth = 2.dp)
                    }
                    state.configured == false -> EmptyState(onGoConfigure = callbacks::onOpenModelManage)
                    else -> AgentChatArea(state, callbacks)
                }
            }
        }
    }
}

// ==================== 顶栏 ====================

@Composable
private fun PanelTopBar(state: PanelUiState, callbacks: PanelCallbacks) {
    val canNavigate = !state.busy && !state.profileOpen && !state.modelManageOpen && state.confirmation == null
    Row(
        modifier = Modifier.fillMaxWidth().background(SystemBg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(
                    enabled = canNavigate,
                    onClick = {
                        callbacks.onCloseDrawer()
                        callbacks.onToggleChipSheet()
                    },
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val modelText = stringResource(R.string.panel_model_menu)
            Text(
                modelText, color = AppTheme.componentColors.sectionText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            AssistantIcon(if (state.chipSheetOpen && canNavigate) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        }
        if (!state.modelManageOpen) {
            AssistantIconButton(
                R.drawable.ic_baseline_plus_24, stringResource(R.string.qa_new_conversation_desc),
                onClick = {
                    state.chipSheetOpen = false
                    callbacks.onNewConversation()
                },
                enabled = canNavigate,
            )
        }
        AssistantIconButton(
            R.drawable.assistant_history, stringResource(R.string.panel_history_desc),
            onClick = {
                state.chipSheetOpen = false
                callbacks.onOpenHistory()
            },
            selected = state.drawerTab != null,
            enabled = canNavigate,
        )
        AssistantIconButton(
            R.drawable.assistant_close, stringResource(R.string.panel_close_desc), callbacks::onClosePanel,
        )
    }
}

// ==================== S9 空态 ====================

/** S9 空态(M3-D 精简):核心一句 + 直达按钮;按钮文案「去配置模型」已表达动作,不再重复说明 */
@Composable
private fun EmptyState(onGoConfigure: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onGoConfigure,
            shape = androidx.compose.material3.MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, contentColor = OnPrimary),
        ) {
            Text(stringResource(R.string.panel_empty_cta), fontSize = 13.sp)
        }
    }
}

// ==================== 消息流 + 单槽系统区 + 输入行 ====================

/** P1 chip 底部弹层:标题「选择模型」;默认✓/最近使用/按服务商分组;底部「管理模型 →」 */
@Composable
private fun ChipSheet(state: PanelUiState, callbacks: PanelCallbacks) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.TextButton(onClick = callbacks::onOpenModelManage) {
            Text(stringResource(R.string.panel_manage_models), color = BodyText)
        }
        if (state.imeInputMode) {
            androidx.compose.material3.TextButton(onClick = callbacks::onOpenProfile) {
                Text(stringResource(R.string.panel_profile), color = BodyText)
            }
        }
    }
}

// M3-C 评审 P2:OkGreenSheet 硬编码 #16A34A 已并入主题 OkGreen(light 同值,dark 提亮)

@Composable
private fun AgentChatArea(state: PanelUiState, callbacks: PanelCallbacks) {
    val listState = rememberLazyListState()
    // P3(评审修正单):key 加流式卡文本——流式回答增长时持续自动滚到底,
    // 否则逐字渲染只在 messages.size 变化时滚一次,长回答看不到最新内容
    val streamingText = (state.messages.lastOrNull() as? PanelMessage.Result)
        ?.takeIf { it.streaming }?.text.orEmpty()
    val itemCount = state.messages.size + if (state.profileSuggestion != null) 1 else 0
    LaunchedEffect(itemCount, streamingText) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // 「总是允许」撤销条(P1-1 状态提升):确认条已消失仍显示 5s
        state.undoAlwaysToolId?.let { toolId ->
            LaunchedEffect(toolId) {
                kotlinx.coroutines.delay(5000)
                callbacks.onUndoExpire()
            }
            UndoAlwaysBar(
                toolId = toolId,
                onUndo = { callbacks.onUndoAlways(toolId) },
            )
        }
        // fc=NO 单轮降级提示条(10 号设计 §4.2;不阻断,静态常显)
        if (state.fcDegraded) {
            Text(
                stringResource(R.string.panel_no_tools_status),
                fontSize = 11.sp, color = WarnText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarnBg)
                    .border(1.dp, WarnBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        // M3 替换恢复提示条(12 §5:8s 展示期;点击 = 反向替换恢复原文)
        state.replaceUndo?.takeIf { !state.replaceUndoHintExpired }?.let { undo ->
            LaunchedEffect(undo.createdAtMs) {
                kotlinx.coroutines.delay(8000)
                state.replaceUndoHintExpired = true // 提示条消失;卡上「恢复原文」入口保留
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .background(OkBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.panel_replace_done_hint),
                    fontSize = 11.sp,
                    color = OkGreen,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.panel_restore),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryBlue,
                    modifier = Modifier
                        .clickable { callbacks.onRestoreReplace() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // 运行条:状态文字随阶段变化(思考 / 查找资料 / 联网搜索 / 整理答案 / 重试中)
        if (state.busy) {
            RunBar(PanelReducer.runPhase(state.messages))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.messages) { _, message ->
                MessageItem(message, callbacks)
            }
            state.profileSuggestion?.let { suggestion ->
                item(key = "profile-suggestion") {
                    ProfileSuggestionRow(suggestion, state.savingProfileSuggestion, callbacks::onProfileSuggestionDecision)
                }
            }
        }
        HorizontalDivider(color = LineGray)
        InputRow(state, callbacks)
    }
}

/** 运行条(脉冲点 + 当前阶段 + ■ 停止,12 号设计 §7);阶段文字由消息流推断,见 PanelReducer.runPhase */
@Composable
private fun RunBar(phase: RunPhase) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SystemBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = PrimaryBlue, strokeWidth = 2.dp, modifier = Modifier.width(14.dp).height(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                when (phase) {
                    RunPhase.THINKING -> R.string.panel_running_hint
                    RunPhase.KNOWLEDGE_SEARCH -> R.string.panel_phase_knowledge
                    RunPhase.WEB_SEARCH -> R.string.panel_phase_web
                    RunPhase.TOOL -> R.string.panel_phase_tool
                    RunPhase.ANSWER -> R.string.panel_phase_answer
                    RunPhase.RETRYING -> R.string.panel_retrying_hint
                },
            ),
            fontSize = 12.sp,
            color = MutedText,
        )
    }
}

@Composable
private fun MessageItem(message: PanelMessage, callbacks: PanelCallbacks) {
    when (message) {
        is PanelMessage.UserText -> UserBubble(message.text, message.actionTag)
        is PanelMessage.Loading -> LoadingRow()
        is PanelMessage.RetryNotice -> LoadingRow(R.string.panel_retrying_hint)
        is PanelMessage.ToolCard -> ToolCard(message)
        is PanelMessage.Result -> ResultCard(message, callbacks)
        is PanelMessage.Error -> ErrorCard(message, callbacks)
        is PanelMessage.System -> SystemCard(message)
    }
}

@Composable
private fun UserBubble(text: String, actionTag: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 快捷动作前缀小标签(12 §3:✨帮写/🔁改写/🌐翻译);M3-C 评审 P2:底色 #EFF6FF → chipBg
            actionTag?.let {
                Text(
                    it,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryBlue,
                    modifier = Modifier
                        .background(ChipBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(modifier = Modifier.height(3.dp))
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(ChipBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text, color = BodyText, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun LoadingRow(text: Int = R.string.panel_loading) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = PrimaryBlue, strokeWidth = 2.dp, modifier = Modifier.width(16.dp).height(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(text), fontSize = 13.sp, color = MutedText)
    }
}

/** 「总是允许」5s 撤销条(P1-1:替代不可点击的系统 toast,行为等同 toast 附「撤销」) */
@Composable
private fun UndoAlwaysBar(toolId: String, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(OkBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已记忆为总是允许", fontSize = 11.sp, color = OkGreen)
        Spacer(Modifier.width(8.dp))
        Text(
            "撤销",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryBlue,
            modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ==================== 工具卡片(四态) ====================

@Composable
private fun ToolCard(card: PanelMessage.ToolCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, if (card.state == ToolCardState.FAILED) ErrorBorder else LineGray, RoundedCornerShape(8.dp))
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
            Spacer(Modifier.width(4.dp))
            when (card.state) {
                ToolCardState.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = PrimaryBlue, strokeWidth = 1.5.dp, modifier = Modifier.width(10.dp).height(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.panel_tool_running), fontSize = 10.sp, color = ChipText)
                }
                ToolCardState.DONE -> Text(
                    stringResource(R.string.panel_tool_done),
                    fontSize = 10.sp,
                    color = OkGreen,
                    modifier = Modifier.background(OkBg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
                ToolCardState.FAILED -> Text(
                    stringResource(R.string.panel_tool_failed),
                    fontSize = 10.sp,
                    color = ErrorText,
                    modifier = Modifier.background(ErrorBg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        if (card.summary.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(card.summary, fontSize = 11.sp, color = MutedText, maxLines = 2)
        }
        // 提示注入警示(FR-E4:指令样文本用户可见警示)
        if (card.flagged) {
            Spacer(Modifier.height(4.dp))
            Text(
                InjectionScanner.warningLabel(),
                fontSize = 10.sp,
                color = WarnText,
                modifier = Modifier
                    .background(WarnBg, RoundedCornerShape(6.dp))
                    .border(1.dp, WarnBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

// ==================== 预览卡 ====================

/** 预览卡(12 号设计 §4):徽章 + 正文(替换型含新旧对照)+ citations + meta + 按钮组 + 关闭 */
@Composable
private fun ResultCard(message: PanelMessage.Result, callbacks: PanelCallbacks) {
    val replaceMode = message.oldText != null
    val answerText = remember(message.text, message.streaming) {
        val formatted = formatAnswerText(message.text)
        if (message.streaming) formatted + androidx.compose.ui.text.AnnotatedString("▍") else formatted
    }
    // 替换卡原文折叠态(超 3 行折叠,点开全文)
    var oldCollapsed by remember { mutableStateOf(true) }
    // 重试期间草稿保留但灰显,表示这段内容还没定稿
    val answerColor = if (message.retrying) BodyText.copy(alpha = 0.55f) else BodyText
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, if (message.unverified) WarnBorder else LineGray, RoundedCornerShape(8.dp))
            .background(AppTheme.componentColors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        val badge = if (message.unverified) stringResource(R.string.panel_badge_unverified) else message.actionTag
        if (badge != null) {
            Text(
                badge,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (message.unverified) WarnText else ChipText,
                modifier = Modifier
                    .background(if (message.unverified) WarnBg else ChipBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (replaceMode) {
            // 替换型预览卡正文(12 §4/§5):上块灰底删除线「原文」(超 3 行折叠)+ 下块白底「新文本」
            Text(stringResource(R.string.panel_replace_old_label), fontSize = 10.sp, color = MutedText)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    message.oldText ?: "",
                    fontSize = 13.sp,
                    color = MutedText,
                    maxLines = if (oldCollapsed) 3 else Int.MAX_VALUE,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SystemBg, RoundedCornerShape(6.dp))
                        .clickable { oldCollapsed = !oldCollapsed }
                        .padding(8.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(R.string.panel_replace_new_label), fontSize = 10.sp, color = ChipText)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    answerText,
                    fontSize = 14.sp,
                    color = answerColor,
                )
            }
        } else {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    answerText,
                    fontSize = 14.sp,
                    color = answerColor,
                )
            }
        }
        if (message.streaming) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(if (message.retrying) R.string.panel_retrying_hint else R.string.panel_streaming_hint),
                fontSize = 10.sp,
                color = MutedText,
            )
        }
        // 预算耗尽后的收尾回答:资料不全,灰色小字标注
        if (message.partial) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.panel_partial_hint), fontSize = 10.sp, color = MutedText)
        }
        // 来源列表(📖 N;M4 出处强化:kb 来源显示「文档 §片段 · 片段首句」)
        if (message.citations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(R.string.panel_sources_count, message.citations.size), fontSize = 11.sp, color = BodyText, fontWeight = FontWeight.SemiBold)
            message.citations.take(5).forEach { c ->
                val line = if (c.snippet.isNotBlank()) "${c.title} · ${c.snippet}" else c.title.ifBlank { c.url }
                Text(
                    line,
                    fontSize = 10.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
        }
        val canInsert = message.canInsert
        if (canInsert || message.metaLine != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    message.metaLine?.let {
                        Text(it, fontSize = 10.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (canInsert) {
                    androidx.compose.material3.TextButton(
                        onClick = { callbacks.onInsert(message) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        AssistantIcon(R.drawable.ic_baseline_arrow_downward_24, tint = BodyText)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.panel_insert_plain), color = BodyText, fontSize = 12.sp)
                    }
                }
            }
        }
        if (canInsert && replaceMode) {
            androidx.compose.material3.TextButton(
                onClick = { callbacks.onReplace(message) },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.panel_replace)) }
            if (message.replaceSucceeded) {
                androidx.compose.material3.TextButton(
                    onClick = callbacks::onRestoreReplace,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.panel_restore)) }
            }
        }
    }
}

// ==================== 错误卡 / 系统卡 ====================

/** 拦截原因文案:引擎给的稳定标识(AgentContentSafety 的原因码)→ strings;空或未知返回 null */
internal fun unsafeReasonRes(reason: String): Int? = when (reason) {
    com.agentkeyboard.agent.AgentContentSafety.TOO_LONG -> R.string.qa_unsafe_reason_too_long
    com.agentkeyboard.agent.AgentContentSafety.GUARDRAIL -> R.string.qa_unsafe_reason_guardrail
    com.agentkeyboard.agent.AgentContentSafety.SECRET -> R.string.qa_unsafe_reason_secret
    com.agentkeyboard.agent.AgentContentSafety.INJECTION -> R.string.qa_unsafe_reason_injection
    com.agentkeyboard.agent.AgentContentSafety.FORMAT -> R.string.qa_unsafe_reason_format
    com.agentkeyboard.agent.AgentContentSafety.TOOL_CALL_ID -> R.string.qa_unsafe_reason_tool_call_id
    else -> null
}

/**
 * 错误卡(12 号设计 §8 五类成品文案;P3-8:文案由 kind→strings 单源,engine 不再携带文本)。
 * 下方一行调试信息说明是哪类错误、什么状态码、花了多久,可复制进问题反馈;
 * 可重试的错误给「重试」,配置类错误给「去设置」。
 */
@Composable
private fun ErrorCard(message: PanelMessage.Error, callbacks: PanelCallbacks) {
    if (message.kind == "UNSAFE_INPUT" || message.kind == "UNSAFE_OUTPUT") {
        // 多说一句是哪条客观条件不满足(超长/带出安全规则/含密钥…),再误拦时能直接对上规则
        val reasonRes = unsafeReasonRes(message.message)
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(
                stringResource(if (message.kind == "UNSAFE_INPUT") R.string.qa_unsafe_input else R.string.qa_unsafe_output),
                color = ErrorText, fontSize = 13.sp,
            )
            if (reasonRes != null) {
                Text(
                    stringResource(R.string.qa_unsafe_reason, stringResource(reasonRes)),
                    color = MutedText, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
        }
        return
    }
    val showSettings = message.kind == "BAD_KEY" || message.kind == "NOT_CONFIGURED" || message.kind == "LLM_DENIED"
    val text = when (message.kind) {
        "NO_NETWORK" -> stringResource(R.string.panel_err_no_network)
        "BAD_KEY" -> stringResource(R.string.panel_err_bad_key)
        "TIMEOUT" -> stringResource(R.string.panel_err_timeout)
        "RATE_LIMIT" -> stringResource(R.string.panel_err_rate_limit)
        "BALANCE" -> stringResource(R.string.panel_err_balance)
        "CONTEXT_LENGTH" -> stringResource(R.string.panel_err_context_length)
        "NOT_CONFIGURED" -> stringResource(R.string.panel_empty_title)
        "LLM_DENIED" -> stringResource(R.string.panel_llm_denied)
        else -> stringResource(R.string.panel_err_generic)
    }
    val diagnostics = message.diagnostics
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, ErrorBorder, RoundedCornerShape(10.dp))
            .background(ErrorBg, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(text, fontSize = 13.sp, color = ErrorText)
        if (diagnostics != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(diagnostics, fontSize = 10.sp, lineHeight = 14.sp, color = MutedText, maxLines = 3)
        }
        if (message.retryable || diagnostics != null || showSettings) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.retryable) {
                    ErrorAction(R.string.panel_err_retry, callbacks::onRetry)
                }
                if (diagnostics != null) {
                    ErrorAction(R.string.panel_err_copy_diagnostics) { callbacks.onCopyDiagnostics(diagnostics) }
                }
                if (showSettings) {
                    ErrorAction(R.string.panel_err_go_settings, callbacks::onOpenSettings)
                }
            }
        }
    }
}

/** 错误卡上的描边按钮(触控高度不小于 48dp) */
@Composable
private fun ErrorAction(label: Int, onClick: () -> Unit) {
    Text(
        stringResource(label),
        fontSize = 12.sp,
        color = ErrorText,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .border(1.dp, ErrorBorder, RoundedCornerShape(8.dp))
            .background(Surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SystemCard(card: PanelMessage.System) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(SystemBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(card.text, fontSize = 11.sp, color = MutedText)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PanelTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    imeInputMode: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
    minLines: Int = 1,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val hostWindow = LocalWindowInfo.current
    // The editor keeps window focus while Fcitx routes keys to this composer.
    val composerWindow = remember(hostWindow, imeInputMode) {
        object : WindowInfo by hostWindow {
            override val isWindowFocused get() = hostWindow.isWindowFocused || imeInputMode
        }
    }
    LaunchedEffect(imeInputMode) {
        if (imeInputMode) focusRequester.requestFocus()
    }
        CompositionLocalProvider(LocalWindowInfo provides composerWindow) {
            InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .background(InputBg, androidx.compose.material3.MaterialTheme.shapes.small)
                    .border(1.dp, if (focused) PrimaryBlue else LineGray, androidx.compose.material3.MaterialTheme.shapes.small),
                textStyle = androidx.compose.ui.text.TextStyle(color = BodyText, fontSize = 14.sp, lineHeight = 20.sp),
                minLines = minLines,
                maxLines = maxLines,
                cursorBrush = SolidColor(PrimaryBlue),
                enabled = true,
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(hint, fontSize = 13.sp, color = MutedText)
                        }
                        innerTextField()
                    }
                },
                )
            }
        }
}

@Composable
private fun InputRow(state: PanelUiState, callbacks: PanelCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelTextField(
            value = state.inputFieldValue,
            onValueChange = callbacks::onInputValueChanged,
            imeInputMode = state.imeInputMode,
            hint = stringResource(R.string.panel_input_hint),
            modifier = Modifier.weight(1f),
        )
        AssistantIconButton(
            icon = if (state.busy) R.drawable.assistant_stop else R.drawable.ic_baseline_arrow_upward_24,
            description = stringResource(if (state.busy) R.string.panel_stop else R.string.panel_send),
            onClick = { if (state.busy) callbacks.onStop() else callbacks.onSend(state.input) },
            enabled = state.busy || state.input.isNotBlank(),
            danger = state.busy,
            modifier = Modifier.size(48.dp),
        )
    }
}
