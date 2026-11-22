package com.agentkeyboard.agent.permission

/**
 * 活动日志脱敏(11-权限设计 §5.4,纯函数):
 * - preview 截 80 字后过脱敏正则:手机号/身份证/银行卡等连续数字 ≥8 位打码,邮箱打码;
 * - 来源为剪贴板的内容:仅存前 20 字 + 脱敏(剪贴板无法判型,按最敏感处理)。
 */
object LogSanitizer {

    const val PREVIEW_LIMIT = 80
    const val CLIPBOARD_PREVIEW_LIMIT = 20

    private val EMAIL = Regex("[A-Za-z0-9._%+-]{2,}[A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")
    private val ID_CARD = Regex("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)")
    private val LONG_DIGITS = Regex("(?<!\\d)(\\d{8,})(?!\\d)")

    /** 主入口:截断 + 打码 */
    fun sanitizePreview(text: String, source: UserContentSource? = null): String {
        val limit = if (source == UserContentSource.CLIPBOARD) CLIPBOARD_PREVIEW_LIMIT else PREVIEW_LIMIT
        val truncated = text.take(limit)
        return mask(truncated)
    }

    /** 打码:邮箱 local 部分留前2;手机号前2+****+后4;身份证前2+打码+后2;其余连续数字 ≥8 位留前2后2 */
    fun mask(text: String): String {
        var out = text
        out = EMAIL.replace(out) { m ->
            val local = m.groupValues[0].substringBefore("@")
            val head = local.take(2)
            "${head}${"*".repeat((local.length - 2).coerceAtLeast(2))}@${m.groupValues[0].substringAfter("@")}"
        }
        out = PHONE.replace(out) { m ->
            val digits = m.groupValues[1]
            "${digits.take(2)}****${digits.takeLast(4)}"
        }
        out = ID_CARD.replace(out) { m ->
            val digits = m.groupValues[1]
            "${digits.take(2)}${"*".repeat(12)}${digits.takeLast(2)}"
        }
        out = LONG_DIGITS.replace(out) { m ->
            val digits = m.groupValues[1]
            if (digits.length <= 8) {
                "${digits.take(2)}${"*".repeat(digits.length - 4)}${digits.takeLast(2)}"
            } else {
                "${digits.take(2)}${"*".repeat(digits.length - 6)}${digits.takeLast(4)}"
            }
        }
        return out
    }
}
