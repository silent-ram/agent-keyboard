package com.agentkeyboard.ime

import android.view.inputmethod.EditorInfo

/** 输入法回车键对宿主编辑器 action 的纯解析规则。 */
internal object EditorActionResolver {
    fun actionOrNull(imeOptions: Int): Int? = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_NONE,
        EditorInfo.IME_ACTION_UNSPECIFIED,
        -> null
        else -> imeOptions and EditorInfo.IME_MASK_ACTION
    }
}
