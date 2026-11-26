package com.agentkeyboard.data.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 内置模型窗口表:查得到给窗口,查不到不猜;生效窗口按优先级解析 */
class ModelWindowsTest {

    @Before
    @After
    fun resetLearned() = ModelWindows.clearLearnedForTest()

    @Test
    fun `内置表 - 常见模型的窗口`() {
        assertEquals(128_000, ModelWindows.lookup("deepseek-chat"))
        assertEquals(128_000, ModelWindows.lookup("DeepSeek-Reasoner"))
        assertEquals(200_000, ModelWindows.lookup("glm-4.6"))
        assertEquals(128_000, ModelWindows.lookup("glm-4.5-air"))
        assertEquals(128_000, ModelWindows.lookup("gpt-4o-mini"))
        assertEquals(1_000_000, ModelWindows.lookup("gpt-4.1-mini"))
        assertEquals(200_000, ModelWindows.lookup("o3-mini"))
        assertEquals(32_000, ModelWindows.lookup("qwen-max"))
    }

    @Test
    fun `内置表 - 托管平台的 厂商 模型 写法与大小写不敏感`() {
        assertEquals(128_000, ModelWindows.lookup("deepseek-ai/DeepSeek-V3"))
        assertEquals(128_000, ModelWindows.lookup("meta-llama/llama-3.3-70b-instruct"))
        assertEquals(200_000, ModelWindows.lookup("zai-org/GLM-4.6"))
    }

    @Test
    fun `内置表 - ID 末尾自带窗口的命名比前缀规则更具体`() {
        assertEquals(8_000, ModelWindows.lookup("moonshot-v1-8k"))
        assertEquals(32_000, ModelWindows.lookup("moonshot-v1-32k"))
        assertEquals(128_000, ModelWindows.lookup("moonshot-v1-128k"))
        assertEquals(32_000, ModelWindows.lookup("doubao-pro-32k"))
        assertEquals(256_000, ModelWindows.lookup("doubao-pro-256k"))
    }

    @Test
    fun `内置表 - 不认识的模型不猜`() {
        assertNull(ModelWindows.lookup("my-finetune"))
        assertNull(ModelWindows.lookup("glm-9"))
        // 前缀按段匹配:glm-4.5v 不会被 glm-4.5 的规则误命中
        assertNull(ModelWindows.lookup("glm-4.5v"))
        assertNull(ModelWindows.lookup(""))
        assertNull(ModelWindows.lookup("   "))
        assertNull(ModelWindows.lookup("o1x-mini"))
    }

    @Test
    fun `resolve - 模型设置 大于 任务指定 大于 内置表 大于 默认`() {
        assertEquals(ModelWindows.DEFAULT_WINDOW_TOKENS, ModelWindows.resolve(0, 0, "my-finetune"))
        assertEquals(200_000, ModelWindows.resolve(0, 0, "glm-4.6"))
        assertEquals(64_000, ModelWindows.resolve(0, 64_000, "glm-4.6"))
        assertEquals(32_000, ModelWindows.resolve(32_000, 64_000, "glm-4.6"))
    }

    @Test
    fun `learn - 服务端报告过的上限只收窄不放宽 异常值忽略`() {
        // 比内置表宽:忽略
        ModelWindows.learn("glm-4.6", 500_000)
        assertEquals("不会比内置表更宽", 200_000, ModelWindows.resolve(0, 0, "glm-4.6"))
        // 比内置表窄:按它收窄,设置或任务给了更宽的窗口也一样
        ModelWindows.learn("zai-org/GLM-4.6", 100_000)
        assertEquals(100_000, ModelWindows.resolve(0, 0, "glm-4.6"))
        assertEquals(100_000, ModelWindows.resolve(200_000, 0, "glm-4.6"))
        assertEquals("已经更窄的设置保持不变", 32_000, ModelWindows.resolve(32_000, 64_000, "glm-4.6"))
        // 同一模型再次报告更小的上限:取更小的
        ModelWindows.learn("glm-4.6", 50_000)
        assertEquals(50_000, ModelWindows.resolve(0, 0, "GLM-4.6"))

        ModelWindows.learn("deepseek-chat", 10)
        ModelWindows.learn("deepseek-chat", 0)
        assertEquals("过小的值不是窗口,忽略", 128_000, ModelWindows.resolve(0, 0, "deepseek-chat"))
    }
}
