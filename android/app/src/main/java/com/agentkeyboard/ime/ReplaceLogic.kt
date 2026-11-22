package com.agentkeyboard.ime

/**
 * 替换语义执行前的纯逻辑校验(M3,12 号设计 §5 替换实现路径)。
 * IME 侧(onReplace)在 beginBatchEdit 内调用本对象判定,再执行 InputConnection 写操作。
 * 全部为纯函数,可直接 JVM 单测(替换快照失效降级 / code point 删除对齐)。
 */
object ReplaceLogic {

    /**
     * 校验结论:
     * - [valid]=false:不该执行(参数为空等,防御);
     * - [degraded]=true:快照失效,降级为插入(结果插到光标处,不覆盖原文)。
     */
    data class Check(val valid: Boolean, val degraded: Boolean = false, val reason: String? = null)

    /**
     * 有选区替换校验:执行前重读选区与快照比对。
     * 选区 == 快照 → 正常替换(commitText 标准行为即「删除选区并插入」);
     * 选区已变(用户在等待期间改了选区/编辑器不报告选区)→ 降级插入。
     */
    fun checkSelection(oldSnapshot: String, currentSelection: String?): Check = when {
        oldSnapshot.isEmpty() -> Check(false)
        currentSelection == oldSnapshot -> Check(true)
        else -> Check(true, degraded = true, reason = "原文选区已变化,已改为插入")
    }

    /**
     * 无选区替换校验:执行前重读光标前文本与快照比对([currentBefore] 为
     * getTextBeforeCursor(oldSnapshot.length + padding) 的返回,可能为 null)。
     * 校验通过后按 [deletionLengths] 的 code point 对齐长度删除。
     */
    fun checkBefore(oldSnapshot: String, currentBefore: String?): Check = when {
        oldSnapshot.isEmpty() -> Check(false)
        currentBefore != null && currentBefore.endsWith(oldSnapshot) -> Check(true)
        else -> Check(true, degraded = true, reason = "原文已变化,已改为插入")
    }

    /**
     * code point 对齐的删除长度(M3 单测点):返回 (Java char 数, code point 数)。
     * emoji 等增补字符占 2 个 Java char、1 个 code point——deleteSurroundingText
     * 按 Java char 计数,deleteSurroundingTextInCodePoints 按 code point 计数;
     * 两个长度都从快照原文换算,避免中文/emoji 混排时截断代理对。
     */
    fun deletionLengths(text: String): Pair<Int, Int> =
        text.length to text.codePointCount(0, text.length)

    /**
     * 恢复原文校验(12 §5 应用内单步恢复):当前光标前文本以新文本结尾
     * (替换后光标停在新文本尾部)→ 可反向替换;位置已变化 → 提示手动处理。
     */
    fun checkRestore(newText: String, currentBefore: String?): Boolean =
        newText.isNotEmpty() && currentBefore != null && currentBefore.endsWith(newText)

    /**
     * 快捷动作源文本选择(12 §5 三动作语义表):
     * 有选区(ImeContextProvider 读取,可能 null)用选区;无选区取光标前 ≤[contextBeforeChars] 上文。
     * 两者皆空 → null(上层提示无可用内容)。
     */
    fun pickSource(selected: String?, beforeText: String, contextBeforeChars: Int): String? {
        val sel = selected?.takeIf { it.isNotBlank() }
        if (sel != null) return sel
        val before = beforeText.take(contextBeforeChars)
        return before.takeIf { it.isNotBlank() }
    }
}
