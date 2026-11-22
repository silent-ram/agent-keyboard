package com.agentkeyboard.data

import com.agentkeyboard.data.model.ModelConfigFile
import com.agentkeyboard.data.model.ModelEntry
import com.agentkeyboard.data.model.ProviderConfig
import com.agentkeyboard.rag.ZhipuKnowledgeSettings
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

internal fun sampleConnections() = ConnectionConfig(
    models = ModelConfigFile(
        providers = listOf(ProviderConfig("relay", "中转", "https://provider.example/v1")),
        models = listOf(ModelEntry("relay/vendor/chat", "relay", "vendor/chat")),
        defaultModelRef = "relay/vendor/chat",
    ),
    providerKeys = mapOf("relay" to "placeholder-model-key"),
    knowledge = ZhipuKnowledgeSettings("library-id", "项目资料", 6),
    knowledgeKey = "placeholder-library-key",
    tavilyKey = "placeholder-search-key",
)

class ConfigBackupCodecTest {
    @Test
    fun `encrypted file round trips full configuration without recognizable plaintext`() {
        val config = sampleConnections()
        val file = ConfigBackupCodec.encode(config)
        val text = file.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("provider.example"))
        assertFalse(text.contains("placeholder"))
        assertFalse(text.contains("library-id"))
        val restored = ConfigBackupCodec.decode(ByteArrayInputStream(file))
        assertEquals(config.models, restored.models)
        assertEquals(config.providerKeys, restored.providerKeys)
        assertEquals(config.knowledge, restored.knowledge)
        assertEquals(config.knowledgeKey, restored.knowledgeKey)
        assertEquals(config.tavilyKey, restored.tavilyKey)
        assertFalse(file.contentEquals(ConfigBackupCodec.encode(config)))
    }

    @Test
    fun `corrupted ciphertext is rejected`() {
        val file = ConfigBackupCodec.encode(sampleConnections())
        file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
        assertThrows(BackupAuthenticationException::class.java) {
            ConfigBackupCodec.decode(file.inputStream())
        }
    }

    @Test
    fun `invalid version and oversized data fail before decryption`() {
        val file = ConfigBackupCodec.encode(sampleConnections())
        file[4] = 99
        assertThrows(BackupVersionException::class.java) {
            ConfigBackupCodec.decode(file.inputStream())
        }
        assertThrows(BackupFormatException::class.java) {
            ConfigBackupCodec.decode(ByteArray(ConfigBackupCodec.MAX_FILE_BYTES + 1).inputStream())
        }
        assertThrows(BackupFormatException::class.java) {
            ConfigBackupCodec.decode(byteArrayOf(1, 2, 3).inputStream())
        }
    }

    @Test
    fun `credentials cannot refer to a different provider`() {
        val config = sampleConnections()
        val invalid = ConnectionConfig(config.models, mapOf("another" to "placeholder"), config.knowledge, null, null)
        assertThrows(BackupFormatException::class.java) { ConfigBackupCodec.encode(invalid) }
    }
}
