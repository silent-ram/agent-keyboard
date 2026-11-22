package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.ClassifyOutbound
import com.agentkeyboard.agent.permission.Decision
import com.agentkeyboard.agent.permission.TaintSnapshot
import com.agentkeyboard.agent.permission.UserContentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** classifyOutbound 派生判定(11-权限设计 §4,FR-E2):滑窗逐字 / 短剪贴板 Jaccard / LLM 改写放行 */
class ClassifyOutboundTest {

    @Test
    fun `规则A - 原文10字以上片段逐字命中 - 确认并标注剪贴板`() {
        val clip = "关于订单3852156789的投诉:三天未发货,要求今晚必须给出补偿方案"
        val d = ClassifyOutbound.classify("帮我把 订单3852156789 的投诉 再写详细点", TaintSnapshot(clipboardText = clip))
        assertTrue(d is Decision.Confirm)
        assertEquals(UserContentSource.CLIPBOARD, (d as Decision.Confirm).source)
    }

    @Test
    fun `规则A - 输入框原文片段命中 - 标注 context`() {
        val ctx = "Q4 优先级排期会议纪要:第一条是搜索项目排期确认"
        val d = ClassifyOutbound.classify("搜索 Q4 优先级排期会议纪要", TaintSnapshot(contextText = ctx))
        assertTrue(d is Decision.Confirm)
        assertEquals(UserContentSource.CONTEXT, (d as Decision.Confirm).source)
    }

    @Test
    fun `规则A - 少于10字的片段不触发`() {
        val clip = "密码是 abcd123"
        val d = ClassifyOutbound.classify("查询 abcd123 含义", TaintSnapshot(clipboardText = clip))
        // 9 字符原文滑窗不足 10 字;剪贴板 10 字符 ≤120 走规则 B,此处相似度低于 0.6
        assertEquals(Decision.Allow, d)
    }

    @Test
    fun `规则A - 跨片段步长4不漏检`() {
        // 原文 41 字,敏感片段落在滑窗步长边界也要命中
        val ctx = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十秘密部分"
        val d = ClassifyOutbound.classify("查一下 二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十秘密", TaintSnapshot(contextText = ctx))
        assertTrue(d is Decision.Confirm)
    }

    @Test
    fun `规则A - 全半角与空白归一化后仍命中`() {
        val clip = "合同编号 ABC-1234567"
        val d = ClassifyOutbound.classify("搜索 合同编号 ABC－1234567 全文", TaintSnapshot(clipboardText = clip))
        assertTrue(d is Decision.Confirm)
    }

    @Test
    fun `规则B - 短剪贴板改写高相似 - Jaccard大于等于0_6 确认`() {
        val clip = "明天下午3点在国贸中心开会"
        // 改写但字符 2-gram 大量保留
        val d = ClassifyOutbound.classify("明天 下午3点 国贸中心 开会", TaintSnapshot(clipboardText = clip))
        assertTrue(d is Decision.Confirm)
        assertEquals(UserContentSource.CLIPBOARD, (d as Decision.Confirm).source)
    }

    @Test
    fun `LLM改写产物 - 无原文重叠 - 放行`() {
        val ctx = "帮我给客户写一封关于项目延期的说明邮件,语气要诚恳"
        val query = "项目延期说明邮件 客户 语气"
        val d = ClassifyOutbound.classify(query, TaintSnapshot(contextText = ctx))
        assertEquals(Decision.Allow, d)
    }

    @Test
    fun `无污染快照 - 一律放行`() {
        assertEquals(Decision.Allow, ClassifyOutbound.classify("q4 财报 发布日期", TaintSnapshot.EMPTY))
    }

    @Test
    fun `超120字剪贴板不走规则B`() {
        val longClip = "长".repeat(130)
        val d = ClassifyOutbound.classify("长长长长 长长长长", TaintSnapshot(clipboardText = longClip))
        assertEquals(Decision.Allow, d)
    }

    @Test
    fun `滑窗片段覆盖末尾 - 收口片段不漏检`() {
        val chunks = ClassifyOutbound.slidingChunks("abcdefghijk", 10, 4)
        // 11 字符:0..10, 4..14→收口 1..11(末尾)
        assertTrue(chunks.contains("abcdefghij"))
        assertTrue(chunks.contains("bcdefghijk"))
    }

    @Test
    fun `bigramJaccard - 完全相同为1`() {
        assertEquals(1.0, ClassifyOutbound.bigramJaccard("开会通知", "开会通知"), 1e-9)
    }
}
