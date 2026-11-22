package com.agentkeyboard.tools

/**
 * fetch_page:Jina Reader(GET https://r.jina.ai/{url},text/plain,无 Key 免费额度)。
 * 截断:全文 > [Budget.PAGE_FULL_LIMIT] 时,头 2000 + query 命中附近 ±1500 + 尾 500。
 */
class FetchPageTool(
    private val transport: HttpTransport = DefaultHttpTransport,
) : Tool {

    override val id = "fetch_page"
    override val name = "读取网页"
    override val isOutbound = true
    override val targetLabel = "网页"
    override val description =
        "Fetch and read the main text content of a public web page by URL. " +
            "Long pages are truncated to the parts most relevant to the query."

    override val schemaJson = """
        {"type":"object","properties":{
          "url":{"type":"string","description":"Absolute http(s) URL of the page to read"},
          "query":{"type":"string","description":"What to look for in the page; used to pick the most relevant excerpt"}
        },"required":["url"]}
    """.trimIndent()

    private val rawSchemaJson = """
        {"type":"object","properties":{
          "url":{"type":"string"},
          "query":{"type":"string"}
        },"required":["url"]}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult {
        val url = try {
            Jsons.requireString(argsJson, "url")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("fetch_page 参数无效:${e.message}")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("fetch_page 仅支持 http(s) URL")
        }
        val query = Jsons.optionalString(argsJson, "query")

        val response = try {
            transport.execute(
                HttpRequest(
                    url = "https://r.jina.ai/${encodeTargetUrl(url)}",
                    method = "GET",
                    headers = mapOf("Accept" to "text/plain"),
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须传播,不得吞成错误结果(与 KbSearchTool 一致)
        } catch (e: Exception) {
            return ToolResult.error("网页读取失败:${e.javaClass.simpleName}")
        }
        if (response.status !in 200..299) {
            return ToolResult.error("网页读取返回 HTTP ${response.status}")
        }
        val windowed = Budget.pageWindow(response.body, query)
        val citation = Citation(url = url, title = hostOf(url))
        return ToolResult.ok(
            raw = windowed,
            wrapped = ToolResultWrapper.wrap(windowed),
            citations = listOf(citation),
        )
    }

    private fun hostOf(url: String): String =
        url.substringAfter("//", "").substringBefore('/').ifBlank { url }

    /** 供门控派生判定与日志:实际访问的 URL(P2-5;query 仅用于窗口截断,不出网) */
    override fun paramsText(argsJson: String): String = Jsons.optionalString(argsJson, "url") ?: argsJson

    val exposedSchema: String get() = rawSchemaJson

    companion object {
        /**
         * 目标 URL 编码(P2-8):r.jina.ai/{url} 拼接场景下,# 会被读作 fragment 截断、
         * 空格与非 ASCII 非法——去掉 fragment;空格 → %20;非 ASCII → UTF-8 百分号编码;
         * 已是合法 %XX 的序列不重复编码。
         */
        internal fun encodeTargetUrl(url: String): String {
            val noFragment = url.substringBefore('#')
            val sb = StringBuilder(noFragment.length)
            var i = 0
            while (i < noFragment.length) {
                val c = noFragment[i]
                when {
                    c == ' ' -> { sb.append("%20"); i++ }
                    c == '%' && i + 2 < noFragment.length &&
                        noFragment[i + 1].isHexDigit() && noFragment[i + 2].isHexDigit() -> {
                        sb.append(c); i++
                    }
                    c.code > 127 -> {
                        sb.append(java.net.URLEncoder.encode(c.toString(), Charsets.UTF_8)); i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
