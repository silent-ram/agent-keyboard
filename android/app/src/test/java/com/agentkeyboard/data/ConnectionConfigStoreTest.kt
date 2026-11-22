package com.agentkeyboard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.agentkeyboard.data.model.ModelConfigFile
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.data.model.modelConfigStore
import com.agentkeyboard.rag.ZhipuKnowledgeSettings
import com.agentkeyboard.rag.ZhipuKnowledgeSettingsStore
import com.agentkeyboard.rag.encodeKnowledgeSettings
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionConfigStoreTest {
    @Test
    fun `upgrade and restore preserve complete connections and replace them atomically`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt: (String) -> String = { SecureStore.encryptWithKey(deviceKey, it) }
        val decrypt: (String) -> String = { SecureStore.decryptWithKey(deviceKey, it) }
        val store = ModelStore(context, encrypt, decrypt)
        val config = sampleConnections()
        val knowledge = ZhipuKnowledgeSettingsStore(context, store)
        val settings = SettingsRepository(context)
        val knowledgeSecret = ModelStore.secretKey(ZhipuKnowledgeSettingsStore.SECRET_ID)
        val relaySecret = ModelStore.secretKey("relay")

        context.legacyModelSecretsStore.edit {
            it[relaySecret] = encrypt(config.providerKeys.getValue("relay"))
            it[knowledgeSecret] = encrypt(requireNotNull(config.knowledgeKey))
        }
        context.legacyKnowledgeStore.edit {
            it[stringPreferencesKey("config_json")] = encodeKnowledgeSettings(config.knowledge)
        }
        context.agentKeyboardDataStore.edit {
            it[tavilyCiphertextKey] = encrypt(requireNotNull(config.tavilyKey))
        }
        settings.saveUserMd("Keep my local profile")
        store.update { config.models }
        val exported = store.exportConnections()
        assertEquals(config.models, exported.models)
        assertEquals(config.providerKeys, exported.providerKeys)
        assertEquals(config.knowledge, knowledge.load())
        assertEquals(config.knowledgeKey, knowledge.loadApiKey())
        assertEquals(config.tavilyKey, exported.tavilyKey)
        assertNull(context.legacyModelSecretsStore.data.first()[relaySecret])
        assertNull(context.agentKeyboardDataStore.data.first()[tavilyCiphertextKey])

        val failingStore = ModelStore(context, { plain ->
            if (plain == config.tavilyKey) throw GeneralSecurityException()
            encrypt(plain)
        }, decrypt)
        val before = context.modelConfigStore.data.first().asMap()
        val failure = runCatching { failingStore.restoreConnections(exported) }.exceptionOrNull()
        assertTrue(failure is GeneralSecurityException)
        assertEquals(before, context.modelConfigStore.data.first().asMap())

        store.restoreConnections(ConnectionConfig(ModelConfigFile(), emptyMap(), ZhipuKnowledgeSettings(), null, null))
        assertNull(store.loadActiveModel())
        assertFalse(store.hasKey("relay"))
        assertFalse(knowledge.isConfigured())
        assertNull(store.exportConnections().tavilyKey)

        val targetKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val targetStore = ModelStore(
            context,
            { SecureStore.encryptWithKey(targetKey, it) },
            { SecureStore.decryptWithKey(targetKey, it) },
        )
        val file = ConfigBackupCodec.encode(exported)
        targetStore.restoreConnections(ConfigBackupCodec.decode(file.inputStream()))
        assertEquals(config.providerKeys.getValue("relay"), targetStore.loadActiveModel()?.apiKey)
        assertEquals(config.knowledge to config.knowledgeKey, targetStore.loadKnowledgeConnection())
        assertEquals(config.tavilyKey, targetStore.exportConnections().tavilyKey)
        assertEquals("Keep my local profile", settings.loadUserMd())
        assertFalse(context.modelConfigStore.data.first().asMap().values.any {
            it is String && it.contains("placeholder-")
        })

        val previousProvider = config.models.providers.single()
        val replacement = previousProvider.copy(baseUrl = "https://other.example/v1")
        val oldKey = config.providerKeys.getValue(previousProvider.id)
        assertTrue(runCatching {
            targetStore.updateProviderConnection(previousProvider, replacement, null)
        }.isFailure)
        assertEquals(previousProvider.baseUrl, targetStore.loadActiveModel()?.baseUrl)
        assertEquals(oldKey, targetStore.loadActiveModel()?.apiKey)
        val replacementKey = "fixture-replacement"
        assertEquals(replacementKey, targetStore.updateProviderConnection(previousProvider, replacement, replacementKey))
        assertEquals(replacement.baseUrl, targetStore.loadActiveModel()?.baseUrl)
        assertEquals(replacementKey, targetStore.loadActiveModel()?.apiKey)
    }
}
