package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1/P2(评审修正单):直出完成回调的安全守卫纯逻辑。
 * P1:密码框 FAIL-SAFE(QuickWriteGuard)——直出在途切密码框,完成回调禁止写入;
 * P2:输入会话校验(QuickInsertGuard)——编辑器身份/光标前文本与点击时不一致 → 丢弃,
 * 对照流式上屏同类保护(ComposingVerifier.USER_EDITED)。
 */
class QuickInsertGuardTest {

    // ==== P1:直出完成回调密码守卫 ====

    @Test
    fun `P1 密码守卫 - PASSWORD 拦截,其余模式放行`() {
        assertTrue(QuickWriteGuard.blocked(Mode.PASSWORD))
        assertFalse(QuickWriteGuard.blocked(Mode.NORMAL))
        assertFalse(QuickWriteGuard.blocked(Mode.NULL))
        assertFalse(QuickWriteGuard.blocked(null))
    }

    // ==== P2:直出帮写插入的输入会话校验 ====

    @Test
    fun `P2 编辑器身份变化 - 丢弃(直出在途切到另一输入框)`() {
        val identity = QuickInsertGuard.SessionIdentity(editorHash = 101, beforeText = "你好,请问")
        assertEquals(
            QuickInsertGuard.Verdict.EDITOR_CHANGED,
            QuickInsertGuard.verify(identity, editorHashNow = 999, beforeNow = "你好,请问"),
        )
    }

    @Test
    fun `P2 光标前文本变化 - 丢弃(同框继续打字或移动光标)`() {
        val identity = QuickInsertGuard.SessionIdentity(editorHash = 101, beforeText = "你好,请问")
        assertEquals(
            QuickInsertGuard.Verdict.CONTEXT_CHANGED,
            QuickInsertGuard.verify(identity, editorHashNow = 101, beforeNow = "你好,请问另外"),
        )
    }

    @Test
    fun `P2 一致放行,编辑器不报告或不支持查询不误杀,无点击快照不误杀`() {
        val identity = QuickInsertGuard.SessionIdentity(editorHash = 101, beforeText = "你好,请问")
        // 一致(光标前以点击时上文结尾)→ 放行
        assertEquals(
            QuickInsertGuard.Verdict.OK,
            QuickInsertGuard.verify(identity, editorHashNow = 101, beforeNow = "xxx你好,请问"),
        )
        // 编辑器不支持光标前查询(null)→ 不误杀
        assertEquals(
            QuickInsertGuard.Verdict.OK,
            QuickInsertGuard.verify(identity, editorHashNow = 101, beforeNow = null),
        )
        // 点击时未取得输入连接(无快照)→ 不误杀
        assertEquals(
            QuickInsertGuard.Verdict.OK,
            QuickInsertGuard.verify(null, editorHashNow = 999, beforeNow = "别的框的内容"),
        )
    }
}
