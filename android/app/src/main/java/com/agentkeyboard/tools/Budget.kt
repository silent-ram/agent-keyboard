package com.agentkeyboard.tools

/**
 * 工具输出预算(02-架构 §2.4,纯函数便于单测):
 * - web_search:top5,每条 content ≤300 字符
 * - fetch_page:全文 >6000 字符时,头 2000 + query 命中附近 ±1500 + 尾 500
 */
object Budget {

    const val SEARCH_MAX_RESULTS = 5
    const val SEARCH_ITEM_CONTENT_LIMIT = 300

    const val PAGE_FULL_LIMIT = 6000
    const val PAGE_HEAD = 2000
    const val PAGE_WINDOW = 1500
    const val PAGE_TAIL = 500

    /** 搜索结果单条 content 截断(预算硬限) */
    fun searchItemContent(content: String): String =
        if (content.length <= SEARCH_ITEM_CONTENT_LIMIT) content else content.take(SEARCH_ITEM_CONTENT_LIMIT) + "…"

    /**
     * 网页正文窗口截断:
     * - 全文 ≤6000 字符:原样返回;
     * - 超限:头 [PAGE_HEAD] + 命中 [query] 的第一个位置附近 ±[PAGE_WINDOW] + 尾 [PAGE_TAIL];
     *   未命中 query 时退化为 头 (PAGE_HEAD+PAGE_WINDOW) + 尾 PAGE_TAIL。
     */
    fun pageWindow(fullText: String, query: String?): String {
        if (fullText.length <= PAGE_FULL_LIMIT) return fullText
        val head = fullText.take(PAGE_HEAD)
        val tail = fullText.takeLast(PAGE_TAIL)
        val midStart = query?.takeIf { it.isNotBlank() }?.let { q ->
            fullText.indexOf(q, PAGE_HEAD).takeIf { it >= 0 }
        } ?: (PAGE_HEAD + PAGE_WINDOW).coerceAtMost(fullText.length - PAGE_TAIL - 1).coerceAtLeast(0)
        val midFrom = (midStart - PAGE_WINDOW).coerceAtLeast(0)
        val midTo = (midStart + PAGE_WINDOW).coerceAtMost(fullText.length - PAGE_TAIL)
        if (midTo <= midFrom) return head + "\n…\n" + tail
        val mid = fullText.substring(midFrom, midTo)
        return head + "\n…\n" + mid + "\n…\n" + tail
    }
}
