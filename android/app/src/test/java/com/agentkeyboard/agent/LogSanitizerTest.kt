package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.LogSanitizer
import com.agentkeyboard.agent.permission.UserContentSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 活动日志脱敏(11-权限设计 §5.4) */
class LogSanitizerTest {

    @Test
    fun `手机号打码 - 保留前2后4`() {
        val out = LogSanitizer.mask("联系 13812345678 处理")
        assertFalse(out.contains("13812345678"))
        assertTrue(out.contains("13****5678"))
    }

    @Test
    fun `身份证打码`() {
        val out = LogSanitizer.mask("身份证 110101199001011234 提交")
        assertFalse(out.contains("110101199001011234"))
        assertTrue(out.contains("*"))
    }

    @Test
    fun `连续长数字打码 - 银行卡`() {
        val out = LogSanitizer.mask("卡号 6222020200112233456")
        assertFalse(out.contains("6222020200112233456"))
        assertTrue(out.contains("*"))
    }

    @Test
    fun `邮箱打码 - local 部分掩码域名保留`() {
        val out = LogSanitizer.mask("发送到 zhangsan.dev@company.example.com")
        assertFalse(out.contains("zhangsan.dev@"))
        assertTrue(out.contains("@company.example.com"))
    }

    @Test
    fun `普通文本不误伤`() {
        val text = "Q4 优先级排期:搜索项目 12 号上线"
        val out = LogSanitizer.mask(text)
        assertTrue(out.contains("Q4 优先级排期"))
        assertTrue(out.contains("12 号上线"))
    }

    @Test
    fun `剪贴板来源只存前20字且脱敏`() {
        val clip = "手机号13812345678,这是一段超过二十个字符的很长的剪贴板内容需要截断"
        val out = LogSanitizer.sanitizePreview(clip, UserContentSource.CLIPBOARD)
        assertTrue(out.length <= 20)
        assertFalse(out.contains("13812345678"))
    }

    @Test
    fun `preview 截断80字`() {
        val text = "字".repeat(200)
        val out = LogSanitizer.sanitizePreview(text)
        assertTrue(out.length <= 81) // 80 + 可能的省略号(实现取 take(80),不含省略号)
    }
}
