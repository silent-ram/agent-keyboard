package com.agentkeyboard.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户显式配置的远程 MCP server(G12)。
 * [id]:稳定 slug,进入工具 id 前缀与 PermissionGate 授权 key,创建后不变;
 * [authHeaderEnc]:Authorization 整串(如 "Bearer xxx")经 SecureStore 加密后的密文,不落明文;
 * [enabled]:false 时其工具在下一次同步后从注册表移除。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val authHeaderEnc: String? = null,
    val enabled: Boolean = true,
)

/** 配置 JSON 编解码(纯 JVM,便于单测;容错:坏条目跳过,不拖垮整个列表)。 */
object McpServerConfigJson {

    fun encode(servers: List<McpServerConfig>): String {
        val array = JSONArray()
        servers.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("authHeaderEnc", s.authHeaderEnc ?: JSONObject.NULL)
                    .put("enabled", s.enabled),
            )
        }
        return JSONObject().put("servers", array).toString()
    }

    fun decode(json: String?): List<McpServerConfig> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(json).optJSONArray("servers") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = runCatching { array.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            val url = o.optString("url")
            if (id.isBlank() || name.isBlank() || url.isBlank()) return@mapNotNull null
            McpServerConfig(
                id = id,
                name = name,
                url = url,
                authHeaderEnc = if (o.isNull("authHeaderEnc")) null else o.optString("authHeaderEnc").takeIf { it.isNotBlank() },
                enabled = o.optBoolean("enabled", true),
            )
        }
    }

    /** server id slug:[a-z0-9-],用于工具 id 与权限 key;空结果回退 "server"。 */
    fun slug(raw: String): String =
        raw.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(24)
            .ifBlank { "server" }
}
