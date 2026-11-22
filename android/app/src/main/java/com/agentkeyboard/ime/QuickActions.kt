package com.agentkeyboard.ime

import com.agentkeyboard.agent.TaskType
import com.agentkeyboard.data.ErrorClassifier

/**
 * 工具条快捷动作(12 号设计 §5 三动作语义表)。
 * [tag] 为面板消息流的前缀小标签;[taskType] 决定单轮 system 提示词模板。
 */
enum class QuickAction(val tag: String, val taskType: TaskType) {
    HELP_WRITE("✨帮写", TaskType.HELP_WRITE),
    REWRITE("🔁改写", TaskType.REWRITE),
    TRANSLATE("🌐翻译", TaskType.TRANSLATE);

    /** 改写/翻译为替换语义(预览主按钮「🔁 替换」);帮写为插入语义 */
    val replaceMode: Boolean get() = this != HELP_WRITE
}

/**
 * 快捷动作的消息构建纯函数(单测覆盖:三动作的消息流映射与 prompt 构建)。
 */
object QuickActions {

    /** 用户气泡原文摘要:去换行取前 32 字;空则占位 */
    fun summarize(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return flat.take(32).ifEmpty { "(空)" }
    }

    /**
     * 面板用户气泡显示文本(12 §3:动作标签由 [QuickAction.tag] 渲染,正文 = 原文摘要)。
     * 帮写第一轮无 brief:正文固定话术(上文已在 system prompt 中)。
     */
    fun userDisplay(action: QuickAction, source: String): String = when (action) {
        QuickAction.HELP_WRITE -> "基于上文生成可直接发送的回复"
        else -> summarize(source)
    }

    /**
     * 发给模型的 userText:
     * - 帮写:固定话术(上文经 Task.contextBeforeText 进 system prompt);
     * - 改写/翻译:源文本本身(选区或光标前上文),指令在 system prompt。
     */
    fun modelUserText(action: QuickAction, source: String): String = when (action) {
        QuickAction.HELP_WRITE -> "请基于上文帮我写一段可直接发送的回复"
        else -> source
    }

    /**
     * V-A 直出失败分类(纯函数,单测覆盖):kind 字符串与 engine 的 AgentError.kind 同口径,
     * UI 按 kind 取简短 toast 文案(不外泄原始 message)。
     * NOT_CONFIGURED = 未配置模型([ModelNotConfiguredException]);EMPTY = 空响应;
     * 其余走 [ErrorClassifier](BAD_KEY/RATE_LIMIT/BALANCE/TIMEOUT/NO_NETWORK/OTHER)。
     */
    fun classifyQuickError(e: Throwable): String = when {
        e is ModelNotConfiguredException -> "NOT_CONFIGURED"
        ErrorClassifier.isEmptyResponse(e) -> "EMPTY"
        else -> ErrorClassifier.classify(
            statusCode = ErrorClassifier.extractStatusCode(e),
            connectivityFailure = ErrorClassifier.isConnectivityFailure(e),
            timedOut = ErrorClassifier.isTimedOut(e),
        ).name
    }
}
