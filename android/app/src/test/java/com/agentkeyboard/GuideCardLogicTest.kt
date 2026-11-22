package com.agentkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fcitx.fcitx5.android.R

/**
 * M3-C(audit#1)设置引导卡状态检测纯函数测试:
 * 三步勾选(模型/知识库可选/IME)、启用 vs 当前输入法判定、隐藏条件。
 */
class GuideCardLogicTest {

    private val ourId =
        "com.agentkeyboard/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
    private val ourIdFull = ourId

    // 1. IME 启用判定:启用列表精确匹配组件 id(短/全形式归一兼容)
    @Test
    fun `isOurImeEnabled - 启用列表含本组件`() {
        assertTrue(GuideCardLogic.isOurImeEnabled(listOf(ourId, "com.a/.B"), ourId))
        assertTrue(GuideCardLogic.isOurImeEnabled(listOf(ourIdFull), ourId)) // 系统若存全形式
        assertFalse(GuideCardLogic.isOurImeEnabled(listOf("com.a/.B"), ourId))
        assertFalse(GuideCardLogic.isOurImeEnabled(emptyList(), ourId))
    }

    // 2. 当前输入法判定:DEFAULT_INPUT_METHOD == 本组件 id(系统存短形式)
    @Test
    fun `isOurImeCurrent - 默认输入法匹配`() {
        assertTrue(GuideCardLogic.isOurImeCurrent(ourId, ourId))
        assertTrue(GuideCardLogic.isOurImeCurrent(ourIdFull, ourId)) // 全形式兼容
        assertFalse(GuideCardLogic.isOurImeCurrent("com.android.inputmethod.latin/.LatinIME", ourId))
        assertFalse(GuideCardLogic.isOurImeCurrent(null, ourId))
    }

    // 3. 三步构建:done 状态逐项映射;③ 仅启用未选中不算完成(设为当前提示不被隐藏吞掉)
    @Test
    fun `buildSteps - 三步状态映射`() {
        val steps = GuideCardLogic.buildSteps(
            modelConfigured = false, kbHasDocs = true, imeEnabled = true,
        )
        assertEquals(3, steps.size)
        assertEquals("model", steps[0].key)
        assertFalse(steps[0].optional)
        assertFalse(steps[0].done)
        assertEquals("kb", steps[1].key)
        assertTrue(steps[1].optional)
        assertTrue(steps[1].done)
        assertEquals("ime", steps[2].key)
        assertFalse(steps[2].optional)
        assertTrue(steps[2].done)
        // 已启用但非当前:③ 未完成(显示「设为当前输入法」按钮)
        val enabledNotCurrent = GuideCardLogic.buildSteps(
            modelConfigured = true, kbHasDocs = false, imeEnabled = true, imeCurrent = false,
        )
        assertFalse(enabledNotCurrent[2].done)
        val current = GuideCardLogic.buildSteps(
            modelConfigured = true, kbHasDocs = false, imeEnabled = true, imeCurrent = true,
        )
        assertTrue(current[2].done)
    }

    // 4. 隐藏条件:必做(①③)全完成即隐藏;②可选不阻断;不再显示立即隐藏
    @Test
    fun `shouldHide - 必做全完成或用户关闭后隐藏`() {
        val allDone = GuideCardLogic.buildSteps(modelConfigured = true, kbHasDocs = false, imeEnabled = true)
        assertTrue(GuideCardLogic.shouldHide(allDone, dismissed = false)) // ②可选不阻断
        val imeMissing = GuideCardLogic.buildSteps(modelConfigured = true, kbHasDocs = true, imeEnabled = false)
        assertFalse(GuideCardLogic.shouldHide(imeMissing, dismissed = false))
        val modelMissing = GuideCardLogic.buildSteps(modelConfigured = false, kbHasDocs = false, imeEnabled = true)
        assertFalse(GuideCardLogic.shouldHide(modelMissing, dismissed = false))
        // 用户点「不再显示」:即便什么都没配也隐藏
        val allMissing = GuideCardLogic.buildSteps(modelConfigured = false, kbHasDocs = false, imeEnabled = false)
        assertTrue(GuideCardLogic.shouldHide(allMissing, dismissed = true))
    }

    // 4b. M3-C 实测修正:已启用但非当前 → 卡保留(否则用户错过「设为当前」最后一步)
    @Test
    fun `shouldHide - 启用未选中当前输入法时卡片保留`() {
        val enabledNotCurrent = GuideCardLogic.buildSteps(
            modelConfigured = true, kbHasDocs = true, imeEnabled = true, imeCurrent = false,
        )
        assertFalse(GuideCardLogic.shouldHide(enabledNotCurrent, dismissed = false))
    }

    // 5. 全新状态(pm clear):三步全未完成 → 卡片显示
    @Test
    fun `shouldHide - 全新状态显示三步卡`() {
        val fresh = GuideCardLogic.buildSteps(modelConfigured = false, kbHasDocs = false, imeEnabled = false)
        assertFalse(GuideCardLogic.shouldHide(fresh, dismissed = false))
        assertFalse(fresh[0].done)
        assertFalse(fresh[2].done)
    }

    // 6. M3-D 文案精简后副文案仅剩唯一例外:③ 启用未设为当前(其余步骤仅 title+勾,资源已删)
    @Test
    fun `subTextOf 与 titleRes - guide_step 资源全量接线`() {
        fun step(key: String, done: Boolean) =
            GuideCardLogic.GuideStep(key, R.string.guide_title, key == "kb", done)
        // 标题走资源(原硬编码中文)
        val fresh = GuideCardLogic.buildSteps(false, false, false)
        assertEquals(R.string.guide_step_model, fresh[0].titleRes)
        assertEquals(R.string.guide_step_kb, fresh[1].titleRes)
        assertEquals(R.string.guide_step_ime, fresh[2].titleRes)
        // 副文案:仅 ③ 启用未选中保留;①② 及完成态一律 null
        assertEquals(null, GuideCardLogic.subTextOf(step("model", false), false, false))
        assertEquals(null, GuideCardLogic.subTextOf(step("model", true), false, false))
        assertEquals(null, GuideCardLogic.subTextOf(step("kb", true), false, false))
        assertEquals(null, GuideCardLogic.subTextOf(step("kb", false), false, false))
        assertEquals(null, GuideCardLogic.subTextOf(step("ime", false), imeEnabled = false, imeCurrent = false))
        assertEquals(
            R.string.guide_step_ime_enabled_not_current,
            GuideCardLogic.subTextOf(step("ime", false), imeEnabled = true, imeCurrent = false),
        )
        assertEquals(null, GuideCardLogic.subTextOf(step("ime", true), true, true))
    }
}
