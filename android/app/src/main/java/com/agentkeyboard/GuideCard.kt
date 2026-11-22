package com.agentkeyboard

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R

/**
 * M3-C(audit#1)设置引导卡:配置 24 步最大流失点的首屏三步引导。
 * ①配置模型(必做) ②导入知识库(可选) ③启用键盘(必做);
 * 每步状态自动检测(模型已配/有文档/IME 已启用),完成态打勾,未完成带直达按钮;
 * 全部完成或用户点「不再显示」(DataStore)后隐藏。
 */
object GuideCardLogic {

    /**
     * IME 组件 id(短形式 `pkg/.Service`):系统 ENABLED_INPUT_METHODS 与
     * DEFAULT_INPUT_METHOD 存的都是 InputMethodInfo.getId()(= flattenToShortString),
     * 不能用 flattenToString 全形式比较(实测模拟器:全形式比较恒不等,③永不打勾)。
     */
    fun ourImeId(context: Context): String =
        android.content.ComponentName(
            context,
            org.fcitx.fcitx5.android.input.FcitxInputMethodService::class.java,
        )
            .flattenToShortString()

    /** 兼容短/全两种形式的组件 id 归一:短形式 `pkg/.Cls` → 全形式 `pkg/pkg.Cls` */
    private fun normalizeImeId(id: String): String? {
        val pkg = id.substringBefore('/', missingDelimiterValue = "")
        val cls = id.substringAfter('/', missingDelimiterValue = "")
        if (pkg.isEmpty() || cls.isEmpty()) return null
        val fullCls = if (cls.startsWith(".")) pkg + cls else cls
        return "$pkg/$fullCls"
    }

    /** 键盘是否已在系统启用(启用列表含本应用;兼容短/全两种 id 形式) */
    fun isOurImeEnabled(enabledIds: List<String>, ourId: String): Boolean {
        val want = normalizeImeId(ourId) ?: return ourId in enabledIds
        return enabledIds.any { normalizeImeId(it) == want }
    }

    /** 键盘是否为当前输入法(DEFAULT_INPUT_METHOD == 本组件;兼容短/全两种 id 形式) */
    fun isOurImeCurrent(defaultIme: String?, ourId: String): Boolean {
        if (defaultIme == null) return false
        val want = normalizeImeId(ourId) ?: return defaultIme == ourId
        return normalizeImeId(defaultIme) == want
    }

    /** 真实检测(enabled, current);系统服务不可用时按未启用处理 */
    fun detectIme(context: Context): Pair<Boolean, Boolean> {
        val ourId = ourImeId(context)
        return runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabledList: List<android.view.inputmethod.InputMethodInfo> =
                imm?.getEnabledInputMethodList() ?: emptyList()
            // M3-C 评审 P3:复用 isOurImeEnabled(内部经 normalizeImeId 归一),消除双轨 == 比较
            val enabled = isOurImeEnabled(enabledList.map { it.id }, ourId)
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            enabled to isOurImeCurrent(current, ourId)
        }.getOrDefault(false to false)
    }

    /**
     * 引导步骤(key/titleRes/optional/done);纯函数,状态检测输入由调用方注入。
     * M3-C 评审 P3:title 由硬编码中文改为 string 资源引用(guide_step_* 原死资源接线)。
     */
    data class GuideStep(
        val key: String,
        val titleRes: Int,
        val optional: Boolean,
        val done: Boolean,
    )

    /**
     * 三步状态。③ 的完成判定 = 已启用 **且** 为当前输入法:仅启用未选中时,
     * 显示「设为当前输入法」直达按钮(system picker),不算完成——否则卡会被隐藏,
     * 用户错过最后一步(audit#1 流失点;模拟器实测发现并修正)。
     */
    fun buildSteps(
        modelConfigured: Boolean,
        kbHasDocs: Boolean,
        imeEnabled: Boolean,
        imeCurrent: Boolean = imeEnabled,
    ): List<GuideStep> = listOf(
        GuideStep(key = "model", titleRes = R.string.guide_step_model, optional = false, done = modelConfigured),
        GuideStep(key = "kb", titleRes = R.string.guide_step_kb, optional = true, done = kbHasDocs),
        GuideStep(key = "ime", titleRes = R.string.guide_step_ime, optional = false, done = imeEnabled && imeCurrent),
    )

    /**
     * 每步副文案(M3-D 文案精简):控件/按钮已自解释,仅保留唯一例外——
     * ③ 已启用但未设为当前时,「设为当前输入法」按钮的语境说明;其余步骤返回 null(仅 title+勾)。
     */
    fun subTextOf(step: GuideStep, imeEnabled: Boolean, imeCurrent: Boolean): Int? = when {
        step.key == "ime" && !step.done && imeEnabled && !imeCurrent ->
            R.string.guide_step_ime_enabled_not_current
        else -> null
    }

    /** 卡片是否隐藏:必做步骤全部完成即认为全部完成(②为可选,不阻断);或用户已点不再显示 */
    fun shouldHide(steps: List<GuideStep>, dismissed: Boolean): Boolean =
        dismissed || steps.filterNot { it.optional }.all { it.done }
}

/**
 * 设置引导卡 UI(12 号设计风格:浅蓝底卡片,完成态 ✓,未完成带直达按钮)。
 * 状态检测在 [MainActivity.SettingsScreen] 的生命周期观察里完成(onResume 刷新,
 * 覆盖「从系统启用页返回」场景)。
 */
@Composable
fun SetupGuideCard(
    modelConfigured: Boolean,
    kbHasDocs: Boolean,
    imeEnabled: Boolean,
    imeCurrent: Boolean,
    onOpenModelManage: () -> Unit,
    onEnableIme: () -> Unit,
    onPickIme: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = com.agentkeyboard.ui.AppTheme.colors
    val steps = GuideCardLogic.buildSteps(modelConfigured, kbHasDocs, imeEnabled, imeCurrent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.chipBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            stringResource(R.string.guide_title),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.bodyText,
        )
        Spacer(Modifier.height(10.dp))
        steps.forEach { step ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (step.done) "✓" else "○",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (step.done) colors.okGreen else colors.mutedText,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(step.titleRes),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (step.done) colors.bodyText else colors.sectionText,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (!step.done && step.key == "model") {
                        GuideButton(stringResource(R.string.guide_btn_config_model), onOpenModelManage)
                    }
                    if (!step.done && step.key == "ime") {
                        if (!imeEnabled) {
                            GuideButton(stringResource(R.string.guide_btn_enable_ime), onEnableIme)
                        } else if (!imeCurrent) {
                            GuideButton(stringResource(R.string.guide_btn_set_current), onPickIme)
                        }
                    }
                }
                // M3-C 评审 P3:guide_step_* 死资源接线为每步副文案
                GuideCardLogic.subTextOf(step, imeEnabled, imeCurrent)?.let { res ->
                    Text(
                        stringResource(res),
                        fontSize = 11.sp,
                        color = colors.mutedText,
                        modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (steps.filterNot { it.optional }.all { it.done }) {
                Text(
                    stringResource(R.string.guide_all_done),
                    fontSize = 11.sp, color = colors.okGreen,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // M3-D:未完成态的「✨帮写 / 🤖问AI 需要先完成以上步骤」劝导行删除——
                // 每步 title+直达按钮已自解释,不属于安全/空态/隐私三类保留文案;占位保住「不再显示」靠右
                Spacer(Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.guide_dismiss),
                fontSize = 12.sp, color = colors.mutedText,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun GuideButton(label: String, onClick: () -> Unit) {
    val colors = com.agentkeyboard.ui.AppTheme.colors
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
        modifier = Modifier
            .background(colors.primary, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
