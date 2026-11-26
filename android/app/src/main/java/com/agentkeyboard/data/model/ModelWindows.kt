package com.agentkeyboard.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * 内置模型上下文窗口表:模型设置里的 contextWindow 为 0(未知)时,按模型 ID 推断窗口,
 * 不再让所有模型都按保守的 8k 折叠。只收录窗口公开且稳定的常见模型,查不到返回 null。
 *
 * 推断值可能过时,也可能与中转站的实际配置不符,所以它只是起点:
 * - 运行时按窗口的 70% 折叠(ContextCompactor.foldBudget);
 * - 服务端报「上下文超长」时,AgentRuntime 收紧窗口重发;报文里带真实上限的记入 [learned],
 *   之后按它收窄该模型的窗口。
 * 表偏大可以自愈,表偏小只会多折叠,不会出错。
 */
object ModelWindows {

    /** 查不到模型、也没有任何显式设置时的保守窗口 */
    const val DEFAULT_WINDOW_TOKENS = 8_000

    /** 收紧窗口的下限:再小连系统提示和工具定义都放不下 */
    const val MIN_WINDOW_TOKENS = 2_048

    /** 窗口大小的下界/上界,过滤掉表与报文里的异常值 */
    private const val WINDOW_FLOOR = 1_024
    private const val WINDOW_CEILING = 100_000_000

    /** 规则前缀 → 窗口 token 数 */
    private val RULES: List<Pair<String, Int>> = listOf(
        // OpenAI
        "gpt-4.1" to 1_000_000,
        "gpt-4o" to 128_000,
        "chatgpt-4o-latest" to 128_000,
        "gpt-4-turbo" to 128_000,
        "o1" to 200_000,
        "o3" to 200_000,
        "o4-mini" to 200_000,
        // Anthropic / Google(经 OpenAI 兼容端点)
        "claude" to 200_000,
        "gemini-2" to 1_000_000,
        // DeepSeek
        "deepseek" to 128_000,
        // 智谱
        "glm-4.6" to 200_000,
        "glm-4.5" to 128_000,
        "glm-4-plus" to 128_000,
        "glm-4-air" to 128_000,
        "glm-4-flash" to 128_000,
        "glm-z1" to 128_000,
        "codegeex-4" to 128_000,
        // 通义
        "qwen-max" to 32_000,
        "qwen-plus" to 131_000,
        "qwen3-max" to 256_000,
        // 月之暗面
        "moonshot-v1" to 128_000,
        "kimi-k2" to 256_000,
        // Meta(各托管平台的 OpenAI 兼容端点)
        "llama-3.1" to 128_000,
        "llama-3.2" to 128_000,
        "llama-3.3" to 128_000,
    )

    /** ID 末尾自带窗口的命名,如 moonshot-v1-8k / doubao-pro-32k / ernie-4.5-turbo-128k */
    private val SUFFIX_WINDOW = Regex("-(\\d{1,4})(k|m)$")

    /** 服务端报告过的真实上限(按模型,进程内有效):只用来收窄窗口 */
    private val learned = ConcurrentHashMap<String, Int>()

    /** 按模型 ID 查内置表;查不到返回 null */
    fun lookup(modelId: String): Int? = keyOf(modelId)?.let(::lookupKey)

    /**
     * 生效窗口,优先级从高到低:模型设置 → 任务指定 → 内置表 → 保守默认;
     * 服务端报告过的上限只用来收窄——它排最前,但不会比上面解析出的值更宽。
     */
    fun resolve(configuredWindow: Int, taskWindow: Int, modelId: String?): Int {
        val key = keyOf(modelId)
        val base = when {
            configuredWindow > 0 -> configuredWindow
            taskWindow > 0 -> taskWindow
            else -> key?.let(::lookupKey) ?: DEFAULT_WINDOW_TOKENS
        }
        val reported = key?.let { learned[it] } ?: return base
        return minOf(base, reported)
    }

    /** 记下服务端报告的上下文上限:同一模型只收窄不放宽 */
    fun learn(modelId: String, limitTokens: Int) {
        val key = keyOf(modelId) ?: return
        if (limitTokens !in WINDOW_FLOOR..WINDOW_CEILING) return
        learned.merge(key, limitTokens, ::minOf)
    }

    internal fun clearLearnedForTest() = learned.clear()

    private fun keyOf(modelId: String?): String? =
        modelId?.trim()?.lowercase()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    private fun lookupKey(key: String): Int? {
        // 末尾的 -8k / -128k / -1m 比前缀规则更具体,先匹配
        SUFFIX_WINDOW.find(key)?.let { m ->
            val n = m.groupValues[1].toInt()
            val tokens = if (m.groupValues[2] == "k") n * 1_000 else n * 1_000_000
            if (tokens in WINDOW_FLOOR..WINDOW_CEILING) return tokens
        }
        return RULES.firstOrNull { (prefix, _) -> key == prefix || key.startsWith("$prefix-") }?.second
    }
}
