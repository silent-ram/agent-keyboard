package com.agentkeyboard.data

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * SecureStore 加解密 roundtrip(纯 JVM:AndroidKeyStore 不可用于单测,
 * 注入 JVM 随机 AES-256 密钥验证核心逻辑;生产路径走 AndroidKeyStore 主钥)。
 */
class SecureStoreTest {

    private lateinit var key: javax.crypto.SecretKey

    @Before
    fun setUp() {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun roundtrip_plainTextSurvives() {
        val plain = "sk-test-abc123"
        val encoded = SecureStore.encryptWithKey(key, plain)
        assertEquals(plain, SecureStore.decryptWithKey(key, encoded))
    }

    @Test
    fun roundtrip_chineseAndSymbols() {
        val plain = "中文+特殊符号 !@#¥%……&*()"
        val encoded = SecureStore.encryptWithKey(key, plain)
        assertEquals(plain, SecureStore.decryptWithKey(key, encoded))
    }

    @Test
    fun encrypt_sameInputTwice_producesDifferentCiphertext_dueToRandomIv() {
        val encoded1 = SecureStore.encryptWithKey(key, "sk-test-abc123")
        val encoded2 = SecureStore.encryptWithKey(key, "sk-test-abc123")
        assertNotEquals(encoded1, encoded2)
        // 两次密文都可解回同一明文
        assertEquals("sk-test-abc123", SecureStore.decryptWithKey(key, encoded1))
        assertEquals("sk-test-abc123", SecureStore.decryptWithKey(key, encoded2))
    }

    @Test
    fun decrypt_withWrongKey_fails() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encoded = SecureStore.encryptWithKey(key, "sk-test-abc123")
        assertThrows(Exception::class.java) { SecureStore.decryptWithKey(otherKey, encoded) }
    }

    @Test
    fun decrypt_truncatedInput_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureStore.decryptWithKey(key, "AAAA")
        }
    }
}
