package com.agentkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 骨架 sanity test:验证测试基建(JUnit)可跑通。
 */
class SanityTest {

    @Test
    fun sanity_arithmeticWorks() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun sanity_imeServiceClassExists() {
        // 保证 IME 服务类在单元测试 classpath 上可加载(反射冒烟)
        val clazz = Class.forName("com.agentkeyboard.ime.AgentKeyboardService")
        assertTrue(android.inputmethodservice.InputMethodService::class.java.isAssignableFrom(clazz))
    }
}
