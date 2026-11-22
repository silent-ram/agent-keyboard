package com.agentkeyboard.ai

import com.agentkeyboard.data.EmptyResponseException
import com.openai.models.chat.completions.ChatCompletionChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1-T6:流式 chunk 聚合(content 增量拼接 / tool_calls 分片聚合) */
class StreamAggregatorTest {

    // openai-java 4.x 无 ChatCompletionChunk.fromJson(String);其 HTTP 层反序列化统一走
    // com.openai.core.ObjectMappers.jsonMapper()(Kotlin internal,含全部 deserializer 配置),
    // 测试经反射取同一 mapper,从 JSON 构造 chunk,语义与真实 SSE 线缆数据一致。
    private val mapper: com.fasterxml.jackson.databind.json.JsonMapper = Class
        .forName("com.openai.core.ObjectMappers")
        .getDeclaredMethod("jsonMapper")
        .invoke(null) as com.fasterxml.jackson.databind.json.JsonMapper

    private fun chunk(json: String): ChatCompletionChunk = mapper.readValue(json, ChatCompletionChunk::class.java)

    @Test
    fun `content delta 逐片拼接且只在有新内容时回调`() {
        val agg = EngineClient.StreamAggregator()
        val snapshots = mutableListOf<Pair<String, Boolean>>()
        val chunks = listOf(
            chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}"""),
            chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}"""),
            chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"好,"},"finish_reason":null}]}"""),
            chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"世界"},"finish_reason":null}]}"""),
        )
        chunks.forEach { snapshots += agg.apply(it) }
        // 无 content 的 chunk 不触发回调
        assertEquals(false, snapshots[0].second)
        assertEquals(true, snapshots[1].second)
        // 累积文本
        assertEquals("你", snapshots[1].first)
        assertEquals("你好,世界", snapshots[3].first)
        val r = agg.result() as ToolCallResult.Content
        assertEquals("你好,世界", r.text)
    }

    @Test
    fun `tool_calls 分片聚合 - id 与 arguments 跨 chunk 拼接`() {
        val agg = EngineClient.StreamAggregator()
        agg.apply(chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_search","arguments":""}}]},"finish_reason":null}]}"""))
        agg.apply(chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\""}}]},"finish_reason":null}]}"""))
        agg.apply(chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"q4\"}"}}]},"finish_reason":"tool_calls"}]}"""))
        val r = agg.result() as ToolCallResult.ToolCalls
        assertEquals(1, r.calls.size)
        assertEquals("call_1", r.calls[0].id)
        assertEquals("web_search", r.calls[0].name)
        assertEquals("""{"query":"q4"}""", r.calls[0].argumentsJson)
    }

    @Test(expected = EmptyResponseException::class)
    fun `空content且无tool_calls - result 抛空响应`() {
        val agg = EngineClient.StreamAggregator()
        agg.apply(chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":"stop"}]}"""))
        agg.result()
    }

    @Test
    fun `choices 为空的 chunk(usage 尾包)- 不崩`() {
        val agg = EngineClient.StreamAggregator()
        val (_, hasNew) = agg.apply(chunk("""{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[]}"""))
        assertFalse(hasNew)
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
}
