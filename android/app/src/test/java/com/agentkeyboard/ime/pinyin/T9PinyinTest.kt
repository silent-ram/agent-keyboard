package com.agentkeyboard.ime.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9PinyinTest {
    @Test
    fun `拼音签名 - nihao 映射为 64426`() {
        assertEquals("64426", T9Pinyin.signature("nihao"))
    }

    @Test
    fun `展开 - 64 包含 ni`() {
        assertTrue(T9Pinyin.expand("64").contains("ni"))
    }

    @Test
    fun `非法键位 - 1 不参与拼音展开`() {
        assertTrue(T9Pinyin.expand("61").isEmpty())
        assertTrue(!T9Pinyin.isPinyinDigit('1'))
    }

    @Test
    fun `分音节展开 - 64426 保留 ni hao 路径`() {
        val expansions = T9Pinyin.segmentedExpand(
            digits = "64426",
            syllables = setOf("ni", "hao", "na", "o", "mi"),
        )
        assertTrue(expansions.contains("nihao"))
        assertTrue(expansions.size < T9Pinyin.expand("64426").size)
    }
}
