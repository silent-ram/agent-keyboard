package com.agentkeyboard.ime.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest

// 视觉常量(M3-C audit#7:与 PanelView 一致走 AppTheme light/dark)
private val PrimaryBlue @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.primary
private val BodyText @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.bodyText
private val Surface @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.surface
private val OnPrimary @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.onPrimary

/** 用户决定:「总是允许」时 rememberAlways=true(实现方须先 setAlwaysAllow 再放行,见 ConfirmationResolver KDoc) */
data class ConfirmationDecision(val allowed: Boolean, val rememberAlways: Boolean = false)

/**
 * 确认条(11-权限设计 §6.2 三形态 + llm_call 首次明示;面板顶部单槽区最高优先级)。
 * agent 在 resolver 挂起等待本条的用户决定,不超时、不自动放行;「拒绝」= 回喂 user_denied。
 * 「总是允许」的 5s 撤销条已提升到 PanelScreen(P1-1)——点「总是允许」后确认条会随
 * resolver 恢复离开组合,撤销状态不能留在本组件内部。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmationBar(
    request: ConfirmationRequest,
    onDecision: (ConfirmationDecision) -> Unit,
) {
    val actions = PanelReducer.actionsFor(request)
    val isDerived = request.kind == ConfirmationKind.DERIVED_BOTTOM_LINE
    val colors = com.agentkeyboard.ui.AppTheme.componentColors
    val foreground = if (isDerived) colors.shieldText else colors.bodyText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(if (isDerived) colors.shieldBg else colors.surfaceAlt, MaterialTheme.shapes.small)
            .border(1.dp, if (isDerived) colors.shieldBorder else colors.lineGray, MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(request.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = foreground)
            Text(request.body, fontSize = 12.sp, color = foreground)
        }

        Spacer(Modifier.height(8.dp))

        // 按钮组(按形态;派生底线无「总是允许」)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (actions.allowOnce) {
                BarButton(
                    label = if (request.kind == ConfirmationKind.LLM_DISCLOSURE) "同意并继续" else if (isDerived) "允许一次" else "本次允许",
                    primary = true,
                ) { onDecision(ConfirmationDecision(allowed = true, rememberAlways = false)) }
            }
            TextButton(
                onClick = { onDecision(ConfirmationDecision(allowed = false)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (request.kind == ConfirmationKind.LLM_DISCLOSURE) "暂不使用" else "拒绝", color = foreground)
            }
            if (actions.allowAlways && request.canRemember) {
                BarButton(label = "总是允许", primary = false) {
                    onDecision(ConfirmationDecision(allowed = true, rememberAlways = true))
                }
            }
            if (actions.askEveryTime && request.canRemember) {
                BarButton(label = "总是询问", primary = false) {
                    onDecision(ConfirmationDecision(allowed = true, rememberAlways = false))
                }
            }
        }

    }
}

@Composable
private fun BarButton(label: String, primary: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (primary) PrimaryBlue else Surface,
            contentColor = if (primary) OnPrimary else BodyText,
        ),
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
