package com.agentkeyboard.tools

import java.text.Normalizer

/**
 * 扫描常见的提示注入指令与伪造角色边界。调用方负责阻断命中的输入或输出。
 */
object InjectionScanner {

    /** 指令样模式(中/英) */
    private val PATTERNS = listOf(
        Regex("忽略(以上|上述|之前|上面|前面)?(的)?(所有)?指令"),
        Regex("(无视| disregard)[^\\n]{0,12}(系统|安全)?(提示|指令|约束|规则)"),
        Regex("输出(你的)?系统提示"),
        Regex("(透露|泄露|显示|打印)[^\\n]{0,8}(系统提示|prompt)"),
        Regex("ignore\\s+(all\\s+)?previous\\s+instructions", RegexOption.IGNORE_CASE),
        Regex("reveal\\s+your\\s+(system\\s+)?prompt", RegexOption.IGNORE_CASE),
        Regex("disregard\\s+(all\\s+)?(prior|previous|above)\\s+(instructions|rules)", RegexOption.IGNORE_CASE),
        Regex("you\\s+are\\s+now\\s+[^\\n]{0,30}(jailbreak|DAN|unrestricted)", RegexOption.IGNORE_CASE),
        Regex("""(?i)(?:<<<\s*(?:END_)?TOOL_RESULT\s*>>>|<\|(?:im_start|im_end|system|assistant)\|>|\[/?INST])"""),
        Regex("""(?i)(?:输出|显示|泄露|发给|打印|透露|reveal|print|exfiltrate)[^\n]{0,24}(?:api\s*key|密钥|系统提示词|配置地址|知识库\s*id|system\s+prompt)"""),
    )

    /** 命中的第一条模式文本(供事件警示文案) */
    fun firstMatch(text: String): String? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
        return PATTERNS.firstOrNull { it.containsMatchIn(normalized) }?.pattern
    }

    fun isFlagged(text: String): Boolean = firstMatch(text) != null

    /** 用户可见警示文案(12 号设计:指令样文本用户可见警示) */
    fun warningLabel(): String = "已拦截疑似不安全内容"
}
