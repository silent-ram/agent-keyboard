package com.agentkeyboard.ai

import com.agentkeyboard.data.EmptyResponseException
import com.agentkeyboard.tools.ToolSchema
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** engine 内部消息表示(role/content/toolCalls),与落盘 agent_messages 一一对应 */
data class EngineMessage(
    val role: String, // system / user / assistant / tool
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<EngineToolCall>? = null,
)

data class EngineToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * 一次或多次模型调用的 token 用量(服务端返回);用于成本展示与后续预算控制。
 * 服务端不返回 usage(OpenAI 兼容实现常见)时保持为 0,不伪造数值。
 */
data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens,
        totalTokens + other.totalTokens,
    )

    val isEmpty: Boolean get() = totalTokens == 0L && promptTokens == 0L && completionTokens == 0L

    companion object {
        val ZERO = TokenUsage()
    }
}

/** LLM 单步结果:content(最终回答)或 toolCalls(继续工具循环) */
sealed class ToolCallResult {
    /** 服务端返回的 token 用量;未返回时为 [TokenUsage.ZERO] */
    abstract val usage: TokenUsage

    /** [incompleteReason] 非空 = 回答被截断或有分片丢失,内容可展示但不完整。 */
    data class Content(
        val text: String,
        val incompleteReason: String? = null,
        override val usage: TokenUsage = TokenUsage.ZERO,
        /** 服务端 finish_reason=length(达到输出上限)/ content_filter(内容过滤),运行时据此填 StopReason */
        val truncatedByLength: Boolean = false,
        val filtered: Boolean = false,
    ) : ToolCallResult()

    data class ToolCalls(
        val content: String,
        val calls: List<EngineToolCall>,
        override val usage: TokenUsage = TokenUsage.ZERO,
    ) : ToolCallResult()
}

/**
 * 流式调用句柄(M1-T6):engine.cancel() 时调用 [cancel] 关闭底层 HTTP 流,
 * 阻塞中的 SSE 迭代立即中断(实测 <1s),取消即时生效。
 */
class StreamingHandle {
    @Volatile
    var cancelFn: (() -> Unit)? = null

    /** M1 批次 3:cancel 早于流建立(建连期)时置位,createStreaming 返回后立即中断 */
    @Volatile
    var cancelRequested: Boolean = false

    /**
     * 本次请求的超时(连接/读/总时长),由运行时按本轮预算设置;null = 用客户端默认值。
     * 经 RequestOptions 按请求下发,客户端与连接池仍按 baseUrl 复用。
     */
    @Volatile
    var requestTimeout: com.openai.core.Timeout? = null

    /** 收到首个数据块时回调一次(运行时据此统计首 token 耗时) */
    @Volatile
    var onFirstChunk: (() -> Unit)? = null

    fun cancel() {
        cancelRequested = true
        cancelFn?.invoke()
    }
}

/**
 * 多步 LLM 客户端(openai-java 4.101.0,function calling + streaming):
 * - 请求:ChatCompletionCreateParams.addTool(ChatCompletionFunctionTool) 携带 FunctionDefinition+FunctionParameters;
 * - assistant 历史:ChatCompletionAssistantMessageParam.toolCalls,经 ChatCompletionMessageToolCall.ofFunction 重建;
 * - 工具结果:ChatCompletionToolMessageParam(content + toolCallId),addMessage() 追加;
 * - 响应:choice.message().toolCalls() 为 Optional,toolCall.asFunction().id()/function().name()/arguments();
 * - 流式:createStreaming(params) → StreamResponse<ChatCompletionChunk>,chunk.choices()[0].delta();
 * - 客户端按 (baseUrl,apiKey) 缓存复用(连接池,评审顺带项)。
 */
object EngineClient {

    /** 流式两次数据块之间允许的最长空闲(读超时);不是整次请求上限。 */
    const val TIMEOUT_SECONDS = 60L
    const val CONNECT_TIMEOUT_SECONDS = 15L
    /** 整次请求上限:长回答逐块到达时不能被 60s 总时长掐断(SDK 的 timeout(Duration) 只设 request)。 */
    const val REQUEST_TIMEOUT_SECONDS = 600L

    /**
     * openai-java 的 `timeout(Duration)` 只设置整次请求总时长(request),流式长回答超过 60s
     * 会被中断并被当成超时重试,永远拿不到完整回答。这里分别设置连接、读(块间空闲)和总时长。
     */
    internal fun requestTimeout(): com.openai.core.Timeout = com.openai.core.Timeout.builder()
        .connect(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .read(Duration.ofSeconds(TIMEOUT_SECONDS))
        .write(Duration.ofSeconds(TIMEOUT_SECONDS))
        .request(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .build()

    /**
     * 模型复核请求的超时(ContentJudge):只需要回一个词,比对话请求短得多。
     * 必须落在 HTTP 层——复核里的阻塞调用不响应协程取消,只靠 withTimeout 掐不住。
     */
    internal fun judgeRequestTimeout(): com.openai.core.Timeout = com.openai.core.Timeout.builder()
        .connect(Duration.ofSeconds(10))
        .read(Duration.ofSeconds(15))
        .write(Duration.ofSeconds(15))
        .request(Duration.ofSeconds(20))
        .build()

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
    const val ROLE_TOOL = "tool"

    /**
     * 客户端按 baseUrl 缓存复用(连接池)。旧实现按 (baseUrl, apiKey) 双键缓存:
     * 用户换 Key 后旧 Key 明文随旧客户端常驻内存且无淘汰。改为单键 + Key 变更即重建,
     * 旧客户端 close 释放连接池。
     */
    private val clients = ConcurrentHashMap<String, Pair<String, OpenAIClient>>()

    private fun clientFor(baseUrl: String, apiKey: String): OpenAIClient {
        val url = baseUrl.trim()
        val key = apiKey.trim()
        clients[url]?.let { (cachedKey, client) ->
            if (cachedKey == key) return client
            runCatching { client.close() } // Key 已变更:关闭旧客户端,旧凭据不驻留
        }
        val client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
            .baseUrl(url)
            .apiKey(key)
            .timeout(requestTimeout())
            // SDK 默认自带 2 次重试,与 AgentRuntime 的退避叠加后一步最多 12 次请求;重试只留一层
            .maxRetries(0)
            .build()
        clients[url] = key to client
        return client
    }

    private fun buildParams(
        model: String,
        messages: List<EngineMessage>,
        tools: List<ToolSchema>,
        stream: Boolean = false,
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(com.openai.models.ChatModel.of(model))
        messages.forEach { builder.addMessage(toParam(it)) }
        if (tools.isNotEmpty()) tools.forEach { builder.addTool(toFunctionTool(it)) }
        if (stream) {
            // 流式下服务端只在 include_usage=true 时于末尾帧返回 usage(token-trace #1)
            builder.streamOptions(
                com.openai.models.chat.completions.ChatCompletionStreamOptions.builder()
                    .includeUsage(true)
                    .build()
            )
        }
        return builder.build()
    }

    suspend fun completeWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<EngineMessage>,
        tools: List<ToolSchema>,
    ): ToolCallResult = withContext(Dispatchers.IO) {
        val client = clientFor(baseUrl, apiKey)
        try {
            val completion = client.chat().completions().create(buildParams(model, messages, tools))
            parseCompletion(completion)
        } finally {
            // 客户端缓存复用,不 close(连接池)
        }
    }

    /**
     * 流式版(M1-T6):逐 delta 经 [onDelta] 回调**累积**文本;tool_calls 增量聚合。
     * [handle].cancel() 关闭底层流 → 阻塞迭代抛异常 → 取消即时生效。
     */
    suspend fun streamWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<EngineMessage>,
        tools: List<ToolSchema>,
        handle: StreamingHandle,
        onDelta: suspend (String) -> Unit,
    ): ToolCallResult = withContext(Dispatchers.IO) {
        val client = clientFor(baseUrl, apiKey)
        // M1 批次 3:每次调用开始清残留取消标志(单例 handle 跨任务复用,标志不清会让新任务启动即被杀)
        handle.cancelRequested = false
        val options = handle.requestTimeout
            ?.let { com.openai.core.RequestOptions.builder().timeout(it).build() }
            ?: com.openai.core.RequestOptions.none()
        val response: StreamResponse<ChatCompletionChunk> =
            client.chat().completions().createStreaming(
                buildParams(model, messages, tools, stream = true),
                options,
            )
        handle.cancelFn = { runCatching { response.close() } }
        if (handle.cancelRequested) {
            // cancel 先于流建立到达(建连期取消):流一旦建立立即关闭并中止
            runCatching { response.close() }
            throw kotlinx.coroutines.CancellationException("engine cancelled while connecting")
        }
        try {
            val agg = StreamAggregator()
            val iterator = response.stream().iterator()
            var seenChunk = false
            while (iterator.hasNext()) {
                if (!seenChunk) {
                    seenChunk = true
                    handle.onFirstChunk?.invoke()
                }
                // P3(评审修正单):per-chunk 降级——部分服务端流不发 tool_calls.index
                // (openai-java required accessor/反序列化抛异常),跳过该 chunk 继续读,
                // 而不是让整个流失败(content 在坏 chunk 内的部分一并丢弃属可接受降级)
                val applied = runCatching { agg.apply(iterator.next()) }
                if (applied.isFailure) {
                    agg.markDropped() // 计数:有丢失时结果标为不完整,不悄悄当作完整回答
                    continue
                }
                val (accumulated, hasNew) = applied.getOrThrow()
                if (hasNew) onDelta(accumulated) // 累积文本回调(面板逐字/上屏)
            }
            agg.result()
        } finally {
            handle.cancelFn = null
            runCatching { response.close() }
        }
    }

    private fun parseCompletion(completion: ChatCompletion): ToolCallResult {
        val choice = completion.choices().firstOrNull() ?: throw EmptyResponseException()
        val message = choice.message()
        val toolCalls = message.toolCalls().orElse(emptyList())
        return if (toolCalls.isNotEmpty() || choice.finishReason() == ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
            if (toolCalls.isEmpty()) throw EmptyResponseException()
            val calls = toolCalls.filter { it.isFunction() }.map { tc ->
                val fn = tc.asFunction()
                EngineToolCall(
                    id = fn.id(),
                    name = fn.function().name(),
                    argumentsJson = fn.function().arguments(),
                )
            }
            if (calls.isEmpty()) throw EmptyResponseException()
            ToolCallResult.ToolCalls(
                content = message.content()?.orElse("").orEmpty(),
                calls = calls,
                usage = completion.usage().orElse(null)?.let {
                    TokenUsage(it.promptTokens(), it.completionTokens(), it.totalTokens())
                } ?: TokenUsage.ZERO,
            )
        } else {
            val text = message.content()?.orElse("").orEmpty()
            text.ifBlank { throw EmptyResponseException() }
            val reason = choice.finishReason()
            val truncatedByLength = reason == ChatCompletion.Choice.FinishReason.LENGTH
            val filtered = reason == ChatCompletion.Choice.FinishReason.CONTENT_FILTER
            ToolCallResult.Content(
                text,
                incompleteReason(truncatedByLength = truncatedByLength, filtered = filtered, droppedChunks = 0),
                usage = completion.usage().orElse(null)?.let {
                    TokenUsage(it.promptTokens(), it.completionTokens(), it.totalTokens())
                } ?: TokenUsage.ZERO,
                truncatedByLength = truncatedByLength,
                filtered = filtered,
            )
        }
    }

    /**
     * 流式 chunk 聚合器(P1 单测对象):content 增量拼接 + tool_calls 分片聚合(index 对齐)。
     * [apply] 返回 (累积content, 本chunk是否有新content);[result] 输出最终 ToolCallResult。
     */
    class StreamAggregator {
        private val content = StringBuilder()
        private val calls = LinkedHashMap<String, Agg>()
        private var finishToolCalls = false
        private var finishReason: ChatCompletionChunk.Choice.FinishReason? = null
        private var droppedChunks = 0
        private var usage = TokenUsage.ZERO

        /** 记录一个无法解析而被跳过的分片。 */
        fun markDropped() {
            droppedChunks++
        }

        private data class Agg(val id: StringBuilder, val name: StringBuilder, val arguments: StringBuilder)

        fun apply(chunk: ChatCompletionChunk): Pair<String, Boolean> {
            // usage 只在末尾帧(需 stream_options.include_usage)或非流式响应中出现
            chunk.usage().orElse(null)?.let { u ->
                usage = TokenUsage(u.promptTokens(), u.completionTokens(), u.totalTokens())
            }
            var hasContent = false
            val choice = chunk.choices().firstOrNull()
            if (choice != null) {
                val delta = choice.delta()
                delta.content()?.orElse(null)?.let { piece ->
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        hasContent = true
                    }
                }
                delta.toolCalls().orElse(null)?.forEach { tc ->
                    finishToolCalls = true
                    val agg = calls.getOrPut(tc.index().toString()) {
                        Agg(StringBuilder(), StringBuilder(), StringBuilder())
                    }
                    tc.id()?.orElse(null)?.let { if (agg.id.isEmpty()) agg.id.append(it) }
                    val fn: com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall.Function? =
                        tc.function()?.orElse(null)
                    if (fn != null) {
                        val fname: String? = fn.name().orElse(null)
                        if (fname != null && agg.name.isEmpty()) agg.name.append(fname)
                        val fargs: String? = fn.arguments().orElse(null)
                        if (fargs != null) agg.arguments.append(fargs)
                    }
                }
                // chunk 的 finishReason 是 Optional<ChatCompletionChunk.Choice.FinishReason>;旧代码与
                // ChatCompletion.Choice.FinishReason 比较,类型不同永远为假,长度截断从未被识别
                choice.finishReason().orElse(null)?.let { reason ->
                    finishReason = reason
                    if (reason == ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS) finishToolCalls = true
                }
            }
            return content.toString() to hasContent
        }

        fun result(): ToolCallResult {
            if (calls.isNotEmpty() && finishToolCalls) {
                val list = calls.entries.map { (k, v) ->
                    EngineToolCall(
                        id = v.id.toString().ifBlank { k },
                        name = v.name.toString(),
                        argumentsJson = v.arguments.toString(),
                    )
                }
                return ToolCallResult.ToolCalls(content = content.toString(), calls = list, usage = usage)
            }
            val text = content.toString()
            if (text.isBlank()) throw EmptyResponseException()
            val truncatedByLength = finishReason == ChatCompletionChunk.Choice.FinishReason.LENGTH
            val filtered = finishReason == ChatCompletionChunk.Choice.FinishReason.CONTENT_FILTER
            return ToolCallResult.Content(
                text,
                incompleteReason(
                    truncatedByLength = truncatedByLength,
                    filtered = filtered,
                    droppedChunks = droppedChunks,
                ),
                usage = usage,
                truncatedByLength = truncatedByLength,
                filtered = filtered,
            )
        }
    }

    /** 回答不完整的用户可见原因;完整时返回 null。流式与非流式共用同一口径。 */
    internal fun incompleteReason(truncatedByLength: Boolean, filtered: Boolean, droppedChunks: Int): String? = when {
        truncatedByLength -> "回答达到模型输出长度上限，内容可能不完整"
        filtered -> "回答被模型服务的内容过滤中断，内容可能不完整"
        droppedChunks > 0 -> "有 $droppedChunks 段回答数据无法解析，内容可能不完整"
        else -> null
    }

    private fun toParam(m: EngineMessage): ChatCompletionMessageParam = when (m.role) {
        ROLE_SYSTEM -> ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder().content(m.content).build()
        )
        ROLE_USER -> ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(m.content).build()
        )
        ROLE_ASSISTANT -> {
            val ab = ChatCompletionAssistantMessageParam.builder()
            if (m.content.isNotEmpty()) ab.content(m.content)
            m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                ab.toolCalls(calls.map { c ->
                    ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(c.id)
                            .function(
                                ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(c.name)
                                    .arguments(c.argumentsJson)
                                    .build()
                            )
                            .build()
                    )
                })
            }
            ChatCompletionMessageParam.ofAssistant(ab.build())
        }
        ROLE_TOOL -> ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .content(m.content)
                .toolCallId(m.toolCallId ?: "")
                .build()
        )
        else -> ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(m.content).build()
        )
    }

    /** schema JSON → FunctionParameters(Android 平台 org.json 无 toMap,手动转 JsonValue 兼容类型) */
    private fun toParameters(schemaJson: String): FunctionParameters {
        val obj = JSONObject(schemaJson)
        val props = obj.optJSONObject("properties")
        val propsMap = if (props == null) {
            emptyMap<String, Any?>()
        } else {
            val m = linkedMapOf<String, Any?>()
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = jsonFriendly(props.get(k))
            }
            m
        }
        val required = obj.optJSONArray("required")?.let { arr -> List(arr.length()) { jsonFriendly(arr.get(it)) } }
        val b = FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(propsMap))
        if (!required.isNullOrEmpty()) b.putAdditionalProperty("required", JsonValue.from(required))
        return b.build()
    }

    private fun jsonFriendly(any: Any?): Any? = when (any) {
        is JSONObject -> {
            val m = linkedMapOf<String, Any?>()
            val keys = any.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = jsonFriendly(any.get(k))
            }
            m
        }
        is org.json.JSONArray -> List(any.length()) { jsonFriendly(any.get(it)) }
        else -> any
    }

    private fun toFunctionTool(schema: ToolSchema): com.openai.models.chat.completions.ChatCompletionFunctionTool =
        com.openai.models.chat.completions.ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(schema.id)
                    .description(schema.description)
                    .parameters(toParameters(schema.schemaJson))
                    .build()
            )
            .build()
}
