package com.agentkeyboard.mcp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentkeyboard.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.mcpDataStore by preferencesDataStore(name = "agent_keyboard_mcp")

/**
 * MCP server 配置仓库(G12):DataStore Preferences。
 * URL/名称明文;Authorization 经 [SecureStore] AES-256-GCM 加密后存密文,与模型 Key 同策略。
 * 仅用户显式添加,无任何默认 server,未配置时零开销。
 */
class McpServerStore(private val context: Context) {

    private object Keys {
        val SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
    }

    suspend fun list(): List<McpServerConfig> =
        McpServerConfigJson.decode(context.mcpDataStore.data.first()[Keys.SERVERS_JSON])

    /** 添加 server;authHeaderPlain 为空串/null 表示无鉴权。返回新建配置。 */
    suspend fun add(name: String, url: String, authHeaderPlain: String?): McpServerConfig {
        require(url.startsWith("https://") || url.startsWith("http://")) { "MCP server 仅支持 http(s) URL" }
        val encrypted = authHeaderPlain
            ?.takeIf { it.isNotBlank() }
            ?.let { withContext(Dispatchers.IO) { SecureStore.encryptSecret(it) } }
        val config = McpServerConfig(
            id = "${McpServerConfigJson.slug(name)}-${UUID.randomUUID().toString().take(8)}",
            name = name.trim(),
            url = url.trim(),
            authHeaderEnc = encrypted,
        )
        val updated = list() + config
        context.mcpDataStore.edit { it[Keys.SERVERS_JSON] = McpServerConfigJson.encode(updated) }
        return config
    }

    suspend fun delete(serverId: String) {
        val updated = list().filterNot { it.id == serverId }
        context.mcpDataStore.edit { it[Keys.SERVERS_JSON] = McpServerConfigJson.encode(updated) }
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        val updated = list().map { if (it.id == serverId) it.copy(enabled = enabled) else it }
        context.mcpDataStore.edit { it[Keys.SERVERS_JSON] = McpServerConfigJson.encode(updated) }
    }

    /** 解密 Authorization(每次调用时解密,不驻留);未配置或解密失败返回 null。 */
    suspend fun decryptedAuth(config: McpServerConfig): String? {
        val enc = config.authHeaderEnc ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { SecureStore.decryptSecret(enc) }.getOrNull()
        }
    }
}
