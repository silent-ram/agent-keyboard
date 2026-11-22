package com.agentkeyboard.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型配置纯逻辑单测(M1 批次 3,10 号设计 §3/§4/§6):
 * roundtrip / 迁移 / fc 生效与白名单 / 默认回退 / 重拉 offline / /models 解析 / 错误分类。
 */
class ModelConfigLogicTest {

    private fun provider(id: String, status: String = ProviderStatus.OK, err: String = "") =
        ProviderConfig(id = id, name = "P-$id", baseUrl = "https://p$id.example/v1", status = status, errorCode = err, createdAt = 1L)

    private fun model(
        ref: String,
        pid: String,
        id: String,
        fcAuto: String = FcAuto.UNKNOWN,
        override: Int = -1,
        source: String = ModelSource.FETCHED,
        offline: Boolean = false,
        lastUsed: Long = 0L,
    ) = ModelEntry(
        ref = ref, providerId = pid, modelId = id,
        fcAuto = fcAuto, fcOverride = override, source = source,
        offlineFlag = offline, lastUsedAt = lastUsed,
    )

    // 1. JSON roundtrip
    @Test
    fun `roundtrip - encode 再 decode 保真`() {
        val file = ModelConfigFile(
            providers = listOf(provider("p1", ProviderStatus.ERROR, ProviderErrorCodes.AUTH)),
            models = listOf(
                model("p1/a", "p1", "a", fcAuto = FcAuto.VERIFIED_YES, override = 0, offline = true, lastUsed = 42L),
            ),
            defaultModelRef = "p1/a",
        )
        val decoded = ModelConfigLogic.decode(ModelConfigLogic.encode(file))
        assertEquals(file, decoded)
        assertEquals(ProviderErrorCodes.AUTH, decoded.providers[0].errorCode)
        assertEquals(42L, decoded.models[0].lastUsedAt)
    }

    // 2. 迁移:旧单条配置 → provider + model + 默认引用;白名单即时标注
    @Test
    fun `迁移 - 旧配置生成 provider 模型与默认引用`() {
        val file = ModelConfigLogic.migrateLegacy(
            baseUrl = "https://api.deepseek.com/v1",
            modelOverride = "deepseek-chat",
            decryptLegacyKey = { "sk-plain" },
            uuid = { "uuid-1" },
            now = 1000L,
        )!!
        assertEquals(1, file.providers.size)
        val p = file.providers[0]
        assertEquals("uuid-1", p.id)
        assertEquals("https://api.deepseek.com/v1", p.baseUrl)
        val m = file.models.single()
        assertEquals("uuid-1/deepseek-chat", m.ref)
        assertEquals(ModelSource.MANUAL, m.source)
        assertEquals(FcAuto.PRESET_YES, m.fcAuto) // 白名单:deepseek-chat → YES
        assertEquals("uuid-1/deepseek-chat", file.defaultModelRef)
    }

    // 3. 迁移:无旧配置 → null(零配置空态 P2)
    @Test
    fun `迁移 - 无旧配置返回 null`() {
        assertNull(ModelConfigLogic.migrateLegacy(null, null, { null }, { "u" }, 0L))
        assertNull(ModelConfigLogic.migrateLegacy("", "", { null }, { "u" }, 0L))
    }

    // 3b. M3-C(audit#4)迁移仅补空:existing 的 default/name/status 不得被迁移产物覆盖
    @Test
    fun `迁移仅补空 - 已有配置默认模型与 provider 原样保留`() {
        val existing = ModelConfigFile(
            providers = listOf(provider("p1")), // name = "P-p1", status OK
            models = listOf(model("p1/deepseek-v4.1-flash", "p1", "deepseek-v4.1-flash", lastUsed = 42L)),
            defaultModelRef = "p1/deepseek-v4.1-flash",
        )
        val after = ModelConfigLogic.migrateLegacy(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4", // 与既有 provider 不同址 → 追加迁移 provider
            modelOverride = "qwen3.7-max",
            decryptLegacyKey = { null },
            uuid = { "uuid-2" },
            now = 2000L,
            existing = existing,
        )!!
        // 用户已选默认模型保持不变(迁移不得改默认)
        assertEquals("p1/deepseek-v4.1-flash", after.defaultModelRef)
        // 既有 provider 的 name/status 一字不动;迁移 provider 追加
        assertEquals("P-p1", after.providers.first { it.id == "p1" }.name)
        assertEquals(ProviderStatus.OK, after.providers.first { it.id == "p1" }.status)
        assertTrue(after.providers.none { it.name == "已迁移的配置" && it.id == "p1" })
        assertEquals(2, after.providers.size)
        // 迁移模型追加而非替换
        assertEquals(2, after.models.size)
        assertEquals("deepseek-v4.1-flash", after.models.first { it.ref == "p1/deepseek-v4.1-flash" }.modelId)
    }

    // 3c. M3-C:同 baseUrl 命中既有 provider → 不改名不改状态不重复建(名称仅空时填)
    @Test
    fun `迁移仅补空 - 同 baseUrl 命中既有 provider 不覆盖名称与状态`() {
        val existing = ModelConfigFile(
            providers = listOf(
                ProviderConfig(
                    id = "p1", name = "我的 DeepSeek", baseUrl = "https://api.deepseek.com",
                    status = ProviderStatus.OK, errorCode = "", createdAt = 1L,
                ),
            ),
            models = listOf(model("p1/deepseek-chat", "p1", "deepseek-chat")),
            defaultModelRef = "p1/deepseek-chat",
        )
        val after = ModelConfigLogic.migrateLegacy(
            baseUrl = "https://api.deepseek.com",
            modelOverride = null, // pickModel → deepseek-chat,与既有模型同 ID
            decryptLegacyKey = { "sk-x" },
            uuid = { "uuid-3" },
            now = 3000L,
            existing = existing,
        )!!
        // 名称仅在空时填:非空名不被「已迁移的配置」覆盖;status 不被 UNVERIFIED 覆盖
        assertEquals("我的 DeepSeek", after.providers.single().name)
        assertEquals(ProviderStatus.OK, after.providers.single().status)
        // 同 modelId 不重复追加
        assertEquals(1, after.models.size)
        assertEquals("p1/deepseek-chat", after.defaultModelRef)
    }

    // 3d. M3-C:既有 default 为空时迁移才参与默认回退(仅补空语义的正向路径)
    @Test
    fun `迁移仅补空 - 默认为空时回退到最新有效模型`() {
        val existing = ModelConfigFile(
            providers = listOf(provider("p1")),
            models = listOf(model("p1/a", "p1", "a")),
            defaultModelRef = "",
        )
        val after = ModelConfigLogic.migrateLegacy(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelOverride = "qwen3.7-max",
            decryptLegacyKey = { null },
            uuid = { "uuid-4" },
            now = 4000L,
            existing = existing,
        )!!
        // existing 无 lastUsedAt,迁移模型 lastUsedAt=4000 → 回退到迁移模型
        assertEquals("uuid-4/qwen3.7-max", after.defaultModelRef)
    }

    // 4. fc 生效值:覆盖永远优先(§4.2)
    @Test
    fun `fc 生效值 - override 优先于自动`() {
        assertTrue(model("r", "p", "m", fcAuto = FcAuto.VERIFIED_NO, override = 1).effectiveFc() == true)
        assertFalse(model("r", "p", "m", fcAuto = FcAuto.PRESET_YES, override = 0).effectiveFc() == true)
        assertNull(model("r", "p", "m", fcAuto = FcAuto.UNKNOWN).effectiveFc())
        assertFalse(model("r", "p", "m", fcAuto = FcAuto.PRESET_NO).effectiveFc() == true)
        assertTrue(model("r", "p", "m", fcAuto = FcAuto.VERIFIED_YES).effectiveFc() == true)
    }

    // 5. fc 白名单(§4.2 预置表)
    @Test
    fun `fc 白名单 - deepseek-chat 为 YES reasoner 为 NO`() {
        assertEquals(FcAuto.PRESET_YES, FcPresetTable.of("deepseek-chat"))
        assertEquals(FcAuto.PRESET_NO, FcPresetTable.of("deepseek-reasoner"))
        assertNull(FcPresetTable.of("glm-4.6"))
    }

    // 6. 默认回退(§6):默认被删 → lastUsedAt 最新;一个都没有 → null
    @Test
    fun `回退 - 默认失效时取 lastUsedAt 最新`() {
        val file = ModelConfigFile(
            providers = listOf(provider("p1")),
            models = listOf(
                model("p1/a", "p1", "a", lastUsed = 10L),
                model("p1/b", "p1", "b", lastUsed = 99L),
                model("p1/c", "p1", "c", lastUsed = 5L),
            ),
            defaultModelRef = "p1/zzz", // 失效
        )
        assertEquals("p1/b", ModelConfigLogic.resolveDefaultModel(file)?.ref)
        // offline 不参与回退
        val offlineFirst = file.copy(
            models = listOf(
                model("p1/x", "p1", "x", offline = true, lastUsed = 1000L),
                model("p1/a", "p1", "a", lastUsed = 10L),
            ),
            defaultModelRef = "p1/none",
        )
        assertEquals("p1/a", ModelConfigLogic.resolveDefaultModel(offlineFirst)?.ref)
        // 全空 → 零配置
        assertNull(ModelConfigLogic.resolveDefaultModel(ModelConfigFile(providers = listOf(provider("p1")))))
        assertNull(ModelConfigLogic.resolveDefaultModel(ModelConfigFile()))
    }

    // 7. 重拉:FETCHED 缺失标 offline,MANUAL 不动,新增追加(§6 模型被下线)
    @Test
    fun `重拉 - 缺失标 offline 且 MANUAL 不删并追加新模型`() {
        val file = ModelConfigFile(
            providers = listOf(provider("p1")),
            models = listOf(
                model("p1/keep", "p1", "keep", lastUsed = 1L),
                model("p1/gone", "p1", "gone", lastUsed = 2L),
                model("p1/manual", "p1", "manual", source = ModelSource.MANUAL, lastUsed = 3L),
            ),
            defaultModelRef = "p1/gone",
        )
        val after = ModelConfigLogic.applyFetchedModels(file, "p1", listOf("keep", "new1", "new2"), 9L)
        assertEquals(listOf("keep", "gone", "manual", "new1", "new2"), after.models.map { it.modelId })
        assertFalse(after.models.first { it.modelId == "keep" }.offlineFlag)
        assertTrue(after.models.first { it.modelId == "gone" }.offlineFlag)
        assertFalse(after.models.first { it.modelId == "manual" }.offlineFlag) // MANUAL 永不自动删
        // 默认失效 → 自动回退到 lastUsedAt 最新有效(manual=3)
        assertEquals("p1/manual", after.defaultModelRef)
    }

    // 8. /models 响应解析(容错:缺 data/缺字段)
    @Test
    fun `models 解析 - 提取 data id 且容忍缺字段`() {
        val body = """{"object":"list","data":[{"id":"m1","object":"model"},{"id":"m2"},{"other":true}]}"""
        assertEquals(listOf("m1", "m2"), ModelConfigLogic.parseModelList(body))
        assertEquals(emptyList<String>(), ModelConfigLogic.parseModelList("""{"error":{"message":"x"}}"""))
        assertEquals(emptyList<String>(), ModelConfigLogic.parseModelList("not json"))
    }

    // 9. 拉取错误分类(§4.1 五态)
    @Test
    fun `拉取错误分类 - 五态映射`() {
        assertEquals(ProviderErrorCodes.URL_INVALID, ModelConfigLogic.classifyFetchError("ftp://x", null, false, false))
        assertEquals(ProviderErrorCodes.AUTH, ModelConfigLogic.classifyFetchError("https://a/v1", 401, false, false))
        assertEquals(ProviderErrorCodes.AUTH, ModelConfigLogic.classifyFetchError("https://a/v1", 403, false, false))
        assertEquals(ProviderErrorCodes.PATH_404, ModelConfigLogic.classifyFetchError("https://a/v1", 404, false, false))
        assertEquals(ProviderErrorCodes.NETWORK, ModelConfigLogic.classifyFetchError("https://a/v1", null, true, false))
        assertEquals(ProviderErrorCodes.NETWORK, ModelConfigLogic.classifyFetchError("https://a/v1", null, false, true))
    }

    // 10. chip 文案(§4.2 统一能力徽章)
    @Test
    fun `chip 文案 - 支持未知单轮三态`() {
        assertEquals("⚡ m", ModelConfigLogic.chipLabel(model("r", "p", "m", fcAuto = FcAuto.PRESET_YES)))
        assertEquals("m", ModelConfigLogic.chipLabel(model("r", "p", "m", fcAuto = FcAuto.UNKNOWN)))
        assertEquals("m · 单轮", ModelConfigLogic.chipLabel(model("r", "p", "m", fcAuto = FcAuto.VERIFIED_NO)))
        assertNull(ModelConfigLogic.chipLabel(null))
    }

    // 11. 删除服务商:级联删模型 + 默认回退(§6/§8 Q5 会话保留由调用方保证)
    @Test
    fun `删除服务商 - 级联删模型并回退默认`() {
        val file = ModelConfigFile(
            providers = listOf(provider("p1"), provider("p2")),
            models = listOf(
                model("p1/a", "p1", "a", lastUsed = 1L),
                model("p2/b", "p2", "b", lastUsed = 2L),
            ),
            defaultModelRef = "p1/a",
        )
        val after = ModelConfigLogic.afterRemoveProvider(file, "p1")
        assertEquals(listOf("p2"), after.providers.map { it.id })
        assertEquals(listOf("p2/b"), after.models.map { it.ref })
        assertEquals("p2/b", after.defaultModelRef)
    }

    // 12. markUsed:最近使用排序依据(P1)
    @Test
    fun `markUsed - 刷新 lastUsedAt`() {
        val file = ModelConfigFile(
            providers = listOf(provider("p1")),
            models = listOf(model("p1/a", "p1", "a"), model("p1/b", "p1", "b", lastUsed = 5L)),
        )
        val after = ModelConfigLogic.markUsed(file, "p1/a", 77L)
        assertEquals(77L, after.models.first { it.ref == "p1/a" }.lastUsedAt)
        // 最近使用 top 取序
        val recent = after.models.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
        assertEquals("p1/a", recent.first().ref)
    }

    // 13. P0(评审修正单):toLlmConfig 必须全量带入 fc 降级链所需字段
    @Test
    fun `P0 toLlmConfig - toolsAllowed contextWindow ref 全量带入`() {
        val active = ActiveModelConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "sk-x", model = "deepseek-reasoner",
            toolsAllowed = false, contextWindow = 32768, ref = "p1/deepseek-reasoner",
        )
        val cfg = active.toLlmConfig()
        assertEquals("https://api.example.com/v1", cfg.baseUrl)
        assertEquals("sk-x", cfg.apiKey)
        assertEquals("deepseek-reasoner", cfg.model)
        assertEquals(false, cfg.toolsAllowed) // fc=NO 降级链的关键字段,丢失即带 tools 请求 → 400
        assertEquals(32768, cfg.contextWindow)
        assertEquals("p1/deepseek-reasoner", cfg.ref)
    }

    // 14. P0:toolsAllowed=null(未知)也应原样传递(不误降级)
    @Test
    fun `P0 toLlmConfig - 未知 fc 保持 null`() {
        val cfg = ActiveModelConfig("https://x/v1", "sk", "m", null, 0, "p/m").toLlmConfig()
        assertNull(cfg.toolsAllowed)
        assertEquals(0, cfg.contextWindow)
    }

    // 15. P2(评审修正单):decodeChecked 坏 JSON 不再静默——Corrupt 携带坏 raw 备份
    @Test
    fun `P2 decodeChecked - 坏JSON返回Corrupt 好JSON返回Ok`() {
        val raw = """{"providers": [broken"""
        val bad = ModelConfigLogic.decodeChecked(raw)
        assertTrue(bad is ModelConfigLogic.DecodeResult.Corrupt)
        assertEquals(raw, (bad as ModelConfigLogic.DecodeResult.Corrupt).raw)
        assertTrue(bad.reason.isNotBlank())
        val good = ModelConfigLogic.decodeChecked(
            ModelConfigLogic.encode(
                ModelConfigFile(providers = listOf(provider("p1")), models = listOf(model("p1/a", "p1", "a"))),
            ),
        )
        assertTrue(good is ModelConfigLogic.DecodeResult.Ok)
        assertEquals(1, (good as ModelConfigLogic.DecodeResult.Ok).file.providers.size)
    }

    // 16. P1(评审修正单):probeFails 计数字段 roundtrip 保留(INCONCLUSIVE≥2 停止探测依据)
    @Test
    fun `probeFails - roundtrip 保留且默认 0`() {
        val file = ModelConfigFile(models = listOf(model("p1/a", "p1", "a").copy(probeFails = 2)))
        val decoded = ModelConfigLogic.decode(ModelConfigLogic.encode(file))
        assertEquals(2, decoded.models[0].probeFails)
        assertEquals(0, model("p1/b", "p1", "b").probeFails)
    }
}
