package com.agentkeyboard.ime

import com.agentkeyboard.util.Logs
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentkeyboard.BuildConfig
import org.fcitx.fcitx5.android.R
import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.agent.AgentKit
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.agent.Task
import com.agentkeyboard.agent.TaskType
import com.agentkeyboard.agent.knowledgeAskTask
import com.agentkeyboard.data.ModelConfig
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.ime.panel.ConfirmationDecision
import com.agentkeyboard.ime.panel.PanelMessage
import com.agentkeyboard.ime.panel.PanelReducer
import com.agentkeyboard.ime.panel.sessionToListItem
import com.agentkeyboard.ime.pinyin.LibimePinyinEngine
import com.agentkeyboard.ime.pinyin.T9Pinyin
import com.agentkeyboard.ime.pinyin.UserDictionary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent Keyboard IME 服务(M0-T2/T3 + M1-T4/T7)。
 *
 * Compose 挂载方案:
 * - [ImeLifecycleOwner] 作为 ViewTree 的 Lifecycle/ViewModelStore/SavedStateRegistry 三合一 owner,
 *   在 onCreateInputView 时挂到容器上(必须在 ComposeView attach 之前);
 * - 组合策略 DisposeOnDetachedFromWindowOrReleasedFromPool(detach 即释放,重新 attach 自动重建);
 * - 容器为竖排 LinearLayout:面板在上(按 [panelVisible] 切换),键盘在下。
 *
 * M1 批次 2:面板发送走 AgentEngine(AgentKit),单轮 LlmClient 保留为快捷动作路径(批次 3);
 * 收起面板/切输入框不打断运行(12 号设计 §7),任务继续执行并落盘。
 */
class AgentKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "AgentKeyboard"
        private const val CONTEXT_BEFORE_CHARS = 200 // context_before_chars 默认(02-架构 §2.4)
    }

    private val lifecycleOwner = ImeLifecycleOwner()
    private val kbState = KeyboardUiState()
    private val panelState = PanelUiState()
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val clipboardHistory = ClipboardHistory()
    private val userDictionary = UserDictionary()

    /** 面板/引擎作用域:不随面板组合销毁而取消,服务销毁时统一 cancel */
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var keyboardView: View? = null
    private var panelView: View? = null
    private var panelVisible = false

    /** 输入目标代次只跟踪编辑器元数据,不读取宿主文本。 */
    private data class InputTarget(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
        val privateImeOptions: String?,
    )

    private var inputTarget: InputTarget? = null
    private var editorGeneration = 0L
    private var pendingAnswerEditorGeneration: Long? = null

    /** 追问目标会话(🕘 列表打开后设置;发送时携带 → engine 复用会话并折叠旧上下文) */
    private var activeSessionId: Long? = null

    /** 流式上屏(FR-D2):opt-in 缓存 + 本次流式的已固化内容 */
    @Volatile
    private var streamToImeEnabled = false
    private val streamCommitted = StringBuilder()
    private var streamComposingActive = false
    private var streamAborted = false

    /** 本次运行的起始时刻(Finished meta 行耗时) */
    private var startedAtElapsed = 0L

    /** debug:首个 ContentDelta 只记一条日志 */
    @Volatile
    private var loggedFirstDelta = false

    /** 确认条当前等待中的用户决定(resolver 挂起点) */
    private var activeDecision: CompletableDeferred<ConfirmationDecision>? = null

    /** 「总是允许」撤销条状态(P1-1 状态提升;面板销毁/重建不丢) */
    private val undoAlways = com.agentkeyboard.ime.panel.UndoAlwaysController()

    /**
     * M3 快捷动作在途状态:动作类型 + 替换快照(源文本/是否来自选区),
     * Finished 时据此构造替换型预览卡(改写/翻译带 oldText,帮写为普通插入卡)。
     */
    private var pendingAction: QuickAction? = null
    private var pendingSource: String = ""
    private var pendingFromSelection: Boolean = false

    /**
     * P0(评审修正单,权限红线):直出首次明示守卫——直出(runQuickComplete)不走
     * AgentRuntime,若不查 permission_state 就绕过 llm_call 首次明示。未明示时单击直出
     * 转投 startQuickAction 面板路径(确认条在面板可见呈现),已明示放行并落 llm_call
     * 活动日志(见 [QuickActionDirectGuard])。
     */
    private val directGuard = QuickActionDirectGuard(
        llmDisclosed = { agentKit.gate.llmDisclosed() },
        logLlmCall = { entity ->
            runCatching { AgentDatabase.get(applicationContext).activityLogDao().insert(entity) }
                .onFailure { Log.w(TAG, "quick llm_call log failed: ${it.javaClass.simpleName}") }
        },
    )

    /**
     * P2(评审修正单):直出帮写点击时刻的输入会话身份(EditorInfo hashCode + 光标前文本),
     * 完成回调(最长 60s 后)据此校验,编辑器身份/上文不一致 → 内容丢弃不落进无关会话。
     */
    private var quickInsertIdentity: QuickInsertGuard.SessionIdentity? = null

    /**
     * V-A 直通执行器:单击 = 一键直出(独立小循环,一次 LLM 调用无 tools,不走
     * AgentKit 面板事件流,不产生消息流);运行中再点 = 取消;多动作并行、同按钮互斥。
     * 长按入口(onQuickActionLongPress)仍走 startQuickAction 面板路径。
     */
    private val quickRunner = QuickActionRunner(
        scope = panelScope,
        complete = { action, source -> runQuickComplete(action, source) },
        callbacks = object : QuickActionRunner.Callbacks {
            override fun onRunningChanged(action: QuickAction, running: Boolean) {
                kbState.quickRunning = if (running) kbState.quickRunning + action else kbState.quickRunning - action
            }

            override fun onResult(action: QuickAction, result: String, source: String, fromSelection: Boolean) {
                if (action.replaceMode) applyQuickReplace(result, source, fromSelection)
                else applyQuickInsert(result)
            }

            override fun onError(action: QuickAction, kind: String) {
                Toast.makeText(this@AgentKeyboardService, quickErrorText(kind), Toast.LENGTH_SHORT).show()
                Logs.i(TAG, "quick action failed: $action kind=$kind")
            }

            override fun onCancelled(action: QuickAction) {
                Logs.i(TAG, "quick action cancelled: $action")
            }
        },
    )

    /**
     * ConfirmationResolver 面板实现(契约见 ConfirmationResolver KDoc):
     * 置面板确认条状态 → 挂起等用户决定 → 「总是允许」先 setAlwaysAllow 落盘再 resolve(true),
     * 并设置撤销条(5s 内可撤销)。
     */
    private val panelResolver = ConfirmationResolver { request ->
        panelState.confirmation = request
        val deferred = CompletableDeferred<ConfirmationDecision>()
        activeDecision = deferred
        val decision = try {
            deferred.await()
        } finally {
            // 取消/销毁令 await 抛 CancellationException 时也要清理挂起态(与 Fcitx 控制器同口径)
            if (activeDecision === deferred) activeDecision = null
            panelState.confirmation = null
        }
        if (decision.allowed && decision.rememberAlways) {
            agentKit.gate.setAlwaysAllow(request.toolId) // 先落盘再放行
            undoAlways.mark(request.toolId)
            panelState.undoAlwaysToolId = request.toolId
        }
        decision.allowed
    }

    private val agentKit: AgentKit by lazy {
        AgentFactory.createKnowledgeAssistant(applicationContext, panelResolver)
    }

    /**
     * 拼音解码器(M1-T5):assets 词库一次性加载;失败 = null(降级英文)。
     * P3(评审修正单):词库加载挪 Dispatchers.IO 后台预加载(onCreate 触发)——
     * 原实现 by lazy 在主线程首次触键时同步读文件(首触卡顿);未加载完成时为 null,
     * 表现为拼音键暂不可切,加载完成后自动可用(pinyinAvailable 随后台结果置位)。
     */
    @Volatile
    private var pinyinEngine: LibimePinyinEngine? = null

    /** 词库加载完成信号(true=成功,false=失败降级);onTogglePinyin 用它避免误降级 */
    private val pinyinLoaded = CompletableDeferred<Boolean>()

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.dispatch(Lifecycle.Event.ON_CREATE)
        // 订阅必须先于任何 engine.start(面板渲染唯一来源)
        panelScope.launch {
            agentKit.events.collect { onAgentEvent(it) }
        }
        registerDebugHooks()
        // libime 词典和语言模型在 IO 线程初始化,不阻塞首帧或首次按键。
        panelScope.launch(Dispatchers.IO) {
            val engine = try {
                LibimePinyinEngine.create(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "libime pinyin engine load failed: ${e.javaClass.simpleName}")
                null
            }
            pinyinEngine = engine
            pinyinLoaded.complete(engine != null)
        }
        panelScope.launch { kbState.pinyinAvailable = pinyinLoaded.await() }
        panelScope.launch {
            kbState.layout = when (settingsRepo.keyboardLayout()) {
                KeyboardLayout.T9.name -> KeyboardLayout.T9
                else -> KeyboardLayout.QWERTY
            }
        }
        panelScope.launch { kbState.heightScale = settingsRepo.keyboardHeightScale() }
        panelScope.launch(Dispatchers.IO) {
            userDictionary.restore(settingsRepo.userDictionary())
        }
        panelScope.launch { streamToImeEnabled = settingsRepo.isStreamToIme() }
        // M3-C(audit#8):按键震动开关同步(设置页可改,输入会话开始时重读)
        panelScope.launch { KeyboardHaptics.enabled = settingsRepo.isHapticEnabled() }
    }

    /**
     * 仅 debug 构建的自动化测试钩子:向面板输入行写文本/触发发送
     * (adb 无中文 input 手段;release 构建不注册,无生产影响)。
     * 用法:adb shell am broadcast -a com.agentkeyboard.DEBUG_PANEL_INPUT --es text "…"(| "\n" 触发发送)
     */
    /** debug-only 广播钩子;onDestroy 反注册(2026-11-19 审查:IME 重建不泄漏旧 service 实例) */
    private val debugHooks = mutableListOf<android.content.BroadcastReceiver>()

    private fun registerDebugHooks() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val panelInputHook = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (!debugSenderAllowed()) return // P2:防 debug 设备上第三方 app 触发发送
                val text = intent?.getStringExtra("text") ?: return
                if (text == "\n") {
                    sendToAgent(panelState.input)
                } else {
                    panelState.input += text
                }
            }
        }
        registerReceiver(
            panelInputHook,
            android.content.IntentFilter("com.agentkeyboard.DEBUG_PANEL_INPUT"),
            android.content.Context.RECEIVER_EXPORTED, // Android 14 必选;debug 钩子需接收 adb(shell)广播,release 不注册
        )
        debugHooks += panelInputHook
        // M1 批次 3 验收钩子(同上 debug-only):模拟器 input tap 对 IME 窗口不可靠,
        // 拼音键入/上屏、chip 弹层、停止改走广播驱动同一生产代码路径。
        val imeHooks = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (!debugSenderAllowed()) return // P2:同上,来源校验
                    when (intent?.action) {
                        "com.agentkeyboard.DEBUG_PINYIN" -> {
                            val seq = intent.getStringExtra("seq").orEmpty()
                            if (pinyinEngine == null) return
                            kbState.pinyinMode = true
                            kbState.pinyinAvailable = true
                            kbState.pinyinBuffer = seq
                            kbState.pinyinCandidatePage = 0
                            kbState.pinyinCandidatesExpanded = false
                            kbState.pinyinInputRequestedAtNanos =
                                android.os.SystemClock.elapsedRealtimeNanos()
                            kbState.pinyinInputMetricSource = PinyinMetricSource.DEBUG_BROADCAST
                            kbState.pinyinMetricSampleId += 1
                            keyboardCallbacks().onPinyinBufferChanged(seq)
                        }
                        "com.agentkeyboard.DEBUG_PINYIN_COMMIT" -> {
                            val engine = pinyinEngine ?: return
                            val word = intent.getStringExtra("word")
                                ?: if (kbState.layout == KeyboardLayout.T9) {
                                    engine.topCandidateForT9(kbState.pinyinBuffer)
                                } else {
                                    engine.topCandidate(kbState.pinyinBuffer)
                                }
                                ?: kbState.pinyinBuffer
                            kbState.pinyinCandidatePressAtNanos =
                                android.os.SystemClock.elapsedRealtimeNanos()
                            kbState.pinyinCandidateMetricSource = PinyinMetricSource.DEBUG_BROADCAST
                            kbState.pinyinCandidateMetricSampleId += 1
                            keyboardCallbacks().onSelectPinyinCandidate(word)
                        }
                        "com.agentkeyboard.DEBUG_CHIP_SHEET" -> {
                            panelState.chipSheetOpen = intent.getBooleanExtra("open", true)
                            if (panelState.chipSheetOpen) {
                                panelScope.launch {
                                    val store = com.agentkeyboard.data.model.ModelStore(applicationContext)
                                    loadChipData(store, store.load())
                                }
                            }
                        }
                        "com.agentkeyboard.DEBUG_PANEL_SEND" -> {
                            // 可选 text extra:直接设置面板输入(模拟器 adb 文本注入对 IME 窗口不可靠)
                            intent.getStringExtra("text")?.let { panelState.input = it }
                            Logs.i(TAG, "debug panel send: len=${panelState.input.length} busy=${panelState.busy} mode=${kbState.mode} panelVisible=$panelVisible")
                            sendToAgent(panelState.input)
                        }
                        "com.agentkeyboard.DEBUG_INSERT" -> {
                            val last = panelState.messages.lastOrNull {
                                it is PanelMessage.Result && !it.readonly
                            } as? PanelMessage.Result
                            if (last != null) panelCallbacks().onInsert(last)
                        }
                        "com.agentkeyboard.DEBUG_SELECT_MODEL" -> {
                            val key = intent.getStringExtra("ref").orEmpty()
                            if (key.isNotEmpty()) {
                                panelScope.launch {
                                    val file = com.agentkeyboard.data.model.ModelStore(applicationContext).load()
                                    val m = file.models.firstOrNull { it.ref == key }
                                        ?: file.models.firstOrNull { it.modelId == key }
                                        ?: file.models.firstOrNull { it.display() == key }
                                    if (m != null) panelCallbacks().onSelectModel(m.ref)
                                    else Log.w(TAG, "debug select model not found: $key")
                                }
                            }
                        }
                        "com.agentkeyboard.DEBUG_STOP" -> {
                            Logs.i(TAG, "debug stop broadcast received")
                            agentKit.engine.cancel()
                        }
                        "com.agentkeyboard.DEBUG_QUICK_ACTION" -> {
                            // M3/V-A 验收钩子:模拟器 adb 对 IME 窗口 tap 不可靠,改走广播驱动同一生产路径。
                            // V-A 直通改造:mode 缺省 "direct" = 单击直出(不进面板);"panel" = 长按路径(面板预填)。
                            val action = when (intent?.getStringExtra("action")) {
                                "rewrite" -> QuickAction.REWRITE
                                "translate" -> QuickAction.TRANSLATE
                                else -> QuickAction.HELP_WRITE
                            }
                            val mode = intent?.getStringExtra("mode") ?: "direct"
                            Logs.i(TAG, "debug quick action: $action mode=$mode")
                            if (mode == "panel") {
                                keyboardCallbacks().onQuickActionLongPress(action)
                            } else {
                                keyboardCallbacks().onQuickAction(action)
                            }
                        }
                        "com.agentkeyboard.DEBUG_QUICK_RESTORE" -> {
                            // V-A 验收钩子:直出替换恢复条「↩ 恢复原文」(键盘区域 tap 不可靠)
                            Logs.i(TAG, "debug quick restore")
                            keyboardCallbacks().onQuickRestore()
                        }
                        "com.agentkeyboard.DEBUG_CONFIRM" -> {
                            // P0 验收钩子:模拟器 input tap 对 IME 窗口不可靠(llm_call 首次明示
                            // 确认条),广播驱动同一生产路径(onDecision → activeDecision.complete
                            // → resolver 恢复)。allow=false 走拒绝分支。
                            val allow = intent?.getBooleanExtra("allow", true) ?: true
                            val request = agentKit.pendingConfirmation.value
                            if (request != null) {
                                panelCallbacks().onDecision(request, ConfirmationDecision(allow, false))
                                Logs.i(TAG, "debug confirm: allow=$allow tool=${request.toolId}")
                            } else {
                                Log.w(TAG, "debug confirm: no pending confirmation")
                            }
                        }
                        "com.agentkeyboard.DEBUG_REPLACE" -> {
                            // M3 验收钩子:对最后一张替换型预览卡执行替换
                            val last = panelState.messages.lastOrNull {
                                it is PanelMessage.Result && !it.readonly && it.oldText != null
                            } as? PanelMessage.Result
                            if (last != null) performReplace(last)
                        }
                        "com.agentkeyboard.DEBUG_RESTORE" -> {
                            restoreReplace()
                        }
                        "com.agentkeyboard.DEBUG_BACKSPACE" -> {
                            // M3-C 验收钩子:模拟器 touch 注入对 IME 窗口不可靠,
                            // 用广播驱动同一退格路径;holdMs>0 复刻长按连删时序(与 UI 手势同一 RepeatDeleteTiming)
                            val holdMs = intent.getLongExtra("holdMs", 0L)
                            if (holdMs <= 0) {
                                keyboardCallbacks().onBackspace()
                            } else {
                                panelScope.launch {
                                    val cb = keyboardCallbacks()
                                    cb.onBackspace() // 按下即删一次
                                    val start = System.currentTimeMillis()
                                    kotlinx.coroutines.delay(com.agentkeyboard.ime.RepeatDeleteTiming.HOLD_TRIGGER_MS)
                                    var i = 0
                                    while (System.currentTimeMillis() - start < holdMs) {
                                        cb.onBackspace()
                                        kotlinx.coroutines.delay(com.agentkeyboard.ime.RepeatDeleteTiming.intervalAt(i))
                                        i++
                                    }
                                    Logs.i(TAG, "debug backspace hold: repeats=$i holdMs=$holdMs")
                                }
                            }
                        }
                    }
                }
            }
        registerReceiver(
            imeHooks,
            android.content.IntentFilter().apply {
                addAction("com.agentkeyboard.DEBUG_PINYIN")
                addAction("com.agentkeyboard.DEBUG_PINYIN_COMMIT")
                addAction("com.agentkeyboard.DEBUG_CHIP_SHEET")
                addAction("com.agentkeyboard.DEBUG_INSERT")
                addAction("com.agentkeyboard.DEBUG_PANEL_SEND")
                addAction("com.agentkeyboard.DEBUG_SELECT_MODEL")
                addAction("com.agentkeyboard.DEBUG_STOP")
                addAction("com.agentkeyboard.DEBUG_QUICK_ACTION")
                addAction("com.agentkeyboard.DEBUG_QUICK_RESTORE")
                addAction("com.agentkeyboard.DEBUG_CONFIRM")
                addAction("com.agentkeyboard.DEBUG_REPLACE")
                addAction("com.agentkeyboard.DEBUG_RESTORE")
                addAction("com.agentkeyboard.DEBUG_BACKSPACE")
            },
            android.content.Context.RECEIVER_EXPORTED,
        )
        debugHooks += imeHooks
    }

    /**
     * P2(评审修正单):debug 广播来源校验——只放行 adb shell(uid 2000,验收脚本)与
     * 本进程自身(应用内触发),其余 uid(第三方 app)一律丢弃,防 debug 设备上被
     * 第三方 app 触发发送/上屏/切模型。
     */
    private fun debugSenderAllowed(): Boolean {
        val caller = android.os.Binder.getCallingUid()
        val ok = caller == android.os.Process.SHELL_UID || caller == android.os.Process.myUid()
        if (!ok) Log.w(TAG, "debug broadcast rejected: callerUid=$caller")
        return ok
    }

    override fun onDestroy() {
        debugHooks.forEach { runCatching { unregisterReceiver(it) } }
        debugHooks.clear()
        lifecycleOwner.dispatch(Lifecycle.Event.ON_DESTROY)
        agentKit.engine.cancel() // P2-6:确认挂起中服务销毁时取消孤儿协程
        pinyinEngine?.close()
        pinyinEngine = null
        panelScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false // 不进入全屏抽取模式,保证面板高度可控

    /**
     * 始终显示输入视图(M1 批次 3 修复):默认实现在设备报告有硬件键盘且
     * show_ime_with_hard_keyboard=0 时拒绝显示输入视图——本产品是触屏键盘,
     * 模拟器/带键盘设备上会出现 IME 窗口不创建(实测 mViewsCreated=false)。
     */
    override fun onEvaluateInputViewShown(): Boolean = true

    // ==================== 事件 → UI(reducer 纯函数) ====================

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Started -> {
                startedAtElapsed = SystemClock.elapsedRealtime()
                kbState.askBadge = KeyboardBadge.RUNNING
            }
            is AgentEvent.Finished -> {
                finishStreamingCommit()
                val elapsed = ((SystemClock.elapsedRealtime() - startedAtElapsed) / 1000).takeIf { it >= 1 }
                // M3 快捷动作:Finished 直接走 onFinished(带动作徽章与替换快照),不经通用 apply
                replaceMessages(
                    PanelReducer.onFinished(
                        panelState.messages.toList(), event.content, event.citations, panelState.modelLabel, elapsed,
                        actionTag = pendingAction?.tag,
                        oldText = pendingAction?.takeIf { it.replaceMode }?.let { pendingSource },
                        fromSelection = pendingFromSelection,
                        editorGeneration = pendingAnswerEditorGeneration,
                    )
                )
                pendingAction = null
                pendingSource = ""
                pendingFromSelection = false
                pendingAnswerEditorGeneration = null
                panelState.busy = false
                kbState.askBadge = KeyboardBadge.DONE
                Logs.i(TAG, "agent finished, session=${event.sessionId} citations=${event.citations.size}")
            }
            is AgentEvent.Incomplete -> {
                finishStreamingCommit()
                replaceMessages(
                    PanelReducer.onIncomplete(
                        panelState.messages.toList(), event.content, event.citations, panelState.modelLabel, null, event.reason,
                        editorGeneration = pendingAnswerEditorGeneration,
                    ),
                )
                pendingAction = null
                pendingSource = ""
                pendingFromSelection = false
                pendingAnswerEditorGeneration = null
                panelState.busy = false
                kbState.askBadge = KeyboardBadge.FAILED
                Log.w(TAG, "agent incomplete, session=${event.sessionId}")
            }
            is AgentEvent.Failed -> {
                finishStreamingCommit()
                replaceMessages(PanelReducer.apply(panelState.messages.toList(), event, panelState.modelLabel, null))
                panelState.busy = false
                kbState.askBadge = KeyboardBadge.FAILED
                pendingAnswerEditorGeneration = null
                Log.w(TAG, "agent failed, kind=${event.error.kind} session=${event.sessionId}")
            }
            is AgentEvent.ContentDelta -> {
                replaceMessages(PanelReducer.apply(panelState.messages.toList(), event, panelState.modelLabel, null))
                // FR-D2 opt-in:最终回答流式直接上屏(编辑器不支持/用户打字 → 退回)
                if (!loggedFirstDelta) {
                    loggedFirstDelta = true
                    Logs.i(TAG, "first delta: streamToIme=$streamToImeEnabled mode=${kbState.mode} aborted=$streamAborted ic!=null=${currentInputConnection != null}")
                }
                if (streamToImeEnabled && pendingAction != null && kbState.mode == Mode.NORMAL) {
                    commitStreamingDelta(event.accumulated)
                }
            }
            is AgentEvent.Stopped -> {
                Logs.i(TAG, "agent stopped event received") // 取消即时性实测:与发送/取消时刻对照
                finishStreamingCommit()
                panelState.confirmation = null // 停止 = 拒绝挂起中的确认
                activeDecision?.cancel()
                activeDecision = null
                replaceMessages(PanelReducer.apply(panelState.messages.toList(), event, panelState.modelLabel, null))
                panelState.busy = false
                kbState.askBadge = KeyboardBadge.NONE
                pendingAnswerEditorGeneration = null
            }
            else -> replaceMessages(PanelReducer.apply(panelState.messages.toList(), event, panelState.modelLabel, null))
        }
    }

    private fun replaceMessages(list: List<PanelMessage>) {
        panelState.messages.clear()
        panelState.messages.addAll(list)
    }

    /**
     * FR-D2 流式上屏:setComposingText 逐字刷新;finishComposingText 收尾。
     * 硬约束:①编辑器不保留 composing → commitText 固化 + 退回预览(toast);
     * ②用户打字/移光标(光标前文本不再是已写入内容)→ 立即固化并停止;③密码框 FAIL-SAFE(本函数入口已挡)。
     */
    private fun commitStreamingDelta(accumulated: String) {
        if (streamAborted) return
        val ic = currentInputConnection
        if (ic == null) {
            streamAborted = true
            return
        }
        val expected = streamCommitted.toString()
        when (ComposingVerifier.verify(expected, ic.getTextBeforeCursor(expected.length + 4, 0)?.toString())) {
            ComposingVerifier.Verdict.USER_EDITED -> {
                runCatching { ic.finishComposingText() } // 固化已有内容
                abortStreaming()
                Toast.makeText(this, R.string.stream_user_typed_stopped, Toast.LENGTH_SHORT).show()
            }
            ComposingVerifier.Verdict.NOT_SUPPORTED -> {
                runCatching { ic.commitText(accumulated, 1) } // 全量固化
                abortStreaming()
                Toast.makeText(this, R.string.stream_fallback_preview, Toast.LENGTH_SHORT).show()
            }
            ComposingVerifier.Verdict.OK -> {
                ic.beginBatchEdit()
                runCatching { ic.setComposingText(accumulated, 1) }
                runCatching { ic.endBatchEdit() }
                if (streamComposingActive || ComposingVerifier.keepsComposing(
                        accumulated, ic.getTextBeforeCursor(accumulated.length, 0)?.toString()
                    )
                ) {
                    streamComposingActive = true
                    streamCommitted.setLength(0)
                    streamCommitted.append(accumulated)
                } else {
                    // 编辑器不保留 composing(FR-D2 约束①)→ 全量固化 + 退回预览
                    runCatching { ic.commitText(accumulated, 1) }
                    abortStreaming()
                    Toast.makeText(this, R.string.stream_fallback_preview, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 收尾:composing 固化(用户已看到全部文本) */
    private fun finishStreamingCommit() {
        if (streamComposingActive) {
            runCatching { currentInputConnection?.finishComposingText() }
        }
        streamComposingActive = false
        streamCommitted.setLength(0)
        streamAborted = false
    }

    /** 退回预览模式:不再上屏;已写入文本保持固化(Finished 仍出预览卡) */
    private fun abortStreaming() {
        runCatching { currentInputConnection?.finishComposingText() }
        streamComposingActive = false
        streamCommitted.setLength(0)
        streamAborted = true
    }

    // ==================== 视图挂载 ====================

    override fun onCreateInputView(): View {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // M1 批次 3 修复:必须显式 MATCH_PARENT×WRAP_CONTENT——LinearLayout 默认
            // generateLayoutParams 为 WRAP_CONTENT/WRAP_CONTENT,IMMS 以 view 自身参数
            // 布局 IME 窗口,导致 ComposeView 首测前窗口塌陷(实测 840x126),软键盘不可见。
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val bottomInset = navigationBarBottomInsetDp()

        val keyboardCompose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { KeyboardScreen(state = kbState, callbacks = keyboardCallbacks(), bottomInset = bottomInset) }
        }
        val panelCompose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { PanelScreen(state = panelState, callbacks = panelCallbacks(), bottomInset = bottomInset) }
        }

        container.addView(
            panelCompose,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            keyboardCompose,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        container.setViewTreeLifecycleOwner(lifecycleOwner)
        container.setViewTreeViewModelStoreOwner(lifecycleOwner)
        container.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val root = v.rootView
                root.setViewTreeLifecycleOwner(lifecycleOwner)
                root.setViewTreeViewModelStoreOwner(lifecycleOwner)
                root.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })

        keyboardView = keyboardCompose
        panelView = panelCompose
        applyPanelVisibility()
        lifecycleOwner.dispatch(Lifecycle.Event.ON_START)
        keyboardCompose.doOnLayout { Logs.i(TAG, "keyboard compose laid out: ${keyboardCompose.width}x${keyboardCompose.height}") }
        Logs.i(TAG, "onCreateInputView done, container=${container.width}x${container.height}")
        return container
    }

    private fun navigationBarBottomInsetDp(): Dp {
        val density = resources.displayMetrics.density
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val insets = wm?.currentWindowMetrics?.windowInsets?.getInsets(WindowInsets.Type.navigationBars())
                ((insets?.bottom ?: 0) / density).dp
            } else {
                val resId = android.content.res.Resources.getSystem()
                    .getIdentifier("navigation_bar_height", "dimen", "android")
                if (resId > 0) (android.content.res.Resources.getSystem().getDimensionPixelSize(resId) / density).dp else 0.dp
            }
        } catch (e: Exception) {
            0.dp
        }
    }

    private fun applyPanelVisibility() {
        panelView?.visibility = if (panelVisible) View.VISIBLE else View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    /** 收起/打开面板不打断运行(12 号设计 §7);密码输入框禁止打开 */
    private fun showPanel(show: Boolean) {
        if (show && kbState.mode == Mode.PASSWORD) return
        panelVisible = show
        if (!show) {
            panelState.drawerTab = null // P2-1:收面板顺带收弹层
            panelState.chipSheetOpen = false
        }
        applyPanelVisibility()
        if (show) {
            panelState.configured = null
            panelState.modelLabel = null
            panelScope.launch {
                // M1 批次 3:生效模型走 ModelStore(chip P0 文案 + fc 降级提示条)
                val store = com.agentkeyboard.data.model.ModelStore(applicationContext)
                val file = store.load()
                val active = com.agentkeyboard.data.model.ModelConfigLogic.resolveDefaultModel(file)
                panelState.chipLabel = com.agentkeyboard.data.model.ModelConfigLogic.chipLabel(active)
                    ?: "未连接"
                panelState.fcDegraded = active?.effectiveFc() == false
                panelState.modelLabel = active?.display()
                panelState.configured = active != null
                panelState.kbEnabled = true
                if (panelState.chipSheetOpen) loadChipData(store, file)
                // 恢复单槽:确认条在面板未订阅事件期间出现时,重开面板可恢复(P1-2 通道)
                if (panelState.confirmation == null) {
                    panelState.confirmation = agentKit.pendingConfirmation.value
                }
            }
        }
    }

    /** P1 chip 弹层数据:默认✓ + 最近使用(top3)+ 按服务商分组(§5 P1) */
    private fun loadChipData(
        store: com.agentkeyboard.data.model.ModelStore,
        file: com.agentkeyboard.data.model.ModelConfigFile,
    ) {
        val providerName = { pid: String ->
            file.providers.firstOrNull { it.id == pid }?.name ?: "已删除"
        }
        val items = file.models.filter { !it.offlineFlag }.map { m ->
            com.agentkeyboard.ime.ChipItem(m.ref, com.agentkeyboard.data.model.ModelConfigLogic.chipLabel(m) ?: m.display(), providerName(m.providerId))
        }
        panelState.chipDefaultRef = file.defaultModelRef.ifBlank { null }
        panelState.chipRecent = file.models.filter { it.lastUsedAt > 0 }
            .sortedByDescending { it.lastUsedAt }.take(3)
            .map { m ->
                com.agentkeyboard.ime.ChipItem(
                    m.ref, com.agentkeyboard.data.model.ModelConfigLogic.chipLabel(m) ?: m.display(), providerName(m.providerId),
                )
            }
        panelState.chipGroups = file.providers.map { p ->
            com.agentkeyboard.ime.ChipGroup(
                providerName = p.name,
                items = items.filter { it.ref.startsWith("${p.id}/") },
            )
        }
    }

    // ==================== 输入会话 ====================

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val target = attribute?.let {
            InputTarget(it.packageName, it.fieldId, it.inputType, it.privateImeOptions)
        }
        if (!restarting || target != inputTarget) editorGeneration += 1
        inputTarget = target
        kbState.mode = ImeContextProvider.detect(attribute)
        if (kbState.mode == Mode.PASSWORD) {
            // 密码框 FAIL-SAFE:立刻关闭拼音并清掉组合态,不等待 onStartInputView。
            // 输入连接在两个生命周期回调之间仍可能收到按键事件,因此这里必须同步处理。
            kbState.clearPinyinComposition(disablePinyin = true)
            kbState.clipboardOpen = false
            kbState.editorOpen = false
            if (panelVisible) showPanel(false)
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        if (BuildConfig.DEBUG) {
            kbState.imeShowStartedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        lifecycleOwner.dispatch(Lifecycle.Event.ON_RESUME)
        kbState.reset()
        if (kbState.mode == Mode.PASSWORD) {
            // 与 onStartInput 双保险:某些宿主只触发 view 生命周期或重建窗口,
            // 密码框永远不得继承拼音开关/候选。
            kbState.clearPinyinComposition(disablePinyin = true)
        }
        // M3-C(audit#10)面板焦点安全:点面板内 Compose TextField 会引发输入焦点往返,
        // 对同一编辑框的 input restart(restarting=true)不再无条件收起面板——面板被误关后
        // 键盘 onText 会直接 commitText 进宿主编辑框(审计的「打字进聊天框误发」路径);
        // 真正切换编辑框(restarting=false)仍按 12 号设计 §7 重置面板。
        if (!restarting) {
            panelVisible = false
            applyPanelVisibility()
            kbState.resetQuickReplaceUndo() // V-A:新输入框旧恢复条位置失效,清掉(在途直出不受影响)
        }
        // P2(评审修正单):跨输入框定义统一行为——旧任务若仍在流式,立即固化并停止上屏,
        // 防旧任务的流式回答逐字写进新 App 的新输入框(任务本身继续执行,Finished 仍出预览卡)
        abortStreaming()
        // M1 批次 3:流式上屏开关是普通设置(无推送通道),每次输入会话开始时重读,
        // 避免设置页改动后必须重启 IME 服务才生效。
        panelScope.launch { streamToImeEnabled = settingsRepo.isStreamToIme() }
        // M3-C(audit#8):震动开关同样每次输入会话开始时重读
        panelScope.launch { KeyboardHaptics.enabled = settingsRepo.isHapticEnabled() }
        // 键盘高度设置下一次唤起输入视图生效，避免当前布局中途跳动。
        panelScope.launch { kbState.heightScale = settingsRepo.keyboardHeightScale() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // P2(评审修正单):同 onStartInputView——离开输入框即固化已有内容并停止后续上屏
        abortStreaming()
        lifecycleOwner.dispatch(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    /** 返回键(12 号设计 §10):面板浮层 → 收面板;面板未开走系统默认 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panelVisible) { // P2-1:面板未开时不拦截返回键
            if (panelState.drawerTab != null) {
                panelState.drawerTab = null
                return true
            }
            showPanel(false)
            return true // 不 requestHideSelf,键盘保持
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==================== 键盘回调 ====================

    private fun moveEditorCursor(ic: android.view.inputmethod.InputConnection, delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun keyboardCallbacks() = object : KeyboardCallbacks {
        override fun onText(text: String) {
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val committed = pendingPinyinCommit(
                    pinyinMode = true,
                    buffer = kbState.pinyinBuffer,
                    layout = kbState.layout,
                    decoder = pinyinEngine,
                )?.let(::onCommitPinyin) ?: true
                if (!committed) return
            }
            if (panelVisible) {
                panelState.input += text
            } else {
                currentInputConnection?.commitText(text, 1)
            }
        }

        override fun onCursorMove(delta: Int) {
            if (panelVisible) return
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val committed = pendingPinyinCommit(
                    pinyinMode = true,
                    buffer = kbState.pinyinBuffer,
                    layout = kbState.layout,
                    decoder = pinyinEngine,
                )?.let(::onCommitPinyin) ?: true
                if (!committed) return
            }
            val ic = currentInputConnection ?: return
            moveEditorCursor(ic, delta)
        }

        override fun onOpenClipboard() {
            if (kbState.mode == Mode.PASSWORD) return
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val committed = pendingPinyinCommit(
                    pinyinMode = true,
                    buffer = kbState.pinyinBuffer,
                    layout = kbState.layout,
                    decoder = pinyinEngine,
                )?.let(::onCommitPinyin) ?: true
                if (!committed) return
            }
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = runCatching { clipboard?.primaryClip }.getOrNull()
            if (clip != null) {
                repeat(clip.itemCount.coerceAtMost(3)) { index ->
                    val text = runCatching {
                        clip.getItemAt(index).coerceToText(this@AgentKeyboardService).toString()
                    }.getOrNull().orEmpty()
                    if (text.isNotEmpty()) clipboardHistory.record(text)
                }
            }
            kbState.clipboardItems = clipboardHistory.snapshot()
            kbState.editorOpen = false
            kbState.clipboardOpen = true
        }

        override fun onCloseClipboard() {
            kbState.clipboardOpen = false
        }

        override fun onPasteClipboard(text: String) {
            if (kbState.mode == Mode.PASSWORD) return
            val committed = if (panelVisible) {
                panelState.input += text
                true
            } else {
                currentInputConnection?.commitText(text, 1) == true
            }
            if (committed) kbState.clipboardOpen = false
        }

        override fun onClearClipboardHistory() {
            clipboardHistory.clear()
            kbState.clipboardItems = emptyList()
        }

        override fun onOpenEditor() {
            if (kbState.mode == Mode.PASSWORD || panelVisible) return
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val committed = pendingPinyinCommit(
                    pinyinMode = true,
                    buffer = kbState.pinyinBuffer,
                    layout = kbState.layout,
                    decoder = pinyinEngine,
                )?.let(::onCommitPinyin) ?: true
                if (!committed) return
            }
            kbState.clipboardOpen = false
            kbState.editorOpen = true
        }

        override fun onCloseEditor() {
            kbState.editorOpen = false
        }

        override fun onEditorAction(action: EditorToolAction) {
            if (kbState.mode == Mode.PASSWORD || panelVisible) return
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val committed = pendingPinyinCommit(
                    pinyinMode = true,
                    buffer = kbState.pinyinBuffer,
                    layout = kbState.layout,
                    decoder = pinyinEngine,
                )?.let(::onCommitPinyin) ?: true
                if (!committed) return
            }
            val ic = currentInputConnection ?: return
            when (action) {
                EditorToolAction.MOVE_LEFT -> moveEditorCursor(ic, -1)
                EditorToolAction.MOVE_RIGHT -> moveEditorCursor(ic, 1)
                EditorToolAction.MOVE_START -> moveEditorBoundary(ic, KeyEvent.KEYCODE_MOVE_HOME)
                EditorToolAction.MOVE_END -> moveEditorBoundary(ic, KeyEvent.KEYCODE_MOVE_END)
                EditorToolAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
                EditorToolAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
                EditorToolAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
                EditorToolAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            }
        }

        override fun onSpace() {
            // 拼音态:空格上屏首选;否则原样空格
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val top = learnedTopCandidate(kbState.pinyinBuffer, kbState.layout)
                onCommitPinyin(top ?: kbState.pinyinBuffer)
                return
            }
            onText(" ")
        }

        override fun onBackspace() {
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val b = kbState.pinyinBuffer.dropLast(1)
                kbState.pinyinBuffer = b
                onPinyinBufferChanged(b)
                return
            }
            if (panelVisible) {
                // M3-C(audit#10)面板焦点安全硬保证:面板可见时退格只作用于面板输入
                // (空输入 = 无操作;拼音缓冲清理由上方分支承担),永不作用到宿主编辑框,
                // 杜绝「清空后再按误删宿主文本/误触发关闭」路径。
                if (panelState.input.isNotEmpty()) {
                    panelState.input = panelState.input.dropLast(1)
                }
            } else {
                val ic = currentInputConnection ?: return
                val deletedByCodePoint = runCatching {
                    ic.deleteSurroundingTextInCodePoints(1, 0)
                }.getOrDefault(false)
                if (!deletedByCodePoint) {
                    ic.deleteSurroundingText(1, 0)
                }
            }
        }

        override fun onEnter() {
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val top = learnedTopCandidate(kbState.pinyinBuffer, kbState.layout)
                onCommitPinyin(top ?: kbState.pinyinBuffer) // 无候选回车 = 原文上屏
                return
            }
            if (panelVisible) {
                if (panelState.canUseChatComposer && !QuickWriteGuard.blocked(kbState.mode)) {
                    val field = panelState.inputFieldValue
                    val text = field.text.replaceRange(field.selection.min, field.selection.max, "\n")
                    panelState.input = text
                    panelState.inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                        text, androidx.compose.ui.text.TextRange(field.selection.min + 1),
                    )
                }
                return
            }
            val ic = currentInputConnection ?: return
            val action = EditorActionResolver.actionOrNull(currentInputEditorInfo?.imeOptions ?: 0)
            if (action != null && ic.performEditorAction(action)) return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        override fun onAskAi() {
            showPanel(true)
        }

        // V-A 直通改造:单击 = 一键直出(完全不打开面板;运行中再点 = 取消该动作)
        override fun onQuickAction(action: QuickAction) {
            if (kbState.mode == Mode.PASSWORD) return
            // 运行中再点 = 取消:同步处理,不经守卫的 DB 查询(取消路径不触碰输入框、无异步窗口)
            if (action in quickRunner.running.value) {
                quickRunner.cancel(action)
                return
            }
            panelScope.launch {
                // 源文本选取前置(null = 无可用内容,toast 后不启动;与面板路径同规则)
                val pick = pickQuickSource()
                if (pick == null) {
                    Toast.makeText(this@AgentKeyboardService, R.string.panel_quick_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // P2(评审修正单):记录点击时刻输入会话身份(帮写完成回调校验用)
                quickInsertIdentity = QuickInsertGuard.SessionIdentity(
                    editorHash = currentInputEditorInfo?.hashCode(),
                    beforeText = runCatching {
                        currentInputConnection?.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0)?.toString()
                    }.getOrNull().orEmpty(),
                )
                // P0(评审修正单,权限红线):直出也受 llm_call 首次明示约束——
                // 未明示 → 转投 startQuickAction 面板路径(确认条在面板可见呈现,复用 resolver
                // 流程;用户同意后走面板单轮,本次之后 llmDisclosed=true,后续单击直出放行);
                // 已明示 → 直出放行,并落一条 llm_call 活动日志(与 fc 探测同口径)
                directGuard.dispatch(
                    action,
                    pick.text,
                    direct = { quickRunner.onButton(action) { pick } },
                    toPanel = {
                        Logs.i(TAG, "quick action redirect to panel: llm_call disclosure pending")
                        startQuickAction(action)
                    },
                )
            }
        }

        // V-A:长按(≥400ms)= 打开问AI面板并预填该动作(用户要微调/追问的入口)
        override fun onQuickActionLongPress(action: QuickAction) {
            if (kbState.mode == Mode.PASSWORD) return
            quickRunner.cancel(action) // 直出在途转面板微调:先停直出再开面板
            startQuickAction(action)
        }

        // V-A:直出替换恢复条「↩ 恢复原文」(与面板恢复共用 restoreReplace)
        override fun onQuickRestore() {
            restoreReplace()
        }

        override fun onTogglePinyin() {
            if (kbState.mode == Mode.PASSWORD) {
                // 密码框不允许进入拼音组合态;键盘仍可作为纯英文/数字键使用。
                kbState.clearPinyinComposition(disablePinyin = true)
                return
            }
            if (!pinyinLoaded.isCompleted) return // P3:词库后台加载中,不误报降级(完成后自动可切)
            val engine = pinyinEngine
            if (engine == null) {
                // 引擎加载失败:降级英文键盘(有 toast,FR-A2 红线要求)
                kbState.pinyinMode = false
                kbState.pinyinAvailable = false
                Toast.makeText(this@AgentKeyboardService, R.string.pinyin_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val committed = pendingPinyinCommit(
                pinyinMode = kbState.pinyinMode,
                buffer = kbState.pinyinBuffer,
                layout = kbState.layout,
                decoder = engine,
            )?.let(::onCommitPinyin) ?: true
            if (!committed) return
            kbState.pinyinMode = !kbState.pinyinMode
            kbState.clearPinyinComposition()
        }

        override fun onToggleLayout() {
            val committed = pendingPinyinCommit(
                pinyinMode = kbState.pinyinMode,
                buffer = kbState.pinyinBuffer,
                layout = kbState.layout,
                decoder = pinyinEngine,
            )?.let(::onCommitPinyin) ?: true
            if (!committed) return
            kbState.layout = when (kbState.layout) {
                KeyboardLayout.QWERTY -> KeyboardLayout.T9
                KeyboardLayout.T9 -> KeyboardLayout.QWERTY
            }
            panelScope.launch { settingsRepo.setKeyboardLayout(kbState.layout.name) }
            kbState.symbolPage = false
            kbState.clearPinyinComposition()
        }

        override fun onT9Digit(digit: Char) {
            if (kbState.pinyinMode && T9Pinyin.isPinyinDigit(digit)) {
                val next = kbState.pinyinBuffer + digit
                if (next.length <= LibimePinyinEngine.MAX_BUFFER) {
                    kbState.pinyinBuffer = next
                    onPinyinBufferChanged(next)
                }
                return
            }
            // 0/1 在九宫格拼音态中作为数字使用;先提交未完成的拼音。
            if (kbState.pinyinMode && kbState.pinyinBuffer.isNotEmpty()) {
                val top = learnedTopCandidate(kbState.pinyinBuffer, KeyboardLayout.T9)
                onCommitPinyin(top ?: kbState.pinyinBuffer)
            }
            onText(digit.toString())
        }

        override fun onPinyinBufferChanged(buffer: String) {
            val engine = pinyinEngine ?: return
            val metricsEnabled = BuildConfig.DEBUG
            val requestedAt = kbState.pinyinInputRequestedAtNanos
            val source = kbState.pinyinInputMetricSource
            val sampleId = kbState.pinyinMetricSampleId
            val startedAt = if (metricsEnabled) {
                android.os.SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val baseCandidates = if (kbState.layout == KeyboardLayout.T9) {
                engine.candidatesForT9(buffer)
            } else {
                engine.candidates(buffer)
            }
            val candidates = userDictionary.rank(buffer, baseCandidates)
                .take(LibimePinyinEngine.MAX_CANDIDATES)
            val finishedAt = if (metricsEnabled) {
                android.os.SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            if (metricsEnabled && requestedAt > 0L && source != PinyinMetricSource.NONE) {
                kbState.pinyinCandidateFrameStartedAtNanos = requestedAt
                kbState.pinyinCandidateFrameMetricSource = source
                kbState.pinyinCandidateFrameMetricSampleId = sampleId
                kbState.pinyinCandidateFrameMetricToken += 1
            }
            kbState.pinyinCandidates = candidates
            kbState.pinyinCandidatePage = 0
            kbState.pinyinCandidatesExpanded = false
            if (metricsEnabled) {
                Logs.i(
                    TAG,
                    "pinyin_decode_us=${(finishedAt - startedAt) / 1_000} " +
                        "candidate_count=${candidates.size}",
                )
                if (requestedAt > 0L && source != PinyinMetricSource.NONE) {
                    val metricName = if (source == PinyinMetricSource.TOUCH) {
                        "pinyin_touch_to_decoder_ready_us"
                    } else {
                        "pinyin_debug_broadcast_to_decoder_ready_us"
                    }
                    Logs.i(
                        TAG,
                        "$metricName=${(finishedAt - requestedAt) / 1_000} " +
                            "sample_id=$sampleId decode_us=${(finishedAt - startedAt) / 1_000} " +
                            "candidate_count=${candidates.size}",
                    )
                }
            }
            kbState.pinyinInputRequestedAtNanos = 0L
            kbState.pinyinInputMetricSource = PinyinMetricSource.NONE
        }

        override fun onCommitPinyin(word: String): Boolean {
            return commitPinyin(word, learnSelection = false)
        }

        override fun onSelectPinyinCandidate(word: String): Boolean {
            return commitPinyin(word, learnSelection = true)
        }

        private fun commitPinyin(word: String, learnSelection: Boolean): Boolean {
            val metricsEnabled = BuildConfig.DEBUG
            val requestedAt = kbState.pinyinCandidatePressAtNanos
            val source = kbState.pinyinCandidateMetricSource
            val sampleId = kbState.pinyinCandidateMetricSampleId
            val committed = if (panelVisible) {
                panelState.input += word
                true
            } else {
                currentInputConnection?.commitText(word, 1) == true
            }
            if (metricsEnabled && requestedAt > 0L && source != PinyinMetricSource.NONE) {
                val committedAt = android.os.SystemClock.elapsedRealtimeNanos()
                val metricName = if (source == PinyinMetricSource.TOUCH) {
                    "pinyin_candidate_touch_to_commit_call_us"
                } else {
                    "pinyin_debug_commit_to_commit_call_us"
                }
                Logs.i(
                    TAG,
                    "$metricName=${(committedAt - requestedAt) / 1_000} " +
                        "sample_id=$sampleId committed=$committed",
                )
            }
            if (!committed) return false
            val learningKey = kbState.pinyinBuffer
            val shouldLearn = learnSelection && kbState.mode != Mode.PASSWORD &&
                kbState.pinyinMode &&
                learningKey.isNotBlank() &&
                word.isNotBlank() &&
                word != learningKey
            if (shouldLearn) {
                // 显式点选同时反馈 libime 语言模型；应用侧词典继续保留，
                // 这样新词和第三方词库候选也能在下次输入中优先出现。
                runCatching { pinyinEngine?.selectCandidate(word) }
                    .onFailure { Log.w(TAG, "libime candidate learn failed", it) }
            }
            if (shouldLearn && userDictionary.learn(learningKey, word)) {
                val snapshot = userDictionary.encode()
                panelScope.launch(Dispatchers.IO) { settingsRepo.saveUserDictionary(snapshot) }
            }
            kbState.pinyinBuffer = ""
            kbState.pinyinCandidates = emptyList()
            kbState.pinyinCandidatePage = 0
            kbState.pinyinCandidatesExpanded = false
            kbState.pinyinCandidatePressAtNanos = 0L
            kbState.pinyinCandidateMetricSource = PinyinMetricSource.NONE
            return true
        }
    }

    private fun learnedTopCandidate(buffer: String, layout: KeyboardLayout): String? {
        val engine = pinyinEngine ?: return null
        val base = if (layout == KeyboardLayout.T9) {
            engine.candidatesForT9(buffer)
        } else {
            engine.candidates(buffer)
        }
        return userDictionary.rank(buffer, base).firstOrNull()
    }

    private fun moveEditorBoundary(
        ic: android.view.inputmethod.InputConnection,
        keyCode: Int,
    ) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ==================== 面板回调 ====================

    private fun panelCallbacks() = object : PanelCallbacks {
        override fun onClosePanel() = showPanel(false)

        override fun onSend(text: String) = sendToAgent(text)

        override fun onInputValueChanged(value: androidx.compose.ui.text.input.TextFieldValue) {
            // 面板内 Compose 输入框同步到面板状态:发送走 panelState.input,不同步则输入丢失
            panelState.input = value.text
            panelState.inputFieldValue = value
        }

        override fun onInsert(message: PanelMessage.Result) {
            if (QuickWriteGuard.blocked(kbState.mode) || !message.canInsert) return
            if (message.editorGeneration == null || message.editorGeneration != editorGeneration) {
                Toast.makeText(this@AgentKeyboardService, R.string.quick_insert_dropped, Toast.LENGTH_SHORT).show()
                return
            }
            val text = com.agentkeyboard.ui.formatAnswerText(message.text).text
            if (currentInputConnection?.commitText(text, 1) != true) {
                Toast.makeText(this@AgentKeyboardService, R.string.panel_insert_editor_rejected, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this@AgentKeyboardService, R.string.panel_inserted_toast, Toast.LENGTH_SHORT).show()
            showPanel(false)
        }

        override fun onCopy(text: String) {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("agent_keyboard", text))
            Toast.makeText(this@AgentKeyboardService, R.string.panel_copied_toast, Toast.LENGTH_SHORT).show()
        }

        override fun onOpenSettings() {
            showPanel(false)
            startActivity(
                Intent(this@AgentKeyboardService, com.agentkeyboard.MainActivity::class.java)
                    .putExtra(com.agentkeyboard.MainActivity.EXTRA_OPEN, com.agentkeyboard.MainActivity.OPEN_SETTINGS)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }

        override fun onDismissCard(message: PanelMessage.Result) {
            panelState.messages.removeAll { it === message }
        }

        override fun onStop() {
            agentKit.engine.cancel() // 确认挂起中点停止 = 拒绝(cancel 传播到 resolver)
            panelState.confirmation = null
        }

        override fun onDecision(request: ConfirmationRequest, decision: ConfirmationDecision) {
            activeDecision?.complete(decision)
        }

        override fun onUndoAlways(toolId: String) {
            panelScope.launch {
                agentKit.gate.reset(toolId)
                undoAlways.clear()
                panelState.undoAlwaysToolId = null
                Toast.makeText(this@AgentKeyboardService, R.string.settings_perm_updated, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onUndoExpire() {
            undoAlways.clear()
            panelState.undoAlwaysToolId = null
        }

        override fun onOpenHistory() {
            panelState.drawerTab = if (panelState.drawerTab == null) 0 else null
            if (panelState.drawerTab != null) onLoadDrawerData()
        }

        override fun onCloseDrawer() {
            panelState.drawerTab = null
        }

        override fun onDrawerTab(tab: Int) {
            panelState.drawerTab = tab
            onLoadDrawerData()
        }

        override fun onLoadDrawerData() {
            panelScope.launch {
                val db = AgentDatabase.get(applicationContext)
                panelState.sessions = db.sessionDao()
                    .recentByTaskType(TaskType.KNOWLEDGE_ASK.name, 50)
                    .map { session ->
                    val msgs = db.messageDao().bySession(session.id)
                    val firstUser = msgs.firstOrNull { it.role == "user" }?.content.orEmpty()
                    sessionToListItem(session, PanelReducer.sessionTitle(firstUser))
                }
                panelState.activity = db.activityLogDao().recent(200).first()
            }
        }

        override fun onOpenSession(sessionId: Long) {
            if (panelState.busy) return // P2-5:运行中打开旧会话会写错会话
            panelState.drawerTab = null
            panelScope.launch {
                val db = AgentDatabase.get(applicationContext)
                val msgs = db.messageDao().bySession(sessionId)
                replaceMessages(PanelReducer.sessionToPanelMessages(msgs, panelState.modelLabel))
                activeSessionId = sessionId // 后续发送 = 继续追问(engine 复用会话 + 折叠旧上下文)
                panelState.busy = false
            }
        }

        override fun onDeleteSession(sessionId: Long) {
            if (panelState.busy) return // P2-5:运行中删除会话会与落盘竞争
            panelScope.launch {
                AgentDatabase.get(applicationContext).sessionDao().delete(sessionId)
                if (activeSessionId == sessionId) activeSessionId = null
                onLoadDrawerData()
            }
        }

        // ==== 模型 chip(P0/P1,M1 批次 3)====

        override fun onToggleChipSheet() {
            panelState.chipSheetOpen = !panelState.chipSheetOpen
            if (panelState.chipSheetOpen) {
                panelScope.launch {
                    loadChipData(
                        com.agentkeyboard.data.model.ModelStore(applicationContext),
                        com.agentkeyboard.data.model.ModelStore(applicationContext).load(),
                    )
                }
            }
        }

        override fun onSelectModel(ref: String) {
            panelScope.launch {
                val store = com.agentkeyboard.data.model.ModelStore(applicationContext)
                val file = store.load()
                val model = file.models.firstOrNull { it.ref == ref } ?: return@launch
                store.setDefaultModel(ref)
                store.markUsed(ref)
                panelState.chipDefaultRef = ref
                panelState.chipLabel = com.agentkeyboard.data.model.ModelConfigLogic.chipLabel(model)
                // M3 欠账清零:预览卡 meta 行的模型名随 chip 切换同步刷新
                panelState.modelLabel = model.display()
                panelState.fcDegraded = model.effectiveFc() == false
                Toast.makeText(
                    this@AgentKeyboardService,
                    "已切换到 ${model.display()},对之后的对话生效",
                    Toast.LENGTH_SHORT,
                ).show()
                // P1 弹层收起
                panelState.chipSheetOpen = false
                // §4.2 懒探测:UNKNOWN 模型首次选中 → 后台探测 + 记 activity_log(llm_call · auto_allow,§8 Q1)
                // P1(评审修正单):INCONCLUSIVE 连续 ≥2 次后停止自动探测(防每次选中重复烧钱)
                if (model.fcOverride < 0 &&
                    model.fcAuto == com.agentkeyboard.data.model.FcAuto.UNKNOWN &&
                    model.probeFails < 2
                ) {
                    probeAndRecord(store, file, model)
                }
                // chip 文案可能因探测改判,重载
                panelState.chipLabel = com.agentkeyboard.data.model.ModelConfigLogic.chipLabel(
                    com.agentkeyboard.data.model.ModelConfigLogic.resolveDefaultModel(store.load()),
                )
            }
        }

        override fun onOpenModelManage() {
            panelState.chipSheetOpen = false
            showPanel(false)
            startActivity(
                Intent(this@AgentKeyboardService, com.agentkeyboard.MainActivity::class.java)
                    .putExtra(com.agentkeyboard.MainActivity.EXTRA_OPEN, com.agentkeyboard.MainActivity.OPEN_MANAGE)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }

        // ==== M3 替换语义 / S10 空库卡 ====

        override fun onReplace(message: PanelMessage.Result) = performReplace(message)

        override fun onRestoreReplace() = restoreReplace()

    }

    /**
     * fc 懒探测(10 号设计 §4.2 方案 2):max_tokens=100 + 假工具;
     * YES/NO 写回 fcAuto(记「已自动探测」),失败静默保持 UNKNOWN 不改判。
     * 探测请求计入 activity_log(llm_call · auto_allow,§9③;探测无用户内容)。
     *
     * P1(评审修正单):
     * - 写回改 [ModelStore.updateModel] 局部更新——探测最长 30s,期间用户在 P7 改的
     *   fcOverride/displayName 不能被探测前快照整条覆盖;lastUsedAt 同理。
     * - INCONCLUSIVE 连续 ≥2 次后停止自动探测(probeFails 落库计数):不定结果反复烧钱,
     *   表现为每次选中都探测的"UNKNOWN 死循环"。
     */
    private suspend fun probeAndRecord(
        store: com.agentkeyboard.data.model.ModelStore,
        file: com.agentkeyboard.data.model.ModelConfigFile,
        model: com.agentkeyboard.data.model.ModelEntry,
    ) {
        val provider = file.providers.firstOrNull { it.id == model.providerId }
        val key = provider?.let { store.loadKey(it.id) }
        val db = AgentDatabase.get(applicationContext)
        val startedAt = System.currentTimeMillis()
        val result = if (provider == null || key.isNullOrBlank()) {
            com.agentkeyboard.data.model.FcProbeResult.INCONCLUSIVE
        } else {
            com.agentkeyboard.data.model.ModelService.probeFunctionCalling(provider.baseUrl, key, model.modelId)
        }
        db.activityLogDao().insert(
            com.agentkeyboard.data.db.ActivityLogEntity(
                ts = startedAt,
                toolId = "llm_call",
                outcome = "auto_allow",
                containsUserContent = "none",
                target = "模型服务",
                summary = "能力探测 · ${model.display()} · " + when (result) {
                    com.agentkeyboard.data.model.FcProbeResult.YES -> "支持工具调用"
                    com.agentkeyboard.data.model.FcProbeResult.NO -> "不支持工具调用"
                    com.agentkeyboard.data.model.FcProbeResult.INCONCLUSIVE -> "未定(保持未知)"
                },
                preview = "",
                sessionId = null,
            ),
        )
        when (result) {
            com.agentkeyboard.data.model.FcProbeResult.YES ->
                store.updateModel(model.ref) { it.copy(
                    fcAuto = com.agentkeyboard.data.model.FcAuto.VERIFIED_YES,
                    probeFails = 0,
                ) }
            com.agentkeyboard.data.model.FcProbeResult.NO ->
                store.updateModel(model.ref) { it.copy(
                    fcAuto = com.agentkeyboard.data.model.FcAuto.VERIFIED_NO,
                    probeFails = 0,
                ) }
            com.agentkeyboard.data.model.FcProbeResult.INCONCLUSIVE ->
                // 不改判,仅累计失败次数(≥2 后 onSelectModel 不再自动触发)
                store.updateModel(model.ref) { it.copy(probeFails = it.probeFails + 1) }
        }
    }

    // ==================== 发送链路(Agent 驱动) ====================

    /** 面板发送:只提交面板内显式输入的问题,不读取宿主输入框或剪贴板。 */
    private fun sendToAgent(rawText: String) {
        if (kbState.mode == Mode.PASSWORD) return // 密码模式禁止 AI 调用(FAIL-SAFE 同 showPanel)
        val text = rawText.trim()
        if (text.isEmpty() || panelState.busy) return
        val requestEditorGeneration = editorGeneration
        panelState.input = ""
        panelState.busy = true
        loggedFirstDelta = false
        pendingAction = null
        pendingSource = ""
        pendingFromSelection = false
        panelState.replaceUndo = null // 新任务起点:清上一轮替换恢复暂存
        panelState.replaceUndoHintExpired = false
        panelState.messages.add(PanelMessage.UserText(text))
        panelScope.launch {
            pendingAnswerEditorGeneration = requestEditorGeneration
            agentKit.engine.start(knowledgeAskTask(text, activeSessionId))
        }
    }

    // ==================== M3 快捷动作(12 号设计 §5) ====================

    // ==== V-A 直通路径(单击一键直出,不进面板)====

    /**
     * 源文本选取仅由用户显式触发快捷动作时进行:选区优先,否则读取光标前短文本。
     */
    private fun pickQuickSource(): QuickActionRunner.SourcePick? {
        val ic = currentInputConnection
        val selected = runCatching { ic?.getSelectedText(0)?.toString() }.getOrNull()
        val before = runCatching { ic?.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0)?.toString() }
            .getOrNull().orEmpty()
        val source = ReplaceLogic.pickSource(selected, before, CONTEXT_BEFORE_CHARS) ?: return null
        return QuickActionRunner.SourcePick(source, !selected.isNullOrBlank())
    }

    /**
     * V-A 直出单轮调用(独立小循环:一次 LLM 调用无 tools;不走 AgentKit):
     * ModelStore 取生效模型 → LlmClient.complete(SystemPrompts 单轮模板)。
     * 未配置模型抛 [ModelNotConfiguredException] → NOT_CONFIGURED 简短 toast。
     */
    private suspend fun runQuickComplete(action: QuickAction, source: String): String {
        val cfg = com.agentkeyboard.data.model.ModelStore(applicationContext).loadActiveModel()
            ?: throw ModelNotConfiguredException()
        val systemPrompt = com.agentkeyboard.agent.SystemPrompts.build(
            action.taskType,
            contextBefore = if (action == QuickAction.HELP_WRITE) source else "",
            userTextHint = source, // TRANSLATE 目标语判定(12 §5:含 CJK → 英文,否则中文)
        )
        Logs.i(TAG, "quick action start: $action sourceLen=${source.length}")
        val text = com.agentkeyboard.ai.LlmClient.complete(
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            systemPrompt = systemPrompt,
            userText = QuickActions.modelUserText(action, source),
            modelOverride = cfg.model,
        )
        Logs.i(TAG, "quick action complete: $action resultLen=${text.length}")
        return text
    }

    /**
     * V-A 直出替换(改写/翻译):**结果预览权衡——直出不预览直接落**,不进面板预览,
     * 靠原文内存暂存 + 8s 绿色恢复条兜底(「快」优先于「可控」);替换执行复用
     * [ReplaceExecutor](快照失效自动降级插入 + toast);恢复状态同步写键盘恢复条
     * 与面板恢复暂存(面板打开时卡恢复入口一致可用)。
     */
    private fun applyQuickReplace(result: String, source: String, fromSelection: Boolean) {
        // P1(评审修正单):直出在途切到密码框(onStartInput 置 PASSWORD)→ 完成回调禁止写入
        // (FAIL-SAFE 红线;与 performReplace/onQuickAction 入口守卫同款)
        if (QuickWriteGuard.blocked(kbState.mode)) {
            Logs.i(TAG, "quick write blocked: password mode, replace result dropped")
            return
        }
        val ic = currentInputConnection ?: return
        val outcome = ic.beginBatchRun {
            ReplaceExecutor.execute(ReplaceExecutor.IcOps(ic), source, result, fromSelection)
        }
        if (!outcome.applied) return
        val undo = ReplaceUndoState(oldText = source, newText = result, createdAtMs = System.currentTimeMillis())
        kbState.quickReplaceUndo = undo
        kbState.quickReplaceUndoExpired = false
        panelState.replaceUndo = undo
        panelState.replaceUndoHintExpired = false
        outcome.degradedReason?.let {
            Toast.makeText(this, R.string.panel_replace_degraded, Toast.LENGTH_SHORT).show()
        }
        Logs.i(TAG, "quick replace done: degraded=${outcome.degradedReason != null} fromSelection=$fromSelection")
    }

    /** V-A 直出帮写插入(commitText 到光标处 + ✓ toast;可经宿主撤销,无需恢复条) */
    private fun applyQuickInsert(result: String) {
        // P1(评审修正单):直出在途切到密码框 → 完成回调禁止写入(FAIL-SAFE 红线)
        if (QuickWriteGuard.blocked(kbState.mode)) {
            Logs.i(TAG, "quick write blocked: password mode, insert result dropped")
            return
        }
        val ic = currentInputConnection ?: return
        // P2(评审修正单):输入会话校验——编辑器身份/光标前文本与点击时不一致(切到另一个
        // App 聊天框、或同框继续打字/移动光标)→ 内容丢弃,不落进无关会话
        // (对照流式上屏同类保护 ComposingVerifier.USER_EDITED)
        val identity = quickInsertIdentity
        val verdict = QuickInsertGuard.verify(
            identity,
            editorHashNow = currentInputEditorInfo?.hashCode(),
            beforeNow = runCatching {
                ic.getTextBeforeCursor((identity?.beforeText?.length ?: 0) + 4, 0)?.toString()
            }.getOrNull(),
        )
        if (verdict != QuickInsertGuard.Verdict.OK) {
            Toast.makeText(this, R.string.quick_insert_dropped, Toast.LENGTH_SHORT).show()
            Logs.i(TAG, "quick insert dropped: $verdict")
            return
        }
        ic.commitText(result, 1)
        Toast.makeText(this, R.string.panel_inserted_toast, Toast.LENGTH_SHORT).show()
        Logs.i(TAG, "quick insert done: len=${result.length}")
    }

    /** V-A 失败 toast 文案(kind → 简短 strings;不外泄原始 message) */
    private fun quickErrorText(kind: String): Int = when (kind) {
        "NO_NETWORK" -> R.string.quick_err_no_network
        "BAD_KEY" -> R.string.quick_err_bad_key
        "TIMEOUT" -> R.string.quick_err_timeout
        "RATE_LIMIT" -> R.string.quick_err_rate_limit
        "BALANCE" -> R.string.quick_err_balance
        "NOT_CONFIGURED" -> R.string.quick_err_not_configured
        else -> R.string.quick_err_generic
    }

    /** batch edit 包裹 helper(替换/恢复写操作统一;异常也保证 endBatchEdit) */
    private inline fun <T> android.view.inputmethod.InputConnection.beginBatchRun(block: () -> T): T {
        runCatching { beginBatchEdit() }
        try {
            return block()
        } finally {
            runCatching { endBatchEdit() }
        }
    }

    /**
     * 快捷动作:面板开(标准档)→ 读源文本(选区优先,无选区取光标前上文)→
     * 预填用户消息(动作标签)→ 自动单轮运行(单轮模板路径,不带工具)。
     * 帮写 = 插入语义(预览主按钮「插入」);改写/翻译 = 替换语义(预览带 oldText 对照)。
     */
    private fun startQuickAction(action: QuickAction) {
        if (kbState.mode == Mode.PASSWORD) return // 密码框工具条整体禁用(键盘层已隐藏,双保险)
        if (panelState.busy) return
        showPanel(true)
        val ic = currentInputConnection
        val selected = runCatching { ic?.getSelectedText(0)?.toString() }.getOrNull() // 可能为 null(编辑器不报告选区)
        // M3 修正:实时读光标前文本(imeSnapshot 是 onStartInput 时刻快照,聚焦后打字不刷新,
        // 直接用会拿到空/旧上文);编辑器不报告时降级用快照
        val before = runCatching { ic?.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0)?.toString() }
            .getOrNull().orEmpty()
        val source = ReplaceLogic.pickSource(selected, before, CONTEXT_BEFORE_CHARS)
        if (source == null) {
            Toast.makeText(this, R.string.panel_quick_empty, Toast.LENGTH_SHORT).show()
            return
        }
        panelState.replaceUndo = null // 新任务起点:清上一轮替换恢复暂存
        panelState.replaceUndoHintExpired = false
        panelState.input = "" // 输入框就绪:帮写的补充要求作为下一轮发送(12 §5)
        panelState.busy = true
        loggedFirstDelta = false
        panelState.messages.add(
            PanelMessage.UserText(QuickActions.userDisplay(action, source), actionTag = action.tag)
        )
        pendingAction = action
        pendingSource = source
        pendingFromSelection = !selected.isNullOrBlank()
        pendingAnswerEditorGeneration = editorGeneration
        val task = Task(
            type = action.taskType,
            userText = QuickActions.modelUserText(action, source),
            contextBeforeText = if (action == QuickAction.HELP_WRITE) source else "",
            singleTurn = true, // 单轮模板路径:整轮不带工具,一次直出
            kbEnabled = false, // 快捷动作无问答检索需求;系统卡逻辑亦不适用
        )
        agentKit.engine.start(task)
    }

    /**
     * 🔁 替换执行(12 §5 替换实现路径;V-A 起与快捷动作直出共用 [ReplaceExecutor]):
     * - 有选区:重读选区与快照比对 → 一致则 commitText(标准行为=删除选区并插入);
     * - 无选区:重读光标前文本与快照比对 → 一致则按 code point 对齐删除后 commitText;
     * - 任一失效 → 降级插入 + toast 提示;
     * - 成功后内存暂存原文(仅最近一次)+ 提示条(8s),卡上保留「恢复原文」入口。
     */
    private fun performReplace(message: PanelMessage.Result) {
        if (kbState.mode == Mode.PASSWORD) return
        if (message.editorGeneration == null || message.editorGeneration != editorGeneration) {
            Toast.makeText(this, R.string.quick_insert_dropped, Toast.LENGTH_SHORT).show()
            return
        }
        val old = message.oldText ?: return
        val new = com.agentkeyboard.ui.formatAnswerText(message.text).text
        val ic = currentInputConnection ?: return
        val outcome = ic.beginBatchRun {
            ReplaceExecutor.execute(ReplaceExecutor.IcOps(ic), old, new, message.fromSelection)
        }
        if (!outcome.applied) return
        val undo = ReplaceUndoState(oldText = old, newText = new, createdAtMs = System.currentTimeMillis())
        panelState.replaceUndo = undo
        panelState.replaceUndoHintExpired = false
        // P3(评审修正单):同步刷新键盘恢复条(与 applyQuickReplace 同口径)——否则面板路径
        // 替换成功后键盘条不亮,键盘条/面板条双来源语义漂移
        kbState.quickReplaceUndo = undo
        kbState.quickReplaceUndoExpired = false
        // 卡片标记替换成功(「恢复原文」入口保留直至会话结束)
        val idx = panelState.messages.indexOfFirst { it === message }
        if (idx >= 0) panelState.messages[idx] = message.copy(replaceSucceeded = true)
        outcome.degradedReason?.let { Toast.makeText(this, R.string.panel_replace_degraded, Toast.LENGTH_SHORT).show() }
        Logs.i(TAG, "replace done: degraded=${outcome.degradedReason != null} fromSelection=${message.fromSelection}")
    }

    /** 应用内单步恢复(12 §5):对新文本位置反向替换;位置已变化 → 提示手动处理 */
    private fun restoreReplace() {
        // P1(评审修正单):密码框禁止写入(恢复同样是 commitText 写路径,FAIL-SAFE)
        if (QuickWriteGuard.blocked(kbState.mode)) {
            Logs.i(TAG, "quick write blocked: password mode, restore skipped")
            return
        }
        val undo = panelState.replaceUndo ?: return
        val ic = currentInputConnection ?: return
        val before = runCatching { ic.getTextBeforeCursor(undo.newText.length + 8, 0)?.toString() }.getOrNull()
        if (!ReplaceLogic.checkRestore(undo.newText, before)) {
            Toast.makeText(this, R.string.panel_replace_restore_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val (_, newCodePoints) = ReplaceLogic.deletionLengths(undo.newText)
        ic.beginBatchRun {
            runCatching { ic.deleteSurroundingTextInCodePoints(newCodePoints, 0) }
                .onFailure { ic.deleteSurroundingText(undo.newText.length, 0) }
            ic.commitText(undo.oldText, 1)
        }
        panelState.replaceUndo = null
        panelState.replaceUndoHintExpired = false
        kbState.resetQuickReplaceUndo() // V-A:直出恢复条(若在展示期)同步消失
        Toast.makeText(this, R.string.panel_replace_restored, Toast.LENGTH_SHORT).show()
    }

}
