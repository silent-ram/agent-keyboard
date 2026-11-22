package com.agentkeyboard.data

/**
 * 键盘高度档位的单一事实源。
 *
 * 只允许三档，避免任意比例把行高推到不可用范围；设置层和 IME 渲染层
 * 都通过 [normalize] 收敛输入，旧版本或手工写入异常值也不会破坏键盘布局。
 */
object KeyboardHeight {
    const val MIN_SCALE = 0.85f
    const val DEFAULT_SCALE = 1.0f
    const val MAX_SCALE = 1.15f

    fun normalize(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_SCALE
        return when {
            value < (MIN_SCALE + DEFAULT_SCALE) / 2f -> MIN_SCALE
            value < (DEFAULT_SCALE + MAX_SCALE) / 2f -> DEFAULT_SCALE
            else -> MAX_SCALE
        }
    }
}
