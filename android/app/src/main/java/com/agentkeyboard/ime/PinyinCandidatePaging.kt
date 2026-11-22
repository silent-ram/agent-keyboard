package com.agentkeyboard.ime

internal object PinyinCandidatePaging {
    const val COLUMNS = 3
    private const val COLLAPSED_PAGE_SIZE = COLUMNS
    private const val EXPANDED_PAGE_SIZE = COLUMNS * 2

    fun pageCount(candidateCount: Int, expanded: Boolean): Int {
        if (candidateCount <= 0) return 0
        val pageSize = pageSize(expanded)
        return (candidateCount + pageSize - 1) / pageSize
    }

    fun normalizePage(pageIndex: Int, candidateCount: Int, expanded: Boolean): Int {
        val pages = pageCount(candidateCount, expanded)
        return if (pages == 0) 0 else pageIndex.coerceIn(0, pages - 1)
    }

    fun page(candidates: List<String>, pageIndex: Int, expanded: Boolean): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val pageSize = pageSize(expanded)
        val page = normalizePage(pageIndex, candidates.size, expanded)
        val start = page * pageSize
        return candidates.drop(start).take(pageSize)
    }

    private fun pageSize(expanded: Boolean) =
        if (expanded) EXPANDED_PAGE_SIZE else COLLAPSED_PAGE_SIZE
}
