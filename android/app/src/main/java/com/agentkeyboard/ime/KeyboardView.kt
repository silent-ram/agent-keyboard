package com.agentkeyboard.ime

import org.fcitx.fcitx5.android.R
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.agentkeyboard.data.KeyboardHeight
import com.agentkeyboard.ime.pinyin.LibimePinyinEngine
import com.agentkeyboard.util.Logs
import kotlinx.coroutines.flow.collect

// ==================== 视觉常量(M3-C audit#7:抽为 AppTheme light/dark 双套) ====================
private val KbBg get() = com.agentkeyboard.ui.AppTheme.colors.kbBg
private val KeyBg get() = com.agentkeyboard.ui.AppTheme.colors.keyBg
private val FnKeyBg get() = com.agentkeyboard.ui.AppTheme.colors.fnKeyBg
private val PressedKeyBg get() = com.agentkeyboard.ui.AppTheme.colors.pressedKeyBg
private val KeyText get() = com.agentkeyboard.ui.AppTheme.colors.keyText
private val PrimaryBlue get() = com.agentkeyboard.ui.AppTheme.colors.primary
private val PrimaryPressed get() = com.agentkeyboard.ui.AppTheme.colors.primaryPressed
private val ActiveToggle get() = com.agentkeyboard.ui.AppTheme.colors.activeToggle
private val OnPrimary get() = com.agentkeyboard.ui.AppTheme.colors.onPrimary
private val ChipBg get() = com.agentkeyboard.ui.AppTheme.colors.chipBg
private val OkBg get() = com.agentkeyboard.ui.AppTheme.colors.okBg
private val OkGreen get() = com.agentkeyboard.ui.AppTheme.colors.okGreen
private val BannerBg get() = com.agentkeyboard.ui.AppTheme.colors.bannerBg
private val BannerText get() = com.agentkeyboard.ui.AppTheme.colors.bannerText
private val ChipBorder get() = com.agentkeyboard.ui.AppTheme.colors.chipBorder
private val WarnOrange get() = com.agentkeyboard.ui.AppTheme.colors.warnText

/** 问AI 按钮角标(12 号设计 §7:运行中蓝脉冲 → 完成绿✓ → 失败橙⚠) */
enum class KeyboardBadge { NONE, RUNNING, DONE, FAILED }

enum class KeyboardLayout { QWERTY, T9 }

/** 显式编辑工具动作,由键盘服务转译为宿主 InputConnection 操作。 */
enum class EditorToolAction {
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_START,
    MOVE_END,
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
}

enum class PinyinMetricSource { NONE, TOUCH, DEBUG_BROADCAST }

/**
 * M3-C(audit#8)按键触感反馈开关(极低成本,HapticFeedbackConstants.KEYBOARD_TAP
 * 经 View.performHapticFeedback,无需权限):服务按 DataStore「按键震动」开关同步,
 * 默认开。
 */
internal object KeyboardHaptics {
    @Volatile
    var enabled: Boolean = true

    fun maybeTap(view: android.view.View) {
        if (enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/** 键盘侧 UI 状态(服务持有,跨输入会话可重置) */
class KeyboardUiState {
    var mode by mutableStateOf(Mode.NORMAL)
    var layout by mutableStateOf(KeyboardLayout.QWERTY)
    var heightScale by mutableStateOf(KeyboardHeight.DEFAULT_SCALE)
    var shift by mutableStateOf(false)
    var symbolPage by mutableStateOf(false)
    var askBadge by mutableStateOf(KeyboardBadge.NONE)

    /** 拼音态(M1-T5):decoder 为 null = 引擎不可用(降级英文) */
    var pinyinMode by mutableStateOf(false)
    var pinyinBuffer by mutableStateOf("")
    var pinyinCandidates by mutableStateOf<List<String>>(emptyList())
    var pinyinAvailable by mutableStateOf(false)
    var pinyinCandidatePage by mutableStateOf(0)
    var pinyinCandidatesExpanded by mutableStateOf(false)
    var pinyinInputRequestedAtNanos by mutableStateOf(0L)
    var pinyinInputMetricSource by mutableStateOf(PinyinMetricSource.NONE)
    var pinyinCandidateFrameStartedAtNanos by mutableStateOf(0L)
    var pinyinCandidateFrameMetricSource by mutableStateOf(PinyinMetricSource.NONE)
    var pinyinCandidateFrameMetricSampleId by mutableStateOf(0)
    var pinyinCandidateFrameMetricToken by mutableStateOf(0)
    var pinyinCandidatePressAtNanos by mutableStateOf(0L)
    var pinyinCandidateMetricSource by mutableStateOf(PinyinMetricSource.NONE)
    var pinyinMetricSampleId by mutableStateOf(0)
    var pinyinCandidateMetricSampleId by mutableStateOf(0)
    var imeShowStartedAtNanos by mutableStateOf(0L)

    /**
     * V-A 直通改造:转圈中的快捷动作集合(工具条按钮原地 spinner,不拉面板)。
     * 数据源为 QuickActionRunner.running,服务侧同步到此供组合读取;
     * 切输入框不清空(任务在途,完成回调以新输入框执行,快照失效自动降级)。
     */
    var quickRunning by mutableStateOf<Set<QuickAction>>(emptySet())

    /**
     * V-A 直出替换恢复暂存(仅内存,仅最近一次):面板未打开时键盘区域的
     * 绿色恢复条(8s,↩ 恢复原文)兜底;与面板内 replaceUndo 同步写入。
     */
    var quickReplaceUndo by mutableStateOf<ReplaceUndoState?>(null)
    var quickReplaceUndoExpired by mutableStateOf(false)
    var clipboardOpen by mutableStateOf(false)
    var clipboardItems by mutableStateOf<List<String>>(emptyList())
    var editorOpen by mutableStateOf(false)

    /**
     * 清理当前拼音组合态。
     *
     * 普通输入框重置时保留中英文偏好；进入密码框时由调用方传入
     * [disablePinyin]，连同拼音开关一起关闭，避免后续字母键进入缓冲区。
     */
    fun clearPinyinComposition(disablePinyin: Boolean = false) {
        if (disablePinyin) pinyinMode = false
        pinyinBuffer = ""
        pinyinCandidates = emptyList()
        pinyinCandidatePage = 0
        pinyinCandidatesExpanded = false
    }

    fun reset() {
        shift = false
        symbolPage = false
        clipboardOpen = false
        editorOpen = false
        clearPinyinComposition()
        pinyinInputRequestedAtNanos = 0L
        pinyinInputMetricSource = PinyinMetricSource.NONE
        pinyinCandidateFrameStartedAtNanos = 0L
        pinyinCandidateFrameMetricSource = PinyinMetricSource.NONE
        pinyinCandidateFrameMetricSampleId = 0
        pinyinCandidatePressAtNanos = 0L
        pinyinCandidateMetricSource = PinyinMetricSource.NONE
        pinyinMetricSampleId = 0
        pinyinCandidateMetricSampleId = 0
    }

    /** V-A:新输入框开启时清恢复条(旧位置已失效,恢复校验也会失败提示手动处理) */
    fun resetQuickReplaceUndo() {
        quickReplaceUndo = null
        quickReplaceUndoExpired = false
    }
}

private val LocalKeyboardHeightScale = compositionLocalOf { KeyboardHeight.DEFAULT_SCALE }

@Composable
private fun keyboardDp(value: Dp): Dp = value * LocalKeyboardHeightScale.current

/** 键盘对宿主服务的回调 */
interface KeyboardCallbacks {
    fun onText(text: String)
    fun onSpace()
    fun onBackspace()
    fun onEnter()
    fun onAskAi()
    /** V-A 直通改造:单击 = 一键直出(不打开面板;运行中再点 = 取消该动作) */
    fun onQuickAction(action: QuickAction)
    /** V-A:长按(≥400ms)= 打开问AI面板并预填该动作(用户要微调/追问的入口) */
    fun onQuickActionLongPress(action: QuickAction)
    /** V-A:直出替换恢复条「↩ 恢复原文」 */
    fun onQuickRestore()
    /** 拼音态切换(M1-T5;引擎不可用时由服务 toast 并保持英文) */
    fun onTogglePinyin()
    /** 拼音缓冲变化(服务刷新候选) */
    fun onPinyinBufferChanged(buffer: String)
    /** 候选词/首选上屏;返回 false 表示宿主暂未接受提交,需保留组合态 */
    fun onCommitPinyin(word: String): Boolean
    /** 用户明确点选候选词;与空格/回车自动首选区分,仅此路径触发选词学习 */
    fun onSelectPinyinCandidate(word: String): Boolean
    /** 空格键左右滑动移动宿主输入框光标,delta=-1 左移,+1 右移 */
    fun onCursorMove(delta: Int)
    /** 用户显式打开本地剪贴板面板 */
    fun onOpenClipboard()
    fun onCloseClipboard()
    fun onPasteClipboard(text: String)
    fun onClearClipboardHistory()
    /** 用户显式打开/关闭编辑工具行。 */
    fun onOpenEditor()
    fun onCloseEditor()
    fun onEditorAction(action: EditorToolAction)
    /** 26 键/9 键切换 */
    fun onToggleLayout()
    /** 九宫格数字键:拼音态下 2-9 进入候选,其余数字直接上屏 */
    fun onT9Digit(digit: Char)
}

/**
 * M0-T2 键盘主体:顶部工具条(或密码横幅)+ QWERTY 三行 + 底行。
 * [bottomInset]:导航栏高度(IME 窗口内 Compose insets 报 0,由服务侧计算传入)。
 */
@Composable
fun KeyboardScreen(state: KeyboardUiState, callbacks: KeyboardCallbacks, bottomInset: Dp) {
    com.agentkeyboard.ui.AppTheme.FollowSystemDark() // M3-C(audit#7)跟随系统深浅色
    val heightScale = KeyboardHeight.normalize(state.heightScale)
    LaunchedEffect(state.imeShowStartedAtNanos) {
        val startedAt = state.imeShowStartedAtNanos
        if (com.agentkeyboard.BuildConfig.DEBUG && startedAt > 0L) {
            val frameAt = androidx.compose.runtime.withFrameNanos {
                android.os.SystemClock.elapsedRealtimeNanos()
            }
            Logs.i(
                "KeyboardMetrics",
                "ime_show_to_first_frame_ms=${(frameAt - startedAt) / 1_000_000}",
            )
            state.imeShowStartedAtNanos = 0L
        }
    }
    LaunchedEffect(state.pinyinCandidateFrameMetricToken) {
        val token = state.pinyinCandidateFrameMetricToken
        val startedAt = state.pinyinCandidateFrameStartedAtNanos
        val source = state.pinyinCandidateFrameMetricSource
        val sampleId = state.pinyinCandidateFrameMetricSampleId
        if (
            com.agentkeyboard.BuildConfig.DEBUG &&
            token > 0 &&
            startedAt > 0L &&
            source != PinyinMetricSource.NONE
        ) {
            val frameAt = androidx.compose.runtime.withFrameNanos {
                android.os.SystemClock.elapsedRealtimeNanos()
            }
            val metricName = if (source == PinyinMetricSource.TOUCH) {
                "pinyin_touch_to_candidate_compose_frame_us"
            } else {
                "pinyin_debug_broadcast_to_candidate_compose_frame_us"
            }
            Logs.i(
                "KeyboardMetrics",
                "$metricName=${(frameAt - startedAt) / 1_000} sample_id=$sampleId",
            )
            if (token == state.pinyinCandidateFrameMetricToken) {
                state.pinyinCandidateFrameStartedAtNanos = 0L
                state.pinyinCandidateFrameMetricSource = PinyinMetricSource.NONE
            }
        }
    }
    CompositionLocalProvider(LocalKeyboardHeightScale provides heightScale) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KbBg)
                .padding(bottom = bottomInset)
                .padding(horizontal = 3.dp, vertical = keyboardDp(4.dp)),
            verticalArrangement = Arrangement.spacedBy(keyboardDp(6.dp)),
        ) {
            when (state.mode) {
                Mode.NORMAL -> {
                    QuickUndoBar(state, callbacks)
                    Toolbar(state, callbacks)
                }
                Mode.PASSWORD -> PasswordBanner()
                Mode.NULL -> Unit // 纯键盘:工具条整体隐藏
            }
            if (state.clipboardOpen) {
                ClipboardPanel(state, callbacks)
            }
            if (state.editorOpen) {
                EditorPanel(callbacks)
            }
            if (state.pinyinMode && state.pinyinBuffer.isNotEmpty()) {
                PinyinCandidateBar(state, callbacks)
            }
            if (state.clipboardOpen || state.editorOpen) {
                Unit
            } else if (state.symbolPage) {
                SymbolLayout(state, callbacks)
            } else {
                when (state.layout) {
                    KeyboardLayout.QWERTY -> LetterLayout(state, callbacks)
                    KeyboardLayout.T9 -> T9Layout(state, callbacks)
                }
            }
        }
    }
}

// ==================== 工具条 ====================

/**
 * V-A 直出替换恢复条(8s 展示期,样式沿用面板恢复条:OkBg + OkGreen + ↩ 恢复原文):
 * 直出不进面板,恢复条渲染在键盘工具条上方兜底;到期仅隐条,原文暂存保留(经宿主撤销仍可恢复)。
 */
@Composable
private fun QuickUndoBar(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    val undo = state.quickReplaceUndo ?: return
    if (state.quickReplaceUndoExpired) return
    LaunchedEffect(undo.createdAtMs) {
        delay(8000)
        state.quickReplaceUndoExpired = true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OkBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            stringResource(R.string.panel_replace_done_hint),
            fontSize = 11.sp,
            color = OkGreen,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Text(
            stringResource(R.string.panel_restore),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryBlue,
            modifier = Modifier
                .clickable { callbacks.onQuickRestore() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Toolbar(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth().height(keyboardDp(42.dp)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QuickActionChip(
            stringResource(R.string.tb_help_write),
            running = QuickAction.HELP_WRITE in state.quickRunning,
            onClick = { callbacks.onQuickAction(QuickAction.HELP_WRITE) },
            onLongClick = { callbacks.onQuickActionLongPress(QuickAction.HELP_WRITE) },
        )
        QuickActionChip(
            stringResource(R.string.tb_rewrite),
            running = QuickAction.REWRITE in state.quickRunning,
            onClick = { callbacks.onQuickAction(QuickAction.REWRITE) },
            onLongClick = { callbacks.onQuickActionLongPress(QuickAction.REWRITE) },
        )
        QuickActionChip(
            stringResource(R.string.tb_translate),
            running = QuickAction.TRANSLATE in state.quickRunning,
            onClick = { callbacks.onQuickAction(QuickAction.TRANSLATE) },
            onLongClick = { callbacks.onQuickActionLongPress(QuickAction.TRANSLATE) },
        )
        ToolChip(
            stringResource(R.string.tb_ask_ai),
            1.15f,
            primary = true,
                badge = {
                    when (state.askBadge) {
                        KeyboardBadge.RUNNING -> PulsingDot(Modifier.align(Alignment.TopEnd))
                        KeyboardBadge.DONE -> BadgeMark("✓", OkGreen, Modifier.align(Alignment.TopEnd))
                        KeyboardBadge.FAILED -> BadgeMark("⚠", WarnOrange, Modifier.align(Alignment.TopEnd))
                        KeyboardBadge.NONE -> Unit
                    }
                },
        ) { callbacks.onAskAi() }
        ClipboardToolButton(callbacks)
        EditorToolButton(callbacks)
    }
}

@Composable
private fun ClipboardToolButton(callbacks: KeyboardCallbacks) {
    val interaction = rememberInteractionSource()
    Box(
        modifier = Modifier
            .width(38.dp)
            .fillMaxHeight()
            .background(KeyBg, RoundedCornerShape(8.dp))
            .border(1.dp, ChipBorder, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null) {
                callbacks.onOpenClipboard()
            }
            .semantics { contentDescription = "打开剪贴板" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = "📋",
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun EditorToolButton(callbacks: KeyboardCallbacks) {
    val interaction = rememberInteractionSource()
    Box(
        modifier = Modifier
            .width(38.dp)
            .fillMaxHeight()
            .background(KeyBg, RoundedCornerShape(8.dp))
            .border(1.dp, ChipBorder, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null) {
                callbacks.onOpenEditor()
            }
            .semantics { contentDescription = "打开编辑工具" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = "编辑",
            fontSize = 12.sp,
            color = KeyText,
            maxLines = 1,
        )
    }
}

@Composable
private fun EditorPanel(callbacks: KeyboardCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            EditorActionButton("←", "向左移动光标") { callbacks.onEditorAction(EditorToolAction.MOVE_LEFT) }
            EditorActionButton("→", "向右移动光标") { callbacks.onEditorAction(EditorToolAction.MOVE_RIGHT) }
            EditorActionButton("首", "移动到文本开头") { callbacks.onEditorAction(EditorToolAction.MOVE_START) }
            EditorActionButton("尾", "移动到文本末尾") { callbacks.onEditorAction(EditorToolAction.MOVE_END) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            EditorActionButton("全选", "选择全部文本") { callbacks.onEditorAction(EditorToolAction.SELECT_ALL) }
            EditorActionButton("复制", "复制选中文本") { callbacks.onEditorAction(EditorToolAction.COPY) }
            EditorActionButton("剪切", "剪切选中文本") { callbacks.onEditorAction(EditorToolAction.CUT) }
            EditorActionButton("粘贴", "粘贴剪贴板内容") { callbacks.onEditorAction(EditorToolAction.PASTE) }
            EditorActionButton("×", "关闭编辑工具") { callbacks.onCloseEditor() }
        }
    }
}

@Composable
private fun RowScope.EditorActionButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(keyboardDp(40.dp))
            .background(ChipBg, RoundedCornerShape(7.dp))
            .border(1.dp, ChipBorder, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = KeyText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClipboardPanel(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text = "剪贴板",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = KeyText,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Text(
                text = "清空",
                fontSize = 11.sp,
                color = WarnOrange,
                modifier = Modifier
                    .clickable(onClick = callbacks::onClearClipboardHistory)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .semantics { contentDescription = "清空剪贴板历史" },
            )
            androidx.compose.material3.Text(
                text = "×",
                fontSize = 20.sp,
                color = BannerText,
                modifier = Modifier
                    .clickable(onClick = callbacks::onCloseClipboard)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .semantics { contentDescription = "关闭剪贴板" },
            )
        }
        if (state.clipboardItems.isEmpty()) {
            androidx.compose.material3.Text(
                text = "暂无可用记录",
                fontSize = 12.sp,
                color = BannerText,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // IME 窗口高度有限:历史记录必须在固定上限内滚动,不能把键盘整体顶出屏幕。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(state.clipboardItems, key = { it }) { text ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 38.dp, max = 64.dp)
                            .background(ChipBg, RoundedCornerShape(6.dp))
                            .clickable { callbacks.onPasteClipboard(text) }
                            .semantics { contentDescription = "粘贴剪贴板内容" },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        androidx.compose.material3.Text(
                            text = text,
                            color = KeyText,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 蓝色脉冲小圆点(alpha 呼吸动画,12 号设计 §7) */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PulsingDot(modifier: Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "askBadge")
    val alpha: Float by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "askBadgeAlpha",
    )
    Box(
        modifier
            .padding(2.dp)
            .size(9.dp)
            .alpha(alpha)
            .background(PrimaryBlue, RoundedCornerShape(50)),
    )
}

@Composable
private fun BadgeMark(mark: String, tint: Color, modifier: Modifier) {
    androidx.compose.material3.Text(
        mark,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = modifier.padding(2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ToolChip(
    label: String,
    weight: Float,
    primary: Boolean = false,
    running: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    badge: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(if (primary) PrimaryBlue else KeyBg, RoundedCornerShape(8.dp))
            .border(1.dp, if (primary) PrimaryBlue else ChipBorder, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            // V-A 直通改造:按钮原地 spinner(不拉面板);再点 = 取消
            androidx.compose.material3.CircularProgressIndicator(
                color = if (primary) OnPrimary else PrimaryBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            androidx.compose.material3.Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                color = if (primary) OnPrimary else KeyText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        badge?.invoke(this)
    }
}

/**
 * V-A 快捷动作专用 chip:单击 = 一键直出(转圈/取消由 running 态承担);
 * 长按(≥400ms,combinedClickable 系统判定,语义见 QuickActionLongPress)= 打开面板预填。
 */
@Composable
private fun RowScope.QuickActionChip(
    label: String,
    running: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ToolChip(label = label, weight = 1f, running = running, onClick = onClick, onLongClick = onLongClick)
}

/** 中/EN 切换键(M1-T5);引擎不可用时点击由服务 toast 降级 */
@Composable
private fun RowScope.PinyinModeKey(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    Key(
        label = if (state.pinyinMode) "中" else "EN",
        contentDescription = "中英文切换",
        weight = 1.3f,
        isFn = true,
        active = state.pinyinMode,
    ) { callbacks.onTogglePinyin() }
}

@Composable
private fun RowScope.LayoutSwitchKey(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    Key(
        label = if (state.layout == KeyboardLayout.QWERTY) "9" else "26",
        contentDescription = if (state.layout == KeyboardLayout.QWERTY) {
            "切换到九宫格"
        } else {
            "切换到26键"
        },
        weight = 1.1f,
        isFn = true,
    ) { callbacks.onToggleLayout() }
}

/** 拼音候选栏:收起时三列分页,展开时每页显示两行候选。 */
@Composable
private fun PinyinCandidateBar(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    val candidates = state.pinyinCandidates
    val expanded = state.pinyinCandidatesExpanded
    val pageCount = PinyinCandidatePaging.pageCount(candidates.size, expanded)
    val page = PinyinCandidatePaging.normalizePage(
        state.pinyinCandidatePage,
        candidates.size,
        expanded,
    )
    val pageCandidates = PinyinCandidatePaging.page(candidates, page, expanded)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyBg)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        androidx.compose.material3.Text(
            text = state.pinyinBuffer,
            fontSize = 13.sp,
            color = PrimaryBlue,
            fontWeight = FontWeight.SemiBold,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        if (candidates.isEmpty()) {
            // 空格与回车都上屏拼音字母原文。
            androidx.compose.material3.Text(
                stringResource(R.string.pinyin_candidate_empty_hint),
                fontSize = 11.sp,
                color = BannerText,
                modifier = Modifier.height(keyboardDp(34.dp)),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(keyboardDp(34.dp)),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CandidatePagerButton(
                    label = "‹",
                    description = stringResource(R.string.pinyin_candidate_prev_desc),
                    enabled = page > 0,
                ) { state.pinyinCandidatePage = page - 1 }
                CandidateRow(
                    candidates = pageCandidates.take(PinyinCandidatePaging.COLUMNS),
                    offset = page * if (expanded) {
                        PinyinCandidatePaging.COLUMNS * 2
                    } else {
                        PinyinCandidatePaging.COLUMNS
                    },
                    callbacks = callbacks,
                    onPressStarted = {
                        state.pinyinCandidatePressAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        state.pinyinCandidateMetricSource = PinyinMetricSource.TOUCH
                        state.pinyinCandidateMetricSampleId += 1
                    },
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Text(
                    stringResource(R.string.pinyin_candidate_page, page + 1, pageCount),
                    fontSize = 10.sp,
                    color = BannerText,
                    maxLines = 1,
                )
                CandidatePagerButton(
                    label = "›",
                    description = stringResource(R.string.pinyin_candidate_next_desc),
                    enabled = page < pageCount - 1,
                ) { state.pinyinCandidatePage = page + 1 }
                CandidatePagerButton(
                    label = if (expanded) "⌃" else "⌄",
                    description = stringResource(
                        if (expanded) {
                            R.string.pinyin_candidate_collapse_desc
                        } else {
                            R.string.pinyin_candidate_expand_desc
                        },
                    ),
                ) {
                    state.pinyinCandidatesExpanded = !expanded
                    state.pinyinCandidatePage = 0
                }
            }
            if (expanded && pageCandidates.size > PinyinCandidatePaging.COLUMNS) {
                CandidateRow(
                    candidates = pageCandidates.drop(PinyinCandidatePaging.COLUMNS),
                    offset = page * PinyinCandidatePaging.COLUMNS * 2 +
                        PinyinCandidatePaging.COLUMNS,
                    callbacks = callbacks,
                    onPressStarted = {
                        state.pinyinCandidatePressAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        state.pinyinCandidateMetricSource = PinyinMetricSource.TOUCH
                        state.pinyinCandidateMetricSampleId += 1
                    },
                    modifier = Modifier.fillMaxWidth().height(keyboardDp(34.dp)),
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidates: List<String>,
    offset: Int,
    callbacks: KeyboardCallbacks,
    onPressStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.height(keyboardDp(34.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = candidates,
            key = { index, word -> "$offset:$index:$word" },
        ) { index, word ->
            val isTopCandidate = offset + index == 0
            val interaction = rememberInteractionSource()
            val currentOnPressStarted by rememberUpdatedState(onPressStarted)
            if (com.agentkeyboard.BuildConfig.DEBUG) {
                LaunchedEffect(interaction) {
                    interaction.interactions.collect { event ->
                        if (event is PressInteraction.Press) currentOnPressStarted()
                    }
                }
            }
            Box(
                modifier = Modifier
                    .height(keyboardDp(34.dp))
                    .widthIn(min = 56.dp)
                    .background(if (isTopCandidate) ChipBg else KeyBg, RoundedCornerShape(6.dp))
                    .border(1.dp, if (isTopCandidate) PrimaryBlue else ChipBorder, RoundedCornerShape(6.dp))
                    .clickable(interactionSource = interaction, indication = null) {
                        callbacks.onSelectPinyinCandidate(word)
                    }
                    .semantics { contentDescription = "候选:$word" },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = word,
                    fontSize = 16.sp,
                    fontWeight = if (isTopCandidate) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isTopCandidate) PrimaryBlue else KeyText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .wrapContentWidth(),
                )
            }
        }
    }
}

@Composable
private fun CandidatePagerButton(
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(keyboardDp(28.dp))
            .background(if (enabled) ChipBg else KeyBg, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = label,
            fontSize = 16.sp,
            color = if (enabled) PrimaryBlue else BannerText,
        )
    }
}

@Composable
private fun PasswordBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyboardDp(42.dp))
            .background(BannerBg, RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.tb_password_banner),
            fontSize = 12.sp,
            color = BannerText,
        )
    }
}

// ==================== 字母页 ====================

@Composable
private fun LetterLayout(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    val row1 = stringResource(R.string.kb_row1).toList()
    val row2 = stringResource(R.string.kb_row2).toList()
    val row3 = stringResource(R.string.kb_row3).toList()

    NumberRow(callbacks)
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        row1.forEach { c -> LetterKey(c, state, callbacks) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        Key(
            label = stringResource(R.string.kb_key_shift_label),
            contentDescription = stringResource(R.string.kb_key_shift_desc),
            weight = 1.5f,
            isFn = true,
            active = state.shift,
        ) { state.shift = !state.shift }
        row2.forEach { c -> LetterKey(c, state, callbacks) }
        Key(
            label = stringResource(R.string.kb_key_backspace_label),
            contentDescription = stringResource(R.string.kb_key_backspace_desc),
            weight = 1.5f,
            isFn = true,
            repeatOnHold = { callbacks.onBackspace() }, // M3-C(audit#6)长按连删
        ) { callbacks.onBackspace() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        Key(
            label = stringResource(R.string.kb_key_symbols_label),
            contentDescription = stringResource(R.string.kb_key_symbols_desc),
            weight = 1.3f,
            isFn = true,
        ) { state.symbolPage = true }
        row3.forEach { c -> LetterKey(c, state, callbacks) }
        Key(
            label = stringResource(R.string.kb_key_comma_label),
            contentDescription = stringResource(R.string.kb_key_comma_desc),
            weight = 1f,
            isFn = true,
        ) { callbacks.onText(",") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        LayoutSwitchKey(state, callbacks)
        PinyinModeKey(state, callbacks)
        CursorSpaceKey(callbacks, weight = 3.1f)
        Key(
            label = stringResource(R.string.kb_key_dot_label),
            contentDescription = stringResource(R.string.kb_key_dot_desc),
            weight = 1.2f,
            isFn = true,
        ) { callbacks.onText("。") }
        Key(
            label = stringResource(R.string.kb_key_enter_label),
            contentDescription = stringResource(R.string.kb_key_enter_desc),
            weight = 1.4f,
            isFn = true,
            isPrimary = true,
        ) { callbacks.onEnter() }
    }
}

/**
 * 常驻数字快捷行。
 *
 * 数字在拼音态也直接上屏，避免验证码、金额和 URL 输入必须先切到符号页；
 * 符号页仍保留完整数字/符号布局，供需要连续符号输入的场景使用。
 */
@Composable
private fun NumberRow(callbacks: KeyboardCallbacks) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        "1234567890".forEach { digit ->
            Key(
                label = digit.toString(),
                contentDescription = "数字键$digit",
                isFn = true,
            ) { callbacks.onText(digit.toString()) }
        }
    }
}

// ==================== 九宫格页 ====================

private val T9Rows = listOf(
    listOf('1' to "", '2' to "abc", '3' to "def"),
    listOf('4' to "ghi", '5' to "jkl", '6' to "mno"),
    listOf('7' to "pqrs", '8' to "tuv", '9' to "wxyz"),
)

@Composable
private fun T9Layout(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    T9Rows.forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            row.forEach { (digit, letters) ->
                T9Key(digit, letters, state, callbacks)
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LayoutSwitchKey(state, callbacks)
        PinyinModeKey(state, callbacks)
        T9Key('0', "", state, callbacks)
        CursorSpaceKey(callbacks, weight = 2.4f)
        Key(
            label = stringResource(R.string.kb_key_dot_label),
            contentDescription = stringResource(R.string.kb_key_dot_desc),
            weight = 1.1f,
            isFn = true,
        ) { callbacks.onText("。") }
        Key(
            label = stringResource(R.string.kb_key_backspace_label),
            contentDescription = stringResource(R.string.kb_key_backspace_desc),
            weight = 1.2f,
            isFn = true,
            repeatOnHold = { callbacks.onBackspace() },
        ) { callbacks.onBackspace() }
        Key(
            label = stringResource(R.string.kb_key_enter_label),
            contentDescription = stringResource(R.string.kb_key_enter_desc),
            weight = 1.3f,
            isFn = true,
            isPrimary = true,
        ) { callbacks.onEnter() }
    }
}

@Composable
private fun RowScope.T9Key(
    digit: Char,
    letters: String,
    state: KeyboardUiState,
    callbacks: KeyboardCallbacks,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val interaction = rememberInteractionSource()
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 2.5.dp, vertical = 1.dp)
            .height(keyboardDp(46.dp))
            .background(KeyBg, RoundedCornerShape(7.dp))
            .clickable(interactionSource = interaction, indication = null) {
                KeyboardHaptics.maybeTap(view)
                callbacks.onT9Digit(digit)
            }
            .semantics { contentDescription = "数字键$digit${if (letters.isNotEmpty()) " $letters" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                text = digit.toString(),
                fontSize = 21.sp,
                color = KeyText,
            )
            if (letters.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = letters,
                    fontSize = 9.sp,
                    color = BannerText,
                )
            }
        }
    }
}

@Composable
private fun RowScope.LetterKey(c: Char, state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    val label = if (state.shift) c.uppercaseChar().toString() else c.toString()
    Key(
        label = label,
        contentDescription = label,
        onPressStarted = if (com.agentkeyboard.BuildConfig.DEBUG && state.pinyinMode) {
            {
                state.pinyinInputRequestedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
                state.pinyinInputMetricSource = PinyinMetricSource.TOUCH
                state.pinyinMetricSampleId += 1
            }
        } else {
            null
        },
    ) {
        if (state.pinyinMode && c.isLetter()) {
            val b = state.pinyinBuffer + c.lowercaseChar()
            // P3(评审修正单):buffer 上限接线 PinyinDecoder.MAX_BUFFER(原硬编码 24 两者易漂移)
            if (b.length <= LibimePinyinEngine.MAX_BUFFER) {
                state.pinyinBuffer = b
                callbacks.onPinyinBufferChanged(b)
            }
        } else {
            callbacks.onText(if (state.shift) c.uppercaseChar().toString() else c.toString())
        }
        state.shift = false
    }
}

// ==================== 符号页 ====================

@Composable
private fun SymbolLayout(state: KeyboardUiState, callbacks: KeyboardCallbacks) {
    val sym1 = stringResource(R.string.kb_sym_row1).toList()
    val sym2 = stringResource(R.string.kb_sym_row2).toList()
    val sym3 = stringResource(R.string.kb_sym_row3).toList()

    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        sym1.forEach { c -> SymbolKey(c, callbacks) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        sym2.forEach { c -> SymbolKey(c, callbacks) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        Key(
            label = stringResource(R.string.kb_key_abc_label),
            contentDescription = stringResource(R.string.kb_key_abc_desc),
            weight = 1.4f,
            isFn = true,
        ) { state.symbolPage = false }
        sym3.forEach { c -> SymbolKey(c, callbacks) }
        Key(
            label = stringResource(R.string.kb_key_backspace_label),
            contentDescription = stringResource(R.string.kb_key_backspace_desc),
            weight = 1.4f,
            isFn = true,
            repeatOnHold = { callbacks.onBackspace() }, // M3-C(audit#6)长按连删
        ) { callbacks.onBackspace() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        LayoutSwitchKey(state, callbacks)
        PinyinModeKey(state, callbacks)
        CursorSpaceKey(callbacks, weight = 3.1f)
        Key(
            label = stringResource(R.string.kb_key_dot_label),
            contentDescription = stringResource(R.string.kb_key_dot_desc),
            weight = 1.2f,
            isFn = true,
        ) { callbacks.onText("。") }
        Key(
            label = stringResource(R.string.kb_key_enter_label),
            contentDescription = stringResource(R.string.kb_key_enter_desc),
            weight = 1.4f,
            isFn = true,
            isPrimary = true,
        ) { callbacks.onEnter() }
    }
}

@Composable
private fun RowScope.SymbolKey(c: Char, callbacks: KeyboardCallbacks) {
    Key(label = c.toString(), contentDescription = c.toString()) { callbacks.onText(c.toString()) }
}

/**
 * Space keeps its normal tap behavior and also acts as a compact cursor trackpad:
 * horizontal movement emits one left/right cursor step per touch threshold.
 */
@Composable
private fun RowScope.CursorSpaceKey(
    callbacks: KeyboardCallbacks,
    weight: Float,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val thresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val currentCallbacks by rememberUpdatedState(callbacks)
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 2.5.dp, vertical = 1.dp)
            .height(keyboardDp(46.dp))
            .background(FnKeyBg, RoundedCornerShape(7.dp))
            .pointerInput(thresholdPx) {
                detectTapGestures(
                    onPress = {
                        val accumulator = CursorSwipeAccumulator(thresholdPx)
                        var moved = false
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange().x
                                if (delta == 0f) continue
                                change.consume()
                                accumulator.add(delta)?.let { direction ->
                                    moved = true
                                    KeyboardHaptics.maybeTap(view)
                                    currentCallbacks.onCursorMove(direction)
                                }
                            }
                        }
                        if (!moved) {
                            KeyboardHaptics.maybeTap(view)
                            currentCallbacks.onSpace()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = "空格键，点击输入空格，左右滑动移动光标"
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.kb_key_space_label),
            fontSize = 13.sp,
            color = KeyText,
        )
    }
}

// ==================== 通用按键 ====================

@Composable
private fun RowScope.Key(
    label: String,
    contentDescription: String,
    weight: Float = 1f,
    isFn: Boolean = false,
    active: Boolean = false,
    isPrimary: Boolean = false,
    onPressStarted: (() -> Unit)? = null,
    /** M3-C(audit#6):非 null = 按住 400ms 后以加速间隔连发(退格连删);null = 普通点击 */
    repeatOnHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val interaction = rememberInteractionSource()
    val clickPressed by interaction.collectIsPressedAsState()
    val currentOnPressStarted by rememberUpdatedState(onPressStarted)
    if (onPressStarted != null && com.agentkeyboard.BuildConfig.DEBUG) {
        LaunchedEffect(interaction) {
            interaction.interactions.collect { event ->
                if (event is PressInteraction.Press) currentOnPressStarted?.invoke()
            }
        }
    }
    var holdPressed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pressed = if (repeatOnHold != null) holdPressed else clickPressed
    val bg = when {
        isPrimary && pressed -> PrimaryPressed
        isPrimary -> PrimaryBlue
        active -> ActiveToggle
        pressed -> PressedKeyBg
        isFn -> FnKeyBg
        else -> KeyBg
    }
    val tapWithHaptic = {
        KeyboardHaptics.maybeTap(view)
        onClick()
    }
    val inputModifier = if (repeatOnHold != null) {
        Modifier.holdRepeatGesture(
            onTap = tapWithHaptic,
            onRepeat = {
                KeyboardHaptics.maybeTap(view)
                repeatOnHold()
            },
            onPressedChange = { holdPressed = it },
        )
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = tapWithHaptic)
    }
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 2.5.dp, vertical = 1.dp)
            .height(keyboardDp(46.dp))
            .background(bg, RoundedCornerShape(7.dp))
            .then(inputModifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = label,
            fontSize = if (isFn && label.length > 1) 13.sp else if (isFn) 17.sp else 21.sp,
            fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isPrimary -> OnPrimary
                active -> PrimaryBlue
                else -> KeyText
            },
        )
    }
}

/**
 * M3-C(audit#6)长按连发手势:detectTapGestures press 检测 + 协程循环:
 * 按下即触发 [onTap];按住 ≥ [RepeatDeleteTiming.HOLD_TRIGGER_MS] 后按
 * [RepeatDeleteTiming.intervalAt] 加速间隔循环 [onRepeat];抬起/取消立即停止。
 */
private fun Modifier.holdRepeatGesture(
    onTap: () -> Unit,
    onRepeat: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        // 注:awaitEachGesture 的 AwaitPointerEventScope 为 restricted suspension,
        // 不能在其中 launch;detectTapGestures 的 onPress 是普通 suspend 域,可用。
        detectTapGestures(
            onPress = {
                onPressedChange(true)
                onTap()
                // Compose UI 1.7 起 PointerInputScope 不再是 CoroutineScope,
                // 用 kotlinx coroutineScope 开子作用域跑连删循环,tryAwaitRelease 释放即取消
                kotlinx.coroutines.coroutineScope {
                    val repeatJob = launch {
                        delay(RepeatDeleteTiming.HOLD_TRIGGER_MS)
                        var i = 0
                        while (true) {
                            onRepeat()
                            delay(RepeatDeleteTiming.intervalAt(i))
                            i++
                        }
                    }
                    val released = tryAwaitRelease()
                    repeatJob.cancel()
                    onPressedChange(false)
                    // released=false = 手势被取消(滚动/父级拦截),repeat 已停,无额外处理
                }
            },
        )
    },
)

@Composable
private fun rememberInteractionSource(): MutableInteractionSource {
    return androidx.compose.runtime.remember { MutableInteractionSource() }
}
