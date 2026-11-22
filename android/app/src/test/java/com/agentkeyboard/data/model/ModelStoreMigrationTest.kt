package com.agentkeyboard.data.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.agentkeyboard.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M3-C(audit#4)迁移仅补空回归(Robolectric,真 DataStore/真 ModelStore 路径):
 * 审计实测「重启后默认模型 deepseek-v4.1-flash 被改成 qwen3.7-max、服务商被改名『已迁移的配置』」——
 * 根因:模型配置 JSON 为空时(损坏重置/新装/旧版本残留),[ModelStore.migrateIfNeeded] 用 M0 旧配置
 * (SettingsRepository 的 baseUrl/modelOverride)全量生成新配置写入,压掉用户在 M3 UI 的配置。
 *
 * 单方法全链路(DataStore 委托是进程级单例,跨测试方法会带状态,故不拆多方法):
 * ① 旧配置触发迁移(补空路径,产物即审计所见)→ ② 用户在 M3 UI 重新配置(整文件覆盖写)
 * → ③ 新实例 load() 模拟重启 → 断言默认/名称/status 不变、迁移不再运行 → ④ 重复 load 幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelStoreMigrationTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()
    private val legacyUrl = "https://open.bigmodel.cn/api/paas/v4"

    private fun configuredFile() = ModelConfigFile(
        providers = listOf(
            ProviderConfig(
                id = "provider-deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com",
                status = ProviderStatus.OK, errorCode = "", createdAt = 1000L,
            ),
        ),
        models = listOf(
            ModelEntry(
                ref = "provider-deepseek/deepseek-v4.1-flash", providerId = "provider-deepseek",
                modelId = "deepseek-v4.1-flash", source = ModelSource.MANUAL, lastUsedAt = 2000L,
            ),
        ),
        defaultModelRef = "provider-deepseek/deepseek-v4.1-flash",
    )

    @Test
    fun `迁移仅补空 - 旧配置触发迁移后用户重配 重启不回退不改名`() = runBlocking {
        // ① 旧配置存在 + JSON 空 → 迁移运行(补空的正常路径,产物即审计所见)
        SettingsRepository(context()).save(baseUrl = legacyUrl, apiKeyPlain = null, modelOverride = "qwen3.7-max")
        val migrated = ModelStore(context()).load()
        assertEquals("已迁移的配置", migrated.providers.single().name)
        assertEquals("qwen3.7-max", migrated.models.single().modelId)

        // ② 用户在 M3 UI 重新配置(upsert = 整文件覆盖写,与审计操作等价)
        val store = ModelStore(context())
        store.update { _ -> configuredFile() }

        // ③ 新实例 load() = 模拟杀进程重启:默认模型/名称/status 必须保持用户配置
        val after = ModelStore(context()).load()
        assertEquals("provider-deepseek/deepseek-v4.1-flash", after.defaultModelRef)
        assertEquals("DeepSeek", after.providers.single().name)
        assertEquals(ProviderStatus.OK, after.providers.single().status)
        assertTrue(after.models.none { it.modelId == "qwen3.7-max" }) // 迁移不得回流

        // ④ 再次 load 幂等(无重复迁移/追加)
        val again = ModelStore(context()).load()
        assertEquals(1, again.providers.size)
        assertEquals(1, again.models.size)
        assertEquals(after, again)
    }

    /**
     * M3-C 评审 P2(配置损坏重置后迁移回流吞提示):
     * 修复前:坏 JSON → load 置 CONFIG_ERROR + 清 JSON(用户被告知「需重配」)→ 下次 load
     * JSON 为空 → migrateIfNeeded 用 M0 旧配置全量迁移 write() 且 write 里 remove(CONFIG_ERROR_KEY)
     * → 重启变回旧迁移配置、错误提示消失。
     * 修复后:migrateIfNeeded 入口检测 CONFIG_ERROR 存在即 return null(损坏态不迁移,
     * 保持提示直至用户重配保存)。
     */
    @Test
    fun `配置损坏重置后 - 迁移不回流且错误提示保持`() = runBlocking<Unit> {
        // ① M0 legacy 旧配置在 + 写坏 JSON 模拟配置损坏(键名与 ModelStore.JSON_KEY 一致)
        SettingsRepository(context()).save(baseUrl = legacyUrl, apiKeyPlain = null, modelOverride = "qwen3.7-max")
        context().modelConfigStore.edit { it[stringPreferencesKey("config_json")] = "{\"corrupted\"" }

        // ② 首次 load:decode 失败 → 坏键清除 + CONFIG_ERROR 置位,按零配置继续
        val afterCorrupt = ModelStore(context()).load()
        assertEquals(0, afterCorrupt.providers.size)
        assertEquals("CONFIG_CORRUPT", ModelStore(context()).configError())

        // ③ 再次 load(等价重启):不得发生迁移回流,错误标记保持
        val again = ModelStore(context()).load()
        assertEquals(0, again.providers.size)
        assertTrue(again.models.isEmpty())
        assertTrue(again.providers.none { it.name == "已迁移的配置" }) // 迁移产物未回流
        assertEquals("CONFIG_CORRUPT", ModelStore(context()).configError()) // 提示未被 write 清除

        // ④ 清理:DataStore 委托为进程级单例(跨测试方法带状态),清掉错误标记避免污染其他用例
        context().modelConfigStore.edit {
            it.remove(stringPreferencesKey("config_error")) // = ModelStore.CONFIG_ERROR_KEY
            it.remove(stringPreferencesKey("config_json_corrupt")) // = ModelStore.CORRUPT_JSON_KEY
        }
    }
}
