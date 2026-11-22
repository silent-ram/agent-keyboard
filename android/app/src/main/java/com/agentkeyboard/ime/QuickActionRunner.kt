package com.agentkeyboard.ime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 未配置模型时直出失败的类型化异常(文案「尚未配置模型」;engine 同语义 kind=NOT_CONFIGURED)。
 */
class ModelNotConfiguredException : IllegalStateException("model not configured")

/**
 * 长按阈值纯函数(V-A):单击 = 一键直出;长按(≥[THRESHOLD_MS])= 打开问AI面板并预填
 * (用户要微调/追问的入口)。UI 用 combinedClickable 系统判定(默认即 400ms),本纯函数
 * 与之对齐,单测固化语义。
 */
object QuickActionLongPress {
    const val THRESHOLD_MS = 400L

    fun shouldOpenPanel(pressedMs: Long, thresholdMs: Long = THRESHOLD_MS): Boolean =
        pressedMs >= thresholdMs
}

/**
 * 快捷动作直通执行器(V-A 批次,用户真实反馈:「改写/翻译打开会话跟问AI一样,毫无意义」):
 * 点工具条按钮 = **一键直出**,完全不打开面板。独立小循环:一次 LLM 调用(无 tools,
 * 不走 AgentKit 面板事件流),完成回调由宿主执行替换(改写/翻译)或插入(帮写)。
 *
 * 状态机(单测覆盖):Idle → Running(按钮原地转圈)→ Success(onResult)/ Failed(onError)
 * / Cancelled(再点同按钮取消)。运行约束:多动作可并行,同按钮互斥(再点即取消)。
 *
 * **结果预览权衡(直出的核心取舍)**:直出 = 不预览直接落,不进面板预览——
 * 改写/翻译靠原文内存暂存 + 8s 绿色恢复条兜底(替换快照失效由 [ReplaceExecutor] 降级插入),
 * 帮写插入可经宿主撤销;「快」优先于「可控」。
 */
class QuickActionRunner(
    private val scope: CoroutineScope,
    /** 注入的单轮调用(服务侧包装 ModelStore + LlmClient.complete;单测注入 fake) */
    private val complete: suspend (QuickAction, String) -> String,
    private val callbacks: Callbacks,
) {

    /** 单次点击的源文本选取结果(服务侧读 IC;null = 无可用内容,不启动) */
    data class SourcePick(val text: String, val fromSelection: Boolean)

    interface Callbacks {
        /** 转圈态变化(按钮 spinner 显隐;成功/失败/取消三种出路都保证复原) */
        fun onRunningChanged(action: QuickAction, running: Boolean)

        /** 完成:replaceMode 动作由宿主替换,帮写由宿主 commitText 插入 */
        fun onResult(action: QuickAction, result: String, source: String, fromSelection: Boolean)

        /** 失败:宿主 toast 简短错误(按 kind 映射,不外泄原始 message) */
        fun onError(action: QuickAction, kind: String)

        /** 被用户取消(运行中再点);按钮复原由 onRunningChanged(false) 承担 */
        fun onCancelled(action: QuickAction)
    }

    private val _running = MutableStateFlow<Set<QuickAction>>(emptySet())

    /** 运行中动作集合(KeyboardUiState.quickRunning 的数据源) */
    val running: StateFlow<Set<QuickAction>> = _running

    private val jobs = mutableMapOf<QuickAction, Job>()

    /** P3(评审修正单):jobs 完成即清理的单测观察口(生产路径不使用) */
    internal fun jobForTest(action: QuickAction): Job? = jobs[action]

    /**
     * 点击入口:[sourceProvider] 仅在启动前调用(取消路径不触碰输入框,转圈期间用户可继续打字);
     * 返回 null = 无可用内容,不启动(宿主已 toast)。
     */
    fun onButton(action: QuickAction, sourceProvider: () -> SourcePick?) {
        if (action in _running.value) {
            jobs[action]?.cancel() // 同按钮互斥:再点 = 取消该动作
            return
        }
        val pick = sourceProvider() ?: return
        callbacks.onRunningChanged(action, true)
        _running.update { it + action }
        val job = scope.launch {
            try {
                val result = complete(action, pick.text)
                callbacks.onResult(action, result, pick.text, pick.fromSelection)
            } catch (e: CancellationException) {
                callbacks.onCancelled(action)
                throw e // 保持协程取消语义;复原由 invokeOnCompletion 兜底
            } catch (e: Exception) {
                callbacks.onError(action, QuickActions.classifyQuickError(e))
            }
        }
        jobs[action] = job
        // 转圈态复原兜底:成功/失败/取消任何出路都保证执行;包括协程启动前即被
        // cancel 的边界(此时协程体不执行,finally 拿不到,handler 仍触发——含先入 map
        // 后注册 handler 之间完成的边界,invokeOnCompletion 对已完成 Job 立即执行)。
        // P3(评审修正单):完成即从 jobs 清理,防驻留(旧实现常驻最后一次 Job 引用)
        job.invokeOnCompletion {
            if (jobs[action] === job) jobs.remove(action)
            _running.update { it - action }
            callbacks.onRunningChanged(action, false)
        }
    }

    /** 外部取消(如长按同按钮转面板前的清理) */
    fun cancel(action: QuickAction) {
        jobs[action]?.cancel()
    }
}
