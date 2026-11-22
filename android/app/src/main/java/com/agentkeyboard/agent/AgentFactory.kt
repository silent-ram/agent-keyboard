package com.agentkeyboard.agent

import android.content.Context
import org.fcitx.fcitx5.android.R
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.ai.EngineClient
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.data.model.toLlmConfig
import com.agentkeyboard.tools.AgentToolRegistry
import com.agentkeyboard.tools.KbSearchTool
import com.agentkeyboard.tools.WebSearchTool
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 资料问答 Agent 装配:提供智谱知识库与用户授权的公网搜索。 */
object AgentFactory {

    /**
     * 组装资料问答 Agent:开放知识库检索与联网搜索;不暴露宿主上下文、剪贴板、
     * 写入输入框或网页读取工具。
     */
    fun createKnowledgeAssistant(context: Context, resolver: ConfirmationResolver): AgentKit {
        return createInternal(context, resolver)
    }

    private fun createInternal(
        context: Context,
        resolver: ConfirmationResolver,
    ): AgentKit {
        val db = AgentDatabase.get(context)
        val sharedEvents = MutableSharedFlow<AgentEvent>(
            replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val gate = PermissionGate(
            permissionStateDao = db.permissionStateDao(),
            activityLogDao = db.activityLogDao(),
            resolver = resolver,
            emitConfirmation = { sharedEvents.emit(it) },
            llmDisclosureTitle = context.getString(R.string.llm_disclosure_title),
            llmDisclosureBody = context.getString(R.string.llm_disclosure_body),
        )
        val tools = listOf(
            WebSearchTool(
                apiKeyProvider = { com.agentkeyboard.data.SettingsRepository(context.applicationContext).loadTavilyKey() },
            ),
            KbSearchTool(
                retrieverProvider = {
                    val (kbSettings, key) = ModelStore(context.applicationContext).loadKnowledgeConnection()
                    if (kbSettings.knowledgeId.isBlank() || key.isNullOrBlank()) null
                    else kbSettings.provider.connect(kbSettings.baseUrl, key).retriever(kbSettings)
                },
            ),
        )
        val registry = AgentToolRegistry(
            tools = tools,
            gate = gate,
            activityLogDao = db.activityLogDao(),
        )
        // G12:用户显式配置的 MCP server 工具桥;任务开始前同步(TTL 60s),未配置零开销
        val mcpBridge = com.agentkeyboard.mcp.McpToolBridge(
            store = com.agentkeyboard.mcp.McpServerStore(context.applicationContext),
        )
        val engine = AgentRuntime(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            gate = gate,
            registry = registry,
            llmConfigProvider = { ModelStore(context).loadActiveModel()?.toLlmConfig() },
            llmCall = { baseUrl, apiKey, model, messages, tools, handle, onDelta ->
                EngineClient.streamWithTools(baseUrl, apiKey, model, messages, tools, handle, onDelta)
            },
            eventsFlow = sharedEvents,
            beforeRun = { mcpBridge.syncInto(registry) },
            profileProvider = AgentProfileStore(
                db, com.agentkeyboard.data.SettingsRepository(context.applicationContext),
            )::profileFor,
            protectedConfigProvider = { protectedConfig(context) },
        )
        return AgentKit(engine, gate, registry, sharedEvents, mcpBridge)
    }

    internal suspend fun protectedConfig(context: Context): List<String> {
        val config = ModelStore(context.applicationContext).privacySnapshot()
        return buildList {
            addAll(config.providerKeys.values)
            addAll(listOfNotNull(config.knowledgeKey, config.tavilyKey))
            add(config.knowledge.knowledgeId)
            add(config.knowledge.knowledgeName)
            addAll(AgentContentSafety.endpointValues(config.knowledge.baseUrl))
            addAll(listOf("智谱知识库", "Zhipu", "open.bigmodel.cn", "Tavily", "api.tavily.com"))
            config.models.providers.forEach {
                add(it.name)
                addAll(AgentContentSafety.endpointValues(it.baseUrl))
            }
            config.models.models.forEach {
                add(it.modelId)
                if (it.displayName.isNotBlank()) add(it.displayName)
            }
            val mcpStore = com.agentkeyboard.mcp.McpServerStore(context.applicationContext)
            mcpStore.list().forEach {
                add(it.name)
                addAll(AgentContentSafety.endpointValues(it.url))
                mcpStore.decryptedAuth(it)?.let { auth ->
                    add(auth)
                    add(auth.substringAfter(' ', auth))
                }
            }
        }
    }

    internal suspend fun historySafety(context: Context): AgentContentSafety? = try {
        AgentContentSafety(protectedConfig(context))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** 一次组装的完整集合(面板持有 kit:订阅 events、读 pendingConfirmation 恢复确认条、start/cancel、reset 权限) */
class AgentKit(
    val engine: AgentRuntime,
    val gate: PermissionGate,
    val registry: AgentToolRegistry,
    val events: MutableSharedFlow<AgentEvent>,
    /** G12:MCP server 配置变更后由设置页 force 同步;状态行数据源 */
    val mcpBridge: com.agentkeyboard.mcp.McpToolBridge,
) {
    /** P1-2:待决确认单槽(与 engine.pendingConfirmation 同源),面板恢复/重进时读取 */
    val pendingConfirmation get() = gate.pendingConfirmation
}
