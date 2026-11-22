package com.agentkeyboard.util

import android.util.Log
import com.agentkeyboard.BuildConfig

/**
 * 诊断日志收敛(M3 欠账清零):Log.i 级别的流程诊断统一走这里,由 BuildConfig.DEBUG 门控——
 * release 构建不再输出 info 级日志(降低泄漏面与噪音)。
 * Log.w 级别的失败告警保留直出(有排障价值,内容已由 LogSanitizer 约束)。
 *
 * 用法:`Logs.i(TAG, "...")` 替代 `Log.i(TAG, "...")`;tag 沿用各模块原有 TAG。
 */
object Logs {

    /** debug 构建才输出 info 级诊断 */
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    /** warn 保留(失败诊断价值;release 仍输出) */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }
}
