package com.agentkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 当前输入框的上下文模式(最小版):
 * - [Mode.NORMAL]:普通文本框,工具条 + AI 功能全量可用;
 * - [Mode.PASSWORD]:密码/敏感输入框,工具条替换为安全横幅,键盘打字不受影响;
 * - [Mode.NULL]:inputType == TYPE_NULL 的纯文本场景(如部分网页表单),整条工具条隐藏。
 */
enum class Mode { NORMAL, PASSWORD, NULL }

/**
 * EditorInfo 解析为纯函数,便于单元测试(不依赖服务实例)。
 */
object ImeContextProvider {

    fun detect(info: EditorInfo?): Mode {
        if (info == null) return Mode.NORMAL
        if (info.inputType == InputType.TYPE_NULL) return Mode.NULL

        // 变体值按输入类别解释:TYPE_NUMBER_VARIATION_PASSWORD 与 TYPE_TEXT_VARIATION_URI 都是 0x10,
        // 不先看类别会把浏览器地址栏等 URI 输入框误判为密码框。
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        val isPassword = when (inputClass) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }

        return if (isPassword) Mode.PASSWORD else Mode.NORMAL
    }
}
