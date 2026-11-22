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

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        val isPassword =
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        return if (isPassword) Mode.PASSWORD else Mode.NORMAL
    }
}
