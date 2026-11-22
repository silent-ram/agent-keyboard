package com.agentkeyboard.data

import java.net.URI

/**
 * 模型相关纯逻辑(M0 标准档):URL 校验、模型名选择。
 * M1 将替换为完整的多服务商模型配置。
 */
object ModelConfig {

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val MODEL_GLM = "glm-4.6"
    const val MODEL_DEEPSEEK = "deepseek-chat"

    /** 本地校验 Base URL:必须为 http/https 且带 host */
    fun validateBaseUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }

    /**
     * 模型名选择:显式指定(设置页"模型 ID",中转站场景)优先;
     * 未指定时按 baseUrl 主机推断(DeepSeek 端点用 deepseek-chat,其余用 glm-4.6),
     * 保证默认配置下链路真实可用;M1 接入模型管理后废弃。
     */
    fun pickModel(baseUrl: String, modelOverride: String? = null): String {
        modelOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val host = baseUrl.trim().substringAfter("//", "").substringBefore('/')
        return if (host.endsWith("deepseek.com")) MODEL_DEEPSEEK else MODEL_GLM
    }

    /** 模型显示名(顶栏 chip / 预览卡徽标),必须与真实请求模型一致 */
    fun modelDisplayName(model: String): String = when (model) {
        MODEL_GLM -> "GLM-4.6"
        MODEL_DEEPSEEK -> "DeepSeek Chat"
        else -> model
    }
}
