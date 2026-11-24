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
        require(McpServerConfigJson.isValidServerUrl(url)) { "MCP server 地址无效" }
        require(name.isNotBlank()) { "MCP server 名称不能为空" }
        val encrypted = authHeaderPlain
            ?.takeIf { it.isNotBlank() }
            ?.let { withContext(Dispatchers.IO) { SecureStore.encryptSecret(it) } }
        val config = McpServerConfig(
            id = "${McpServerConfigJson.slug(name)}-${UUID.randomUUID().toString().take(8)}",
            name = name.trim(),
            url = url.trim(),
            authHeaderEnc = encrypted,
        )
        update { it + config }
        return config
    }

    suspend fun delete(serverId: String) {
        update { servers -> servers.filterNot { it.id == serverId } }
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        update { servers -> servers.map { if (it.id == serverId) it.copy(enabled = enabled) else it } }
    }

    /**
     * 读-改-写在同一个 DataStore 事务内完成。旧实现先 list() 再 edit{} 写回整表,
     * 两个操作并发(如连点开关、添加时同步回调)会互相覆盖,丢失其中一次修改。
     */
    private suspend fun update(transform: (List<McpServerConfig>) -> List<McpServerConfig>) {
        context.mcpDataStore.edit { prefs ->
            val current = McpServerConfigJson.decode(prefs[Keys.SERVERS_JSON])
            prefs[Keys.SERVERS_JSON] = McpServerConfigJson.encode(transform(current))
        }
    }

    /** 解密 Authorization(每次调用时解密,不驻留);未配置或解密失败返回 null。 */
    suspend fun decryptedAuth(config: McpServerConfig): String? {
        val enc = config.authHeaderEnc ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { SecureStore.decryptSecret(enc) }.getOrNull()
        }
    }
}
