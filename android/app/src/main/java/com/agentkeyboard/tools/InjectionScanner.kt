package com.agentkeyboard.tools

import java.text.Normalizer

/**
 * 提示注入的触发规则:在**不可信来源**(工具结果、MCP 工具描述)里找指令样文字。
 *
 * 命中不等于判定。规则只负责说「这里可疑」——收窄到带明确指代、或明确指向「你的/系统」的说法,
 * 免得普通的说明文字(无视提示继续安装、显示 prompt 变量)白白触发;命中之后由模型复核
 * (AgentRuntime 的 contentJudge)区分「在对助手下指令」和「在讨论、引用这种句式」,
 * 复核不可用时才按命中即隔离兜底。用户自己的输入、模型自己的回答不走这套规则。
 *
 * 英文模式的词边界一律用「前后不是英文字母」而不是 `\b`:Java 的 `\b` 把汉字也当单词字符,
 * 英文句式紧贴汉字时会判成没有边界而漏掉。
 */
object InjectionScanner {

    /** 指令样模式(中/英) */
    private val PATTERNS = listOf(
        // 覆盖此前的指令:必须带指代(以上/之前…)或范围(所有/全部),单独的「忽略指令」不算
        Regex(
            "忽略(?:掉|一下|过去)?(?:以上|上述|之前|上面|前面|此前|先前|刚才|刚刚|原来|原本|所有|全部|一切)" +
                "(?:的)?(?:所有|全部|一切)?(?:指令|要求|规则|设定|约束|提示(?!音|框|信息|文字|窗|语))",
        ),
        Regex(
            "(?:无视|不顾|不管)(?:掉)?(?:以上|上述|之前|上面|前面|此前|先前|刚才|所有|全部|系统)" +
                "(?:的)?(?:所有|全部)?(?:指令|要求|规则|设定|约束|提示词?)",
        ),
        // 要求说出系统提示:「系统提示词」是行话,「系统提示」必须带「你的/完整…」
        Regex("输出(?:一下|看看|来|出)?(?:你的|您的|当前|内部|完整|全部|真实)(?:的)?系统提示词?|输出(?:一下|看看|来|出)?系统提示词"),
        Regex(
            "(?:透露|泄露|复述|公布|显示|打印|输出)[^\\n]{0,8}(?:你的|您的|当前|内部|完整|全部)(?:的)?(?:系统提示词?|system\\s*prompt)|" +
                "(?:透露|泄露|复述|公布)[^\\n]{0,8}系统提示词",
            RegexOption.IGNORE_CASE,
        ),
        // 英文:覆盖/遗忘此前的指令
        Regex(
            "(?<![A-Za-z])(?:ignore|disregard|forget|override|bypass)\\s+" +
                "(?:all\\s+|any\\s+|the\\s+|your\\s+|these\\s+|those\\s+|of\\s+the\\s+|my\\s+|our\\s+)*" +
                "(?:previous|prior|earlier|above|preceding|system|initial|original|aforementioned)\\s+" +
                "(?:instructions?|rules?|prompts?|guidelines?|constraints?|directions?)(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "(?<![A-Za-z])(?:ignore|disregard)\\s+(?:all\\s+|the\\s+|your\\s+|these\\s+|those\\s+)*" +
                "(?:instructions?|rules?|prompts?|guidelines?)\\s+(?:above|before|from\\s+(?:the\\s+)?(?:system|developer))(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        ),
        Regex("(?<![A-Za-z])ignore\\s+(?:all|any)\\s+instructions?(?![A-Za-z])", RegexOption.IGNORE_CASE),
        Regex(
            "(?<![A-Za-z])(?:reveal|print|output|repeat|recite|show|display|leak|expose|disclose|tell\\s+me)\\s+(?:me\\s+)?(?:your|the)\\s+" +
                "(?:(?:system|initial|hidden|original|full|complete|entire|secret|actual|real)\\s+)+(?:system\\s+)?(?:prompt|instructions?)(?![A-Za-z])|" +
                "(?<![A-Za-z])reveal\\s+(?:me\\s+)?your\\s+prompt(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        ),
        // 越狱
        Regex(
            "(?<![A-Za-z])you\\s+are\\s+(?:now\\s+|no\\s+longer\\s+|from\\s+now\\s+on\\s+)?(?:an?\\s+)?[^.\\n]{0,40}" +
                "(?:jailbroken|jailbreak|(?-i:(?<![A-Za-z])DAN(?![A-Za-z]))|unrestricted|uncensored|unfiltered|developer\\s+mode|evil\\s+mode|" +
                "without\\s+(?:any\\s+|ethical\\s+)?(?:restrictions|rules|limitations|guidelines))(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        ),
        // 索要凭据并外传:必须带「你的/用户的」,或「把…发到」这种明确指向
        Regex(
            "(?:泄露|发给|发送到|转发到|上传到|输出|显示|打印|透露)[^\\n]{0,16}" +
                "(?:你的|您的|用户的|user'?s\\s+|your\\s+|my\\s+|我的|配置(?:的|里))" +
                "(?:\\s*api\\s*key|密钥|令牌|token|secret|系统提示词|system\\s*prompt|配置地址|服务地址|知识库\\s*(?:id|名称))",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "(?:把|将)(?:你的|您的|用户的|user'?s\\s+|your\\s+|my\\s+|我的|所有|全部)[^\\n]{0,12}" +
                "(?:api\\s*key|密钥|令牌|token|secret|凭据|凭证)[^\\n]{0,16}(?:发(?:给|送到|到)|转发|上传|泄露|输出|打印|贴出|写出来)",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "(?<![A-Za-z])(?:send|post|upload|forward|email|transmit|exfiltrate|leak)[^\\n.]{0,40}?(?<![A-Za-z])(?:your|user'?s|my)\\s+" +
                "(?:(?:api|access|auth|secret|bearer)\\s+)?(?:key|token|secret|credentials?)(?![A-Za-z])[^\\n.]{0,40}?(?<![A-Za-z])to\\s+" +
                "(?:me|us|the\\s+attacker|this\\s+(?:url|server|endpoint|webhook|address|chat|number|email)|https?://|\\S+@\\S+)",
            RegexOption.IGNORE_CASE,
        ),
        // 对用户隐瞒、声明盖过系统规则
        Regex("(?:do\\s+not|don'?t|never)\\s+(?:tell|inform|notify|warn)\\s+the\\s+user", RegexOption.IGNORE_CASE),
        Regex("不要(?:告诉|告知|通知)(?:用户|使用者)|对(?:用户|使用者)(?:隐瞒|保密)|(?:不得|不能|不可)让(?:用户|使用者)知道"),
        Regex(
            "(?<![A-Za-z])(?:overrides?|supersed?|replaces?)\\s+(?:all\\s+|any\\s+|the\\s+|these\\s+|those\\s+)?" +
                "(?:previous|prior|earlier|pre-existing|system)\\s+(?:instructions?|rules?|prompts?|guidelines?)(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        ),
        // 伪造工具结果边界(聊天模板的角色分隔符不在这里判:回喂前已由 ToolResultWrapper 中和)
        Regex("""(?i)<<<\s*(?:END_)?TOOL_RESULT\s*>>>"""),
    )

    /** 命中的第一条模式文本 */
    fun firstMatch(text: String): String? = PATTERNS.firstOrNull { it.containsMatchIn(normalize(text)) }?.pattern

    fun isFlagged(text: String): Boolean = firstMatch(text) != null

    /**
     * 取命中处前后的片段供模型复核(归一化后的文本):最多 [maxWindows] 处,每处前后各 [radius] 个字符。
     * 只看命中点附近,复核请求不必带上整篇资料。没有命中返回 null。
     */
    fun suspiciousExcerpt(text: String, radius: Int = 1_000, maxWindows: Int = 3): String? {
        val normalized = normalize(text)
        val hits = PATTERNS.flatMap { regex -> regex.findAll(normalized).map { it.range.first..it.range.last }.toList() }
            .sortedBy(IntRange::first)
        if (hits.isEmpty()) return null
        val windows = mutableListOf<IntRange>()
        for (hit in hits) {
            // 闭区间;资料比窗口短时整篇给出
            val start = (hit.first - radius).coerceAtLeast(0)
            val end = (hit.last + radius).coerceAtMost(normalized.lastIndex)
            val last = windows.lastOrNull()
            if (last != null && start <= last.last) windows[windows.size - 1] = last.first..maxOf(last.last, end)
            else {
                windows += start..end
                if (windows.size == maxWindows) break
            }
        }
        return windows.joinToString("\n……\n") { normalized.substring(it.first, it.last + 1) }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).filterNot { Character.getType(it) == Character.FORMAT.toInt() }

    /** 用户可见警示文案(12 号设计:指令样文本用户可见警示) */
    fun warningLabel(): String = "已拦截疑似不安全内容"
}
