package com.agentkeyboard.ime.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDictionaryTest {

    @Test
    fun `学习词置顶并按次数排序`() {
        val dictionary = UserDictionary()
        dictionary.learn("nihao", "您好")
        dictionary.learn("nihao", "您好")
        dictionary.learn("nihao", "你好")

        assertEquals(listOf("您好", "你好", "你号"), dictionary.rank("nihao", listOf("你号")))
    }

    @Test
    fun `重复学习去重且可持久化恢复`() {
        val original = UserDictionary()
        original.learn("64426", "您好")
        original.learn("64426", "您好")

        val restored = UserDictionary()
        restored.restore(original.encode())

        assertEquals(listOf("您好", "你好"), restored.rank("64426", listOf("你好")))
        assertEquals(1, restored.size())
    }

    @Test
    fun `非法或原样组合不记录`() {
        val dictionary = UserDictionary()

        assertFalse(dictionary.learn("", "你好"))
        assertFalse(dictionary.learn("ni hao", "你好"))
        assertFalse(dictionary.learn("nihao", "nihao"))
        assertTrue(dictionary.rank("nihao", listOf("你好")).isNotEmpty())
        assertEquals(0, dictionary.size())
    }

    @Test
    fun `超过容量淘汰最低频条目`() {
        val dictionary = UserDictionary(maxEntries = 2)
        dictionary.learn("a", "甲")
        dictionary.learn("b", "乙")
        dictionary.learn("b", "乙")
        dictionary.learn("c", "丙")

        assertEquals(2, dictionary.size())
        assertEquals(listOf("乙"), dictionary.rank("b", emptyList()))
        assertTrue(dictionary.rank("c", emptyList()).contains("丙"))
    }
}
