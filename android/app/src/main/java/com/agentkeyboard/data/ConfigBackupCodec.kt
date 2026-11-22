package com.agentkeyboard.data

import com.agentkeyboard.data.model.ModelConfigFile
import com.agentkeyboard.data.model.ModelConfigLogic
import com.agentkeyboard.rag.ZhipuKnowledgeSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val providerIdPattern = Regex("[a-zA-Z0-9_-]{1,128}")

@Serializable
internal class ConnectionConfig(
    val models: ModelConfigFile,
    val providerKeys: Map<String, String>,
    val knowledge: ZhipuKnowledgeSettings,
    val knowledgeKey: String?,
    val tavilyKey: String?,
) {
    fun validate() {
        val providerIds = models.providers.map { it.id }.toSet()
        val refs = models.models.map { it.ref }.toSet()
        if (models.version != 1 || providerIds.size != models.providers.size ||
            refs.size != models.models.size || !providerIds.containsAll(providerKeys.keys) ||
            models.defaultModelRef.isNotEmpty() && models.defaultModelRef !in refs ||
            models.providers.any {
                !it.id.matches(providerIdPattern) || it.id == "zhipu_knowledge" ||
                    it.protocol != "OPENAI_COMPAT" || !validUrl(it.baseUrl)
            } ||
            models.models.any {
                it.providerId !in providerIds || it.modelId.isBlank() || it.modelId.any(Char::isISOControl) ||
                    it.ref != ModelConfigLogic.modelRef(it.providerId, it.modelId) ||
                    it.fcOverride !in -1..1 || it.contextWindow < 0
            } ||
            knowledge.knowledgeId.any(Char::isISOControl) || (knowledge.documentCount ?: 0) < 0 ||
            knowledge.provider.validBaseUrl(knowledge.baseUrl) != knowledge.baseUrl ||
            (providerKeys.values + listOfNotNull(knowledgeKey, tavilyKey)).any {
                it.isBlank() || it.length > 16_384 || it.any(Char::isISOControl)
            }
        ) throw BackupFormatException()
    }

    private fun validUrl(url: String): Boolean =
        url == url.trim() && ModelConfig.validateBaseUrl(url) && URI(url).let {
            it.userInfo == null && it.fragment == null
        }
}

internal class BackupFormatException : Exception()
internal class BackupAuthenticationException : Exception()
internal class BackupVersionException : Exception()

internal object ConfigBackupCodec {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private val magic = byteArrayOf(0x41, 0x4b, 0x43, 0x46) // AKCF
    private const val VERSION: Byte = 3
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 5 + NONCE_BYTES
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true }

    // Public format parameter for unattended interchange. Any holder of the file can recover the contents.
    private val formatKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("AgentKeyboard/portable-config/v3".encodeToByteArray()),
        "AES",
    )

    fun encode(config: ConnectionConfig): ByteArray {
        config.validate()
        val clear = json.encodeToString(config).encodeToByteArray()
        try {
            if (clear.size > MAX_FILE_BYTES - HEADER_BYTES - TAG_BYTES) throw BackupFormatException()
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = magic + byteArrayOf(VERSION) + nonce
            val cipher = cipher(Cipher.ENCRYPT_MODE, header)
            return header + cipher.doFinal(clear)
        } finally {
            clear.fill(0)
        }
    }

    fun decode(source: InputStream): ConnectionConfig {
        val bytes = source.readBounded(MAX_FILE_BYTES)
        if (bytes.size < 5 || !magic.contentEquals(bytes.copyOfRange(0, 4))) {
            throw BackupFormatException()
        }
        if (bytes[4] != VERSION) throw BackupVersionException()
        if (bytes.size < HEADER_BYTES + TAG_BYTES) throw BackupFormatException()
        val clear = try {
            cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, HEADER_BYTES))
                .doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        } catch (_: BadPaddingException) {
            throw BackupAuthenticationException()
        }
        try {
            return json.decodeFromString<ConnectionConfig>(clear.decodeToString(throwOnInvalidSequence = true))
                .also(ConnectionConfig::validate)
        } catch (_: SerializationException) {
            throw BackupFormatException()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw BackupFormatException()
        } finally {
            clear.fill(0)
        }
    }

    private fun cipher(mode: Int, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, formatKey, GCMParameterSpec(TAG_BYTES * 8, header, 5, NONCE_BYTES))
            updateAAD(header)
        }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) return output.toByteArray()
        if (count == 0) {
            val value = read()
            if (value == -1) return output.toByteArray()
            if (output.size() == limit) throw BackupFormatException()
            output.write(value)
        } else {
            if (output.size() + count > limit) throw BackupFormatException()
            output.write(buffer, 0, count)
        }
    }
}
