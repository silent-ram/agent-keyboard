package com.agentkeyboard.ime

import android.view.inputmethod.InputConnection

/**
 * 替换执行核心(V-A 抽取:面板「🔁 替换」预览卡与快捷动作直出共用;12 号设计 §5 替换实现路径):
 * - 有选区:执行前重读选区与快照比对 → 一致则 commitText(标准行为=删除选区并插入);
 * - 无选区:重读光标前文本与快照比对 → 一致则按 code point 对齐删除后 commitText;
 * - 任一失效 → 降级插入(结果插到光标处,不覆盖原文)+ reason;
 * 输入操作经 [ReplaceOps] 小接口注入(服务层用 [IcOps] 适配 InputConnection),
 * 纯 JVM 可测(快照失效降级 / code point 删除对齐)。
 */
object ReplaceExecutor {

    /** 输入框操作抽象(单测用记录型 fake) */
    interface ReplaceOps {
        fun selectedText(): String?
        fun textBefore(maxChars: Int): String?

        /** 光标前删除(优先 code point 对齐,异常回退 Java char) */
        fun deleteBefore(javaChars: Int, codePoints: Int)
        fun commit(text: String)
    }

    /** 执行结论:[degradedReason] 非空 = 快照失效降级插入;[applied]=false = 参数为空未执行 */
    data class Outcome(val applied: Boolean, val degradedReason: String? = null)

    fun execute(ops: ReplaceOps, old: String, new: String, fromSelection: Boolean): Outcome {
        if (old.isEmpty()) return Outcome(false)
        return if (fromSelection) {
            val cur = ops.selectedText()
            val check = ReplaceLogic.checkSelection(old, cur)
            if (check.degraded) {
                ops.commit(new) // 失效降级插入
                Outcome(true, check.reason)
            } else {
                ops.commit(new)
                Outcome(true)
            }
        } else {
            val before = ops.textBefore(old.length + 8)
            val check = ReplaceLogic.checkBefore(old, before)
            if (check.degraded) {
                ops.commit(new) // 失效降级插入
                Outcome(true, check.reason)
            } else {
                val (javaChars, codePoints) = ReplaceLogic.deletionLengths(old)
                ops.deleteBefore(javaChars, codePoints)
                ops.commit(new)
                Outcome(true)
            }
        }
    }

    /** InputConnection 适配(服务层唯一实现;batch edit 由调用方包裹) */
    class IcOps(private val ic: InputConnection) : ReplaceOps {
        override fun selectedText(): String? =
            runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()

        override fun textBefore(maxChars: Int): String? =
            runCatching { ic.getTextBeforeCursor(maxChars, 0)?.toString() }.getOrNull()

        override fun deleteBefore(javaChars: Int, codePoints: Int) {
            runCatching { ic.deleteSurroundingTextInCodePoints(codePoints, 0) }
                .onFailure { ic.deleteSurroundingText(javaChars, 0) }
        }

        override fun commit(text: String) {
            ic.commitText(text, 1)
        }
    }
}
