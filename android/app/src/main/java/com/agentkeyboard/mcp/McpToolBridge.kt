package com.agentkeyboard.mcp

import com.agentkeyboard.tools.AgentToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 工具桥(G12):把用户显式配置的 server 工具同步进 [AgentToolRegistry]。
 *
 * - 同步时机:每次 Agent 任务开始前(AgentRuntime.beforeRun 钩子,TTL 内跳过)与
 *   设置页保存/删除/开关后(force);未配置 server 时零网络开销;
 * - 失败诚实:单 server 同步失败不影响其他 server 与既有工具;该 server 已注册的旧工具
 *   保留(调用时会得到带稳定错误码的失败结果),错误在设置页状态行展示;
 * - 授权边界:tools/list 属能力探测,不携带用户内容,不弹授权;tools/call 一律走
 *   PermissionGate(UNASKED 首次询问,派生底线不可豁免),与 web_search/kb_search 同一通道。
 */
class McpToolBridge(
    private val serversProvider: suspend () -> List<McpServerConfig>,
    private val authProvider: suspend (McpServerConfig) -> String?,
    private val client: McpClient = McpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val syncTtlMs: Long = 60_000L,
) {

    /** 生产装配:以 [McpServerStore] 为配置与鉴权来源。 */
    constructor(
        store: McpServerStore,
        client: McpClient = McpClient(),
    ) : this(
        serversProvider = { store.list() },
        authProvider = { cfg -> store.decryptedAuth(cfg) },
        client = client,
    )

    /** 单 server 同步状态(设置页展示);error != null 时 toolCount 无意义。 */
    data class ServerStatus(
        val serverId: String,
        val name: String,
        val toolCount: Int,
        val error: String?,
    )

    @Volatile
    private var lastSyncAt = 0L

    /** 独立"是否同步过"标记:测试时钟从 0 开始时 lastSyncAt 无法区分"从未同步"。 */
    @Volatile
    private var hasSynced = false

    /**
     * 上次成功注册到注册表的 server 前缀集合。
     * 用于"停用/删除某个 server 后把它的工具摘掉"——只按 enabled 列表重建会漏掉
     * 已下线 server 残留的工具(LLM 仍可调用已停用的 server)。
     */
    @Volatile
    private var registeredPrefixes: Set<String> = emptySet()

    /**
     * 上次同步时 enabled server 的配置指纹(name+url+id)。
     * 配置变化(新增/停用/删除/改 URL)必须立即重新同步,不能被 TTL 挡住,
     * 否则用户刚加的 server 在 60 秒内对 Agent 不可见。
     */
    @Volatile
    private var lastFingerprint: String = ""

    @Volatile
    var lastStatus: List<ServerStatus> = emptyList()
        private set

    private val mutex = Mutex()

    suspend fun syncInto(registry: AgentToolRegistry, force: Boolean = false) {
        val all = serversProvider()
        val enabled = all.filter { it.enabled }
        if (enabled.isEmpty()) {
            registry.unregisterByPrefix(McpTool.ID_PREFIX)
            registeredPrefixes = emptySet()
            lastStatus = emptyList()
            lastFingerprint = ""
            lastSyncAt = clock()
            hasSynced = true
            return
        }
        // hasSynced=false 表示从未同步:即使测试时钟从 0 起,首次也必须执行
        // 配置指纹变化(新增/停用/删除/改 URL)必须立即同步,不能被 TTL 挡住
        val fingerprint = fingerprintOf(enabled)
        if (!force && hasSynced && fingerprint == lastFingerprint && clock() - lastSyncAt < syncTtlMs) return
        mutex.withLock {
            if (!force && hasSynced && fingerprint == lastFingerprint && clock() - lastSyncAt < syncTtlMs) return
            val statuses = mutableListOf<ServerStatus>()
            val activePrefixes = mutableSetOf<String>()
            enabled.forEach { cfg ->
                val prefix = McpTool.prefixFor(cfg)
                try {
                    val descriptors = client.listTools(cfg.url, authProvider(cfg))
                    val tools = descriptors.map { descriptor ->
                        McpTool(cfg, descriptor, client) { authProvider(cfg) }
                    }
                    registry.replaceByPrefix(prefix, tools)
                    activePrefixes += prefix
                    statuses += ServerStatus(cfg.id, cfg.name, tools.size, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = if (e is McpException) "${e.code}: ${e.message}" else e.javaClass.simpleName
                    statuses += ServerStatus(cfg.id, cfg.name, 0, msg)
                    // 同步失败:保留该 server 上次注册的工具(调用时得到带错误码的失败结果),
                    // 不能当作"已下线"清掉
                    if (prefix in registeredPrefixes) activePrefixes += prefix
                }
            }
            // 已下线(停用或删除)的 server:其工具必须从注册表摘掉,
            // 否则 LLM 仍可调用用户已停用的 server 的工具
            (registeredPrefixes - activePrefixes).forEach { registry.unregisterByPrefix(it) }
            registeredPrefixes = activePrefixes
            lastStatus = statuses
            lastFingerprint = fingerprint
            lastSyncAt = clock()
            hasSynced = true
        }
    }

    /** enabled server 配置指纹:任一 server 的 name/url/id 变化即视为需重新同步。 */
    private fun fingerprintOf(enabled: List<McpServerConfig>): String =
        enabled.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.url}:${it.name}" }
}
