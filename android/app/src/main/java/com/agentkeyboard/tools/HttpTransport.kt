package com.agentkeyboard.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP 请求模型(出网工具共用;可注入 fake 做单测) */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Int = 20_000,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    /** 响应头(key 统一小写;多值取首个)。G12:MCP Streamable HTTP 需要读 Mcp-Session-Id。 */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * HTTP 传输抽象:生产走 HttpURLConnection;单测注入 fake(不引 MockWebServer 依赖)。
 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

object DefaultHttpTransport : HttpTransport {

    /** 响应体上限(P2-9):2MB,超出截断(工具输出本身还有二次预算截断) */
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = request.method
            conn.connectTimeout = request.timeoutMs
            conn.readTimeout = request.timeoutMs
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val sb = StringBuilder(8 * 1024)
                val buf = CharArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    total += n
                    sb.append(buf, 0, n)
                    if (total >= MAX_BODY_BYTES) {
                        sb.append("\n…(响应超过 2MB,已截断)")
                        break
                    }
                }
                sb.toString()
            }.orEmpty()
            HttpResponse(status, body, responseHeaders(conn))
        } finally {
            runCatching { conn.disconnect() } // P2-9:确保连接释放
        }
    }

    private fun responseHeaders(conn: HttpURLConnection): Map<String, String> = runCatching {
        conn.headerFields
            .filterKeys { it != null }
            .mapValues { (_, v) -> v?.firstOrNull().orEmpty() }
            .mapKeys { (k, _) -> k.lowercase() }
    }.getOrDefault(emptyMap())
}

/** 参数/响应 JSON 解析(Android 平台 org.json;单测经 testImplementation 提供) */
object Jsons {
    /** 从工具参数 JSON 取字符串字段;缺字段/解析失败抛 IllegalArgumentException */
    fun requireString(argsJson: String, key: String): String = try {
        val obj = org.json.JSONObject(argsJson)
        obj.optString(key).trim().also { if (it.isEmpty()) throw IllegalArgumentException("缺少参数 $key") }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("参数不是合法 JSON")
    }

    fun optionalString(argsJson: String, key: String): String? = runCatching {
        org.json.JSONObject(argsJson).optString(key).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
