package com.agentkeyboard.tools

import com.agentkeyboard.rag.KnowledgeRetriever
import com.agentkeyboard.rag.RetrievedChunk
import com.agentkeyboard.rag.Snippets

/**
 * kb_search 真实现(M2-T3,FR-C3;M4 检索现代化,V-B 批次):
 * - 只查询用户选定的资料库；请求失败直接返回错误；
 * - 出处强化:结果附 citations(url=kb://docName,title=doc §ordinal,snippet=片段首句),
 *   回答卡来源列表展示片段首句;
 * - 迭代检索(Agentic RAG):结果尾部附相关性自评提示——模型可换关键词再次调用 kb_search,
 *   最多两次;无足够证据时明确告知,不退化成常识回答或外网搜索;
 * - 查询经 PermissionGate 授权并审计；命中文本随后发送到所选回答模型；
 * - 知识库关闭时不暴露工具 schema，并在执行时再次检查开关；
 * - 空库/未命中/未配置 → 明确文案回喂(不抛异常,异常由 registry 捕获转 error 结果)。
 */
class KbSearchTool(
    private val retrieverProvider: suspend () -> KnowledgeRetriever?,
    private val enabledProvider: () -> Boolean = { true },
) : Tool {

    override val id = ToolIds.KB_SEARCH
    override val name = "知识库检索"
    override val isOutbound = true
    override val targetLabel = "资料检索"
    override val description =
        "Search the user's uploaded documents, not the public web. " +
            "Call this first whenever the user asks about their own documents, notes, plans, or internal specifics. " +
            "Returns passages with provenance."

    override val schemaJson = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"Search query in the user's language"}
        },"required":["query"]}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult {
        if (!enabledProvider()) {
            return ToolResult.error("知识库开关未开启:本轮不查资料", ToolErrorCodes.DISABLED)
        }
        val query = try {
            Jsons.requireString(argsJson, "query")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("kb_search 参数无效:${e.message}", ToolErrorCodes.BAD_PARAMS)
        }
        return try {
            val retriever = retrieverProvider()
                ?: return ToolResult.error("资料检索未配置", ToolErrorCodes.NOT_CONFIGURED)
            if (retriever.isEmpty()) {
                val msg = "暂无可检索的资料。"
                ToolResult.ok(
                    raw = msg,
                    wrapped = ToolResultWrapper.wrap(msg),
                )
            } else {
                val hits = retriever.search(query, TOP_K)
                if (hits.isEmpty()) {
                    val msg = "没有找到相关资料，现有证据不足。"
                    ToolResult.ok(raw = msg, wrapped = ToolResultWrapper.wrap(msg))
                } else {
                    val text = formatHits(hits)
                    ToolResult.ok(
                        raw = text,
                        wrapped = ToolResultWrapper.wrap(text),
                        citations = hits.map { h ->
                            Citation(
                                url = "kb://${h.docName}",
                                title = if (h.ordinal >= 0) "${h.docName} §${h.ordinal + 1}" else h.docName,
                                snippet = Snippets.firstSentence(h.matchedText ?: h.text),
                            )
                        },
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("资料检索超时", ToolErrorCodes.TIMEOUT, retryable = true)
        } catch (e: Exception) {
            ToolResult.error(
                "资料检索失败",
                ToolErrorCodes.REMOTE_ERROR, retryable = true,
            )
        }
    }

    /**
     * 输出远端命中片段与来源，不把服务商分数解释成本地相似度。
     */
    private fun formatHits(hits: List<RetrievedChunk>): String {
        val sb = StringBuilder()
        sb.append("命中 ${hits.size} 条资料\n\n")
        hits.forEachIndexed { i, h ->
            val location = if (h.ordinal >= 0) " §${h.ordinal + 1}" else ""
            sb.append("【${h.docName}$location】\n")
            sb.append(h.text.trim())
            if (i < hits.size - 1) sb.append("\n\n")
        }
        return sb.toString()
    }

    override fun paramsText(argsJson: String): String =
        Jsons.optionalString(argsJson, "query") ?: argsJson

    private companion object {
        const val TOP_K = 3
    }
}
