package com.agentkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Base URL 校验 + 模型名推断测试 */
class ModelConfigTest {

    @Test
    fun validateBaseUrl_acceptsHttpsUrl() {
        assertTrue(ModelConfig.validateBaseUrl("https://api.deepseek.com/v1"))
    }

    @Test
    fun validateBaseUrl_acceptsHttpUrl() {
        assertTrue(ModelConfig.validateBaseUrl("http://192.168.1.10:8000/v1"))
    }

    @Test
    fun validateBaseUrl_acceptsUrlWithSpaces() {
        assertTrue(ModelConfig.validateBaseUrl("  https://api.deepseek.com/v1  "))
    }

    @Test
    fun validateBaseUrl_rejectsBadInput() {
        assertFalse(ModelConfig.validateBaseUrl(""))
        assertFalse(ModelConfig.validateBaseUrl("not a url"))
        assertFalse(ModelConfig.validateBaseUrl("ftp://api.example.com"))
        assertFalse(ModelConfig.validateBaseUrl("api.deepseek.com/v1")) // 缺 scheme
        assertFalse(ModelConfig.validateBaseUrl("https://")) // 缺 host
    }

    @Test
    fun pickModel_deepseekHost_usesDeepseekChat() {
        assertEquals(
            ModelConfig.MODEL_DEEPSEEK,
            ModelConfig.pickModel("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun pickModel_otherHost_usesGlm() {
        assertEquals(
            ModelConfig.MODEL_GLM,
            ModelConfig.pickModel("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun pickModel_overrideWinsOverHostInference() {
        // 中转站场景:显式模型 ID 优先于主机推断
        assertEquals(
            "deepseek-v4.1-flash",
            ModelConfig.pickModel("https://api.x5m5x.com/v1", "deepseek-v4.1-flash"),
        )
        // 空白 override 回落推断
        assertEquals(
            ModelConfig.MODEL_GLM,
            ModelConfig.pickModel("https://api.x5m5x.com/v1", "  "),
        )
        // null override 不影响既有推断
        assertEquals(
            ModelConfig.MODEL_DEEPSEEK,
            ModelConfig.pickModel("https://api.deepseek.com/v1", null),
        )
    }
}
