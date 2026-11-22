package com.agentkeyboard.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @Test
    fun `approved standing preference appends once and does not replace user notes`() = runBlocking {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        repo.saveUserMd("已有说明")
        assertEquals(com.agentkeyboard.agent.ProfileSaveResult.SAVED, repo.appendUserMdLine("先给结论"))
        assertEquals(com.agentkeyboard.agent.ProfileSaveResult.ALREADY_PRESENT, repo.appendUserMdLine("先给结论"))
        assertEquals("已有说明\n先给结论", repo.loadUserMd())
        assertEquals(com.agentkeyboard.agent.ProfileSaveResult.INVALID, repo.appendUserMdLine("x".repeat(4_000)))
        assertEquals("已有说明\n先给结论", repo.loadUserMd())
    }

    @Test
    fun `knowledgeEnabled - 写后读回一致`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        assertTrue(repo.isKnowledgeEnabled())
        // 切换 → 持久化 → 重读(模拟收面板重开)
        repo.setKnowledgeEnabled(false)
        assertFalse(repo.isKnowledgeEnabled())
        // 再切换回默认
        repo.setKnowledgeEnabled(true)
        assertTrue(repo.isKnowledgeEnabled())
    }

    /** M3-C(audit#8)按键震动开关:默认开,切换 → 持久化 → 重读一致 */
    @Test
    fun `hapticEnabled - 默认开且写后读回一致`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        assertTrue(repo.isHapticEnabled()) // 默认开
        repo.setHapticEnabled(false)
        assertFalse(repo.isHapticEnabled())
        repo.setHapticEnabled(true)
        assertTrue(repo.isHapticEnabled())
    }

    @Test
    fun `keyboardLayout - 未知值回退由调用方处理且写后读回一致`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        repo.setKeyboardLayout("T9")
        assertEquals("T9", repo.keyboardLayout())
        repo.setKeyboardLayout("QWERTY")
        assertEquals("QWERTY", repo.keyboardLayout())
    }

    @Test
    fun `keyboardHeightScale - 默认一档且只持久化三档`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, repo.keyboardHeightScale(), 0.0001f)

        repo.setKeyboardHeightScale(KeyboardHeight.MIN_SCALE)
        assertEquals(KeyboardHeight.MIN_SCALE, repo.keyboardHeightScale(), 0.0001f)
        repo.setKeyboardHeightScale(0.96f)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, repo.keyboardHeightScale(), 0.0001f)
        repo.setKeyboardHeightScale(2f)
        assertEquals(KeyboardHeight.MAX_SCALE, repo.keyboardHeightScale(), 0.0001f)
    }

    @Test
    fun `userDictionary - JSON 写后读回一致`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        val json = "[{\"key\":\"nihao\",\"word\":\"您好\",\"uses\":2}]"

        repo.saveUserDictionary(json)

        assertEquals(json, repo.userDictionary())
    }

    /** M3-C(audit#1)引导卡「不再显示」:默认显示(false),点击后隐藏(true) */
    @Test
    fun `guideDismissed - 默认显示且写后读回一致`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        assertFalse(repo.isGuideDismissed()) // 默认显示引导卡
        repo.setGuideDismissed(true)
        assertTrue(repo.isGuideDismissed())
        repo.setGuideDismissed(false)
        assertFalse(repo.isGuideDismissed())
    }
}
