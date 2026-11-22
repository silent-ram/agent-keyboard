package com.agentkeyboard.tools

import org.json.JSONObject

/**
 * web_search:Tavily(POST https://api.tavily.com/search,Bearer Key)。
 * 输出预算:top [Budget.SEARCH_MAX_RESULTS],每条 content ≤ [Budget.SEARCH_ITEM_CONTENT_LIMIT]。
 * 未配 Key → 明确错误回喂模型(引导用户去面板内模型管理配置)。
 */
class WebSearchTool(
    private val apiKeyProvider: suspend () -> String?,
    private val transport: HttpTransport = DefaultHttpTransport,
) : Tool {

    override val id = "web_search"
    override val name = "联网搜索"
    override val isOutbound = true
    override val targetLabel = "Tavily · api.tavily.com"
    override val description =
        "Search the public web for up-to-date information. Returns the top 5 results " +
            "with title, URL and a content snippet. Use for facts you cannot know from the context."

    override val schemaJson = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"Search keywords, concise and self-contained"}
        },"required":["query"]}
    """.trimIndent()

    private val rawSchemaJson = """
        {"type":"object","properties":{
          "query":{"type":"string"}
        },"required":["query"]}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return ToolResult.error(
                "联网搜索未配置",
                ToolErrorCodes.NOT_CONFIGURED,
            )
        }
        val query = try {
            Jsons.requireString(argsJson, "query")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("web_search 参数无效:${e.message}", ToolErrorCodes.BAD_PARAMS)
        }

        val body = JSONObject()
            .put("query", query)
            .put("max_results", Budget.SEARCH_MAX_RESULTS)
            .put("include_answer", false)
            .toString()
        val response = try {
            transport.execute(
                HttpRequest(
                    url = "https://api.tavily.com/search",
                    method = "POST",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $key",
                    ),
                    body = body,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须传播,不得吞成错误结果(与 KbSearchTool 一致)
        } catch (e: java.net.SocketTimeoutException) {
            return ToolResult.error("搜索服务响应超时", ToolErrorCodes.TIMEOUT, retryable = true)
        } catch (e: Exception) {
            return ToolResult.error(
                "搜索服务无响应:${e.javaClass.simpleName}", ToolErrorCodes.NETWORK, retryable = true,
            )
        }
        if (response.status !in 200..299) {
            val (code, retryable) = httpErrorCode(response.status)
            return ToolResult.error("搜索服务返回 HTTP ${response.status}", code, retryable)
        }
        return parseResponse(response.body)
    }

    /** 解析 Tavily 响应(results[].title/url/content),应用输出预算;异常返回 error 结果 */
    internal fun parseResponse(body: String): ToolResult = runCatching {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: throw IllegalArgumentException("响应缺少 results")
        val lines = StringBuilder()
        val citations = mutableListOf<Citation>()
        val n = minOf(results.length(), Budget.SEARCH_MAX_RESULTS)
        for (i in 0 until n) {
            val item = results.getJSONObject(i)
            val title = item.optString("title").ifBlank { "(无标题)" }
            val url = item.optString("url")
            val content = Budget.searchItemContent(item.optString("content"))
            citations.add(Citation(url = url, title = title))
            lines.append("${i + 1}. $title\nURL: $url\n$content\n\n")
        }
        if (citations.isEmpty()) {
            return ToolResult.error("搜索结果为空:请换关键词重试,不要编造来源", ToolErrorCodes.EMPTY)
        }
        ToolResult.ok(raw = lines.toString().trim(), wrapped = ToolResultWrapper.wrap(lines.toString().trim()), citations = citations)
    }.getOrElse { ToolResult.error("搜索结果解析失败:${it.javaClass.simpleName}", ToolErrorCodes.BAD_RESPONSE) }

    /** 供门控派生判定与日志:实际外发的搜索词(P2-5,覆写接口默认方法) */
    override fun paramsText(argsJson: String): String = Jsons.optionalString(argsJson, "query") ?: argsJson

    /** schemaJson 对外暴露(压缩空白,保证 LLM schema 稳定) */
    val exposedSchema: String get() = rawSchemaJson
}
