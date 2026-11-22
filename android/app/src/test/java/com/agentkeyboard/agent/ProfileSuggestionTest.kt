package com.agentkeyboard.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSuggestionTest {
    @Test
    fun `only accept exact text from the current user message`() {
        val source = "以后都先给结论，再列依据。"
        val args = """{"kind":"user_note","text":"以后都先给结论，再列依据。"}"""
        val proposal = requireNotNull(ProfileSuggestion.parse(args, source))
        assertEquals(ProfileSuggestion.Kind.USER_NOTE, proposal.kind)
        assertEquals(source, proposal.text)
        assertNull(ProfileSuggestion.parse(args, "以后都不要先给结论，再列依据。"))
        assertNull(ProfileSuggestion.parse("""{"kind":"user_note","text":"先给结论，再列依据。"}""", source))
        assertNull(ProfileSuggestion.parse(args, "这次简短点"))
        assertNull(ProfileSuggestion.parse(args, "先给结论，再列出处。"))
    }

    @Test
    fun `reject unknown kind and invalid or oversize payload`() {
        assertNull(ProfileSuggestion.parse("not JSON", "abc"))
        assertNull(ProfileSuggestion.parse("""{"kind":"replace","text":"abc"}""", "abc"))
        assertNull(ProfileSuggestion.parse("""{"kind":"memory","text":""}""", "abc"))
        val text = "x".repeat(501)
        assertNull(ProfileSuggestion.parse("""{"kind":"memory","text":"$text"}""", text))
    }

    @Test
    fun `only direct remember commands with explicit content qualify`() {
        val proposal = requireNotNull(ProfileSuggestion.explicitMemory("请记住这条：甲是项目简称"))
        assertEquals(ProfileSuggestion.Kind.MEMORY, proposal.kind)
        assertEquals("甲是项目简称", proposal.text)
        assertNull(ProfileSuggestion.explicitMemory("‘记住：甲是项目简称’这句话是什么意思？"))
        assertNull(ProfileSuggestion.explicitMemory("记住这条"))
    }

    @Test
    fun `proposals exclude temporary preferences and recognizable sensitive values`() {
        assertNull(ProfileSuggestion.parse(
            """{"kind":"user_note","text":"简短点"}""", "这次简短点",
        ))
        assertNull(ProfileSuggestion.explicitMemory("记住：密码是占位值"))
    }
}
