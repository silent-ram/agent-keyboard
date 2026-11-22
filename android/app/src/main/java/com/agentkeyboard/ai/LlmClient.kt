package com.agentkeyboard.ai

import com.agentkeyboard.data.EmptyResponseException
import com.agentkeyboard.data.ModelConfig
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单轮 LLM 调用(M0 标准档,非流式)。
 * openai-java 客户端:baseUrl=用户配置,model 按 [ModelConfig.pickModel] 推断,
 * 超时 60s(由 OkHttp client timeout 承担,SocketTimeoutException 交给错误分类)。
 */
object LlmClient {

    const val TIMEOUT_SECONDS = 60L

    /** 成功返回回答文本;失败抛异常(由 ErrorClassifier 分类) */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        systemPrompt: String,
        userText: String,
        modelOverride: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl.trim())
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()
            try {
                val params = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(ModelConfig.pickModel(baseUrl, modelOverride)))
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(userText)
                    .build()
                val completion = client.chat().completions().create(params)
                val choice = completion.choices().firstOrNull()
                    ?: throw EmptyResponseException()
                val text = choice.message().content()?.orElse("").orEmpty()
                text.ifBlank { throw EmptyResponseException() }
            } finally {
                runCatching { client.close() }
            }
        }
}
