package com.agentkeyboard.data

import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥安全存储(M0-T3)。
 *
 * - 主钥:AndroidKeyStore 中生成 AES-256-GCM 密钥,alias = "agent_keyboard_master",
 *   密钥材料不出安全硬件/系统密钥库;
 * - 密文格式:Base64(IV(12B) || GCM ciphertext+tag);
 * - 绝不打日志:本类与调用方均不得记录明文 Key 或密文;
 * - 单测路径:[encryptWithKey]/[decryptWithKey] 接受外部注入的 SecretKey
 *   (AndroidKeyStore 无法在 JVM 单测中使用),roundtrip 逻辑用 JVM 随机 AES 密钥验证。
 */
object SecureStore {

    const val MASTER_ALIAS = "agent_keyboard_master"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** 取 AndroidKeyStore 主钥;不存在则生成(256 位 GCM,不可导出) */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance("AES", ANDROID_KEY_STORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** 生产路径:用 AndroidKeyStore 主钥加密(供 SettingsRepository 调用) */
    fun encryptSecret(plain: String): String = encryptWithKey(getOrCreateMasterKey(), plain)

    /** 生产路径:用 AndroidKeyStore 主钥解密(每次读取解密进内存,用完不驻留) */
    fun decryptSecret(encoded: String): String = decryptWithKey(getOrCreateMasterKey(), encoded)

    /** 加密核心(可注入密钥,便于 JVM 单测):返回 Base64(IV || ciphertext) */
    fun encryptWithKey(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("cipher.iv missing")
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherText)
    }

    /** 解密核心(可注入密钥):输入 Base64(IV || ciphertext) */
    fun decryptWithKey(key: SecretKey, encoded: String): String {
        val data = Base64.getDecoder().decode(encoded)
        require(data.size > IV_LENGTH_BYTES) { "ciphertext too short" }
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val cipherText = data.copyOfRange(IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    /** 清除本机存储的主钥(数据本体在 DataStore,由 SettingsRepository.clear 负责删除) */
    fun deleteMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(MASTER_ALIAS)) keyStore.deleteEntry(MASTER_ALIAS)
    }
}
