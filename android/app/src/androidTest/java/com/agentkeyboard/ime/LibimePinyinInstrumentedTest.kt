package com.agentkeyboard.ime

import androidx.test.platform.app.InstrumentationRegistry
import com.agentkeyboard.ime.pinyin.LibimePinyinEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class LibimePinyinInstrumentedTest {

    @Test
    fun nativeEngineReturnsQwertyAndT9Candidates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LibimePinyinEngine.create(context)
        try {
            val qwerty = engine.candidates("nihao", limit = 5)
            assertTrue("QWERTY candidate list is empty: $qwerty", qwerty.isNotEmpty())
            assertTrue("Expected 你好 in top five: $qwerty", "你好" in qwerty)

            val syllables = context.assets.open("pinyin_chars.txt").bufferedReader().use { reader ->
                reader.lineSequence().map { it.substringBefore('\t').trim() }
                    .filter { it.isNotEmpty() }.toSet()
            }
            val expansions = com.agentkeyboard.ime.pinyin.T9Pinyin.segmentedExpand(
                "64426",
                syllables,
            )
            assertTrue("T9 expansion missed nihao", "nihao" in expansions)

            val t9 = engine.candidatesForT9("64426", limit = 24)
            assertTrue("T9 candidate list is empty: $t9", t9.isNotEmpty())
            assertTrue("Expected 你好 in T9 candidates: $t9", "你好" in t9)
            val t9TopFive = engine.candidatesForT9("64426", limit = 5)
            assertTrue("Expected 你好 in T9 top five: $t9TopFive", "你好" in t9TopFive)
        } finally {
            engine.close()
        }
    }
}
