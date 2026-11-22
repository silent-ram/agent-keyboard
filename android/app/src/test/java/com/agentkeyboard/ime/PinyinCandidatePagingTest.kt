package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinCandidatePagingTest {

    @Test
    fun `collapsed and expanded pages cover every candidate once`() {
        val candidates = (1..19).map { "word$it" }

        val collapsed = (0 until PinyinCandidatePaging.pageCount(candidates.size, expanded = false))
            .flatMap { PinyinCandidatePaging.page(candidates, it, expanded = false) }
        val expanded = (0 until PinyinCandidatePaging.pageCount(candidates.size, expanded = true))
            .flatMap { PinyinCandidatePaging.page(candidates, it, expanded = true) }

        assertEquals(candidates, collapsed)
        assertEquals(candidates, expanded)
    }

    @Test
    fun `last page is partial and requested page is clamped`() {
        val candidates = (1..9).map { "word$it" }

        assertEquals(
            listOf("word7", "word8", "word9"),
            PinyinCandidatePaging.page(candidates, pageIndex = 99, expanded = false),
        )
        assertEquals(2, PinyinCandidatePaging.normalizePage(99, candidates.size, expanded = false))
    }

    @Test
    fun `empty candidates have no pages`() {
        assertEquals(0, PinyinCandidatePaging.pageCount(0, expanded = false))
        assertEquals(emptyList<String>(), PinyinCandidatePaging.page(emptyList(), 0, expanded = false))
        assertEquals(0, PinyinCandidatePaging.normalizePage(2, 0, expanded = true))
    }
}
