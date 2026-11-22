package com.agentkeyboard.data.model

import com.agentkeyboard.data.ErrorClassifier
import com.agentkeyboard.tools.DefaultHttpTransport
import com.agentkeyboard.tools.HttpRequest
import com.agentkeyboard.tools.HttpTransport
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 拉取模型列表结果(§4.1 验证分支:错误五态经 [errorCode] 表达) */
sealed class FetchModelsResult {
    data class Ok(val models: List<String>) : FetchModelsResult()

    /** [errorCode] ∈ URL_INVALID/NETWORK/AUTH/MODELS_EMPTY/PATH_404(§3.1) */
    data class Err(val errorCode: String, val httpStatus: Int? = null) : FetchModelsResult()
}

/** fc 懒探测结果(§4.2:失败静默保持 UNKNOWN,不改判) */
enum class FcProbeResult { YES, NO, INCONCLUSIVE }

/**
 * 模型服务网络操作(10 号设计 §4.1/§4.2,M1 批次 3):
 * - 拉取模型:原始 GET {base}/models(HttpURLConnection 容错解析,不依赖 openai-java
 *   的 Model.ownedBy 必填字段——中转站响应常缺省);
 * - 能力探测:max_tokens=100 + 假工具 get_weather(city);tool_calls→YES / 400→NO /
 *   超时·5xx·其他→INCONCLUSIVE(维持 UNKNOWN 不改判,§8 Q1:静默不弹窗);
 * - 两个操作都由调用方记 activity_log(探测记 llm_call · auto_allow,§9③)。
 */
object ModelService {

    const val FETCH_TIMEOUT_MS = 15_000 // §4.1:15s 超时
    const val PROBE_TIMEOUT_SECONDS = 30L

    /** 拉取 /models;[transport] 可注入 fake 单测 */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        transport: HttpTransport = DefaultHttpTransport,
    ): FetchModelsResult {
        val url = baseUrl.trim().trimEnd('/')
        if (!com.agentkeyboard.data.ModelConfig.validateBaseUrl(url)) {
            return FetchModelsResult.Err(ProviderErrorCodes.URL_INVALID)
        }
        return try {
            val resp = transport.execute(
                HttpRequest(
                    url = "$url/models",
                    headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                    timeoutMs = FETCH_TIMEOUT_MS,
                ),
            )
            when {
                resp.status in 200..299 -> {
                    val models = ModelConfigLogic.parseModelList(resp.body)
                    if (models.isEmpty()) FetchModelsResult.Err(ProviderErrorCodes.MODELS_EMPTY, resp.status)
                    else FetchModelsResult.Ok(models)
                }
                resp.status == 401 || resp.status == 403 -> FetchModelsResult.Err(ProviderErrorCodes.AUTH, resp.status)
                resp.status == 404 -> FetchModelsResult.Err(ProviderErrorCodes.PATH_404, resp.status)
                else -> FetchModelsResult.Err(ProviderErrorCodes.NETWORK, resp.status)
            }
        } catch (e: Exception) {
            FetchModelsResult.Err(ModelConfigLogic.classifyFetchError(url, null, ErrorClassifier.isTimedOut(e), ErrorClassifier.isConnectivityFailure(e)))
        }
    }

    /**
     * fc 懒探测(§4.2):「请调用工具查询北京今天的天气」+ 假工具 get_weather(city),
     * max_tokens=100。返回含 tool_calls → YES;HTTP 400(参数不支持)→ NO;其余 INCONCLUSIVE。
     * 探测结果由调用方写回 fcAuto 并记 activity_log。
     *
     * P1(评审修正单):全程必须在 Dispatchers.IO——探测常从主线程协程触发,
     * 直接网络调用会抛 NetworkOnMainThreadException 被 catch(Exception) 吞成
     * INCONCLUSIVE,表现为探测永远 UNKNOWN + 每次选中重复探测(100% 失效)。
     */
    suspend fun probeFunctionCalling(
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): FcProbeResult = withContext(Dispatchers.IO) {
        val client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl.trim())
            .apiKey(apiKey.trim())
            .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
            .build()
        try {
            val fakeTool = com.openai.models.chat.completions.ChatCompletionFunctionTool.builder()
                .function(
                    com.openai.models.FunctionDefinition.builder()
                        .name("get_weather")
                        .description("查询指定城市今天的天气")
                        .parameters(fakeWeatherSchema())
                        .build()
                )
                .build()
            val params = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                .model(com.openai.models.ChatModel.of(modelId))
                .maxTokens(100L)
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionMessageParam.ofUser(
                        com.openai.models.chat.completions.ChatCompletionUserMessageParam.builder()
                            .content("请调用工具查询北京今天的天气")
                            .build()
                    )
                )
                .addTool(fakeTool)
                .build()
            val completion = client.chat().completions().create(params)
            val message = completion.choices().firstOrNull()?.message()
            val toolCalls = message?.toolCalls()?.orElse(emptyList()) ?: emptyList()
            if (toolCalls.isNotEmpty()) FcProbeResult.YES else FcProbeResult.INCONCLUSIVE
        } catch (e: com.openai.errors.OpenAIServiceException) {
            if (e.statusCode() == 400) FcProbeResult.NO else FcProbeResult.INCONCLUSIVE
        } catch (e: Exception) {
            FcProbeResult.INCONCLUSIVE
        } finally {
            runCatching { client.close() }
        }
    }

    private fun fakeWeatherSchema(): com.openai.models.FunctionParameters =
        com.openai.models.FunctionParameters.builder()
            .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                com.openai.core.JsonValue.from(mapOf("city" to mapOf("type" to "string", "description" to "城市名"))),
            )
            .putAdditionalProperty("required", com.openai.core.JsonValue.from(listOf("city")))
            .build()
}
