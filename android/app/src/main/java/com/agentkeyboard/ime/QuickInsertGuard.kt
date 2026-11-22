package com.agentkeyboard.ime

/**
 * P1(评审修正单):直出完成回调的密码框 FAIL-SAFE 守卫(纯函数,单测固化)。
 * 直出在途最长 60s,期间用户切到密码框(onStartInput 置 PASSWORD),完成回调若不挡会把
 * 结果 commitText 进密码框(违背 FAIL-SAFE 红线)。performReplace/onQuickAction 入口已有
 * 同款守卫,直出完成路径(applyQuickReplace/applyQuickInsert/restoreReplace)统一走本判定。
 */
object QuickWriteGuard {
    fun blocked(mode: Mode?): Boolean = mode == Mode.PASSWORD
}

/**
 * P2(评审修正单):直出帮写插入的输入会话校验(纯逻辑,单测固化)。
 *
 * 问题:applyQuickInsert 无条件 commit 到当前光标——直出在途(最长 60s)用户切到另一个
 * App 的聊天框时,帮写文本会自动落进无关会话。
 *
 * 修法:点击直出时记录输入会话身份([SessionIdentity]:EditorInfo hashCode + 光标前文本),
 * 完成回调时重读比对:
 * - 编辑器身份变化(切到另一 App/另一输入框)→ 丢弃;
 * - 光标前文本与点击时不一致(同框继续打字/移动光标,生成已基于旧上文)→ 丢弃;
 * - 编辑器不报告 EditorInfo / 不支持光标前查询 → 不误杀(无从比对时保持可用)。
 *
 * 对照流式上屏同类保护(ComposingVerifier.USER_EDITED,前缀比对逻辑直接复用)。
 */
object QuickInsertGuard {

    /** 点击时刻的输入会话身份(editorHash 为 null = 编辑器不报告 EditorInfo) */
    data class SessionIdentity(val editorHash: Int?, val beforeText: String)

    enum class Verdict { OK, EDITOR_CHANGED, CONTEXT_CHANGED }

    /**
     * 完成回调判定。
     * [identity] 点击时刻身份(null = 点击时未取得输入连接,不做误杀);
     * [editorHashNow] 此刻 EditorInfo hashCode(null = 编辑器不报告);
     * [beforeNow] 此刻光标前文本(null = 编辑器不支持查询)。
     */
    fun verify(identity: SessionIdentity?, editorHashNow: Int?, beforeNow: String?): Verdict = when {
        identity == null -> Verdict.OK
        editorHashNow != null && identity.editorHash != null && editorHashNow != identity.editorHash ->
            Verdict.EDITOR_CHANGED
        else -> when (ComposingVerifier.verify(identity.beforeText, beforeNow)) {
            ComposingVerifier.Verdict.USER_EDITED -> Verdict.CONTEXT_CHANGED
            else -> Verdict.OK // 前缀一致;或编辑器不报告(NOT_SUPPORTED)不误杀
        }
    }
}
