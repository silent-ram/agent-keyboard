package com.agentkeyboard.ime.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.AgentSessionEntity
import com.agentkeyboard.ui.AppTheme
import com.agentkeyboard.ui.AssistantIconButton
import org.fcitx.fcitx5.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MutedText @Composable get() = AppTheme.componentColors.mutedText
private val BodyText @Composable get() = AppTheme.componentColors.bodyText

/** 会话列表项 UI 模型(标题 = 首条用户输入前 20 字) */
data class SessionListItem(
    val sessionId: Long,
    val title: String,
    val timeLabel: String,
    val statusLabel: String,
    val failed: Boolean = false,
)

internal class SessionDeleteConfirmation {
    var pendingId by mutableStateOf<Long?>(null)
        private set

    fun request(sessionId: Long) {
        pendingId = sessionId
    }

    fun cancel() {
        pendingId = null
    }

    fun confirm(sessionId: Long): Long? {
        if (pendingId != sessionId) return null
        cancel()
        return sessionId
    }
}

/**
 * 🕘 弹层(12 号设计 §6):双标签——「会话」列表 /「最近活动」日志。
 * 会话点击载入续问;最近活动按日分组(11 §5.3);均只读展示。
 */
@Composable
fun HistoryDrawer(
    tab: Int,
    onTab: (Int) -> Unit,
    sessions: List<SessionListItem>,
    activity: List<ActivityLogEntity>,
    onOpenSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    canDelete: Boolean,
) {
    val deletion = remember(tab, canDelete) { SessionDeleteConfirmation() }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerTab("会话", tab == 0) { onTab(0) }
            DrawerTab("最近活动", tab == 1) { onTab(1) }
        }
        HorizontalDivider(color = AppTheme.componentColors.lineGray)
        if (tab == 0) {
            if (sessions.isEmpty()) {
                EmptyHint("暂无会话")
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sessions, key = { it.sessionId }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val confirming = deletion.pendingId == s.sessionId
                            Column(
                                Modifier.weight(1f)
                                    .clickable(enabled = !confirming) { onOpenSession(s.sessionId) }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(s.title, fontSize = 13.sp, color = BodyText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text("${s.timeLabel} · ${s.statusLabel}", fontSize = 10.sp, color = MutedText)
                            }
                            if (!confirming) {
                                AssistantIconButton(
                                    icon = R.drawable.ic_baseline_delete_24,
                                    description = stringResource(R.string.panel_delete_session),
                                    onClick = { deletion.request(s.sessionId) },
                                    enabled = canDelete,
                                )
                            }
                        }
                        if (deletion.pendingId == s.sessionId) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = deletion::cancel) {
                                    Text(stringResource(R.string.settings_cancel), color = BodyText)
                                }
                                TextButton(onClick = {
                                    deletion.confirm(s.sessionId)?.let(onDeleteSession)
                                }) {
                                    Text(
                                        stringResource(R.string.panel_confirm_delete_session),
                                        color = AppTheme.componentColors.errorText,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = AppTheme.componentColors.lineGray)
                    }
                }
            }
        } else {
            if (activity.isEmpty()) {
                EmptyHint("暂无活动")
            } else {
                val grouped = ActivityLogUi.groupByDay(activity)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    grouped.forEach { (day, entries) ->
                        item(key = "day_$day") { ActivityDayHeader(day) }
                        items(entries, key = { "log_${it.entryId}" }) { e ->
                            ActivityEntryRow(e)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.componentColors
    TextButton(onClick = onClick, shape = androidx.compose.material3.MaterialTheme.shapes.small) {
        Text(label, color = if (selected) colors.chipText else colors.bodyText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.background(if (selected) colors.chipBg else androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.material3.MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MutedText,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
    )
}

/** 会话实体 → 列表项(纯函数映射,可测;标题由 [PanelReducer.sessionTitle] 生成) */
fun sessionToListItem(session: AgentSessionEntity, title: String): SessionListItem {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(session.createdAt))
    val status = when (session.status) {
        "RUNNING" -> "运行中"
        "DONE" -> "已完成"
        "FAILED" -> "失败"
        "STOPPED" -> "已停止"
        else -> session.status
    }
    return SessionListItem(
        sessionId = session.id,
        title = title,
        timeLabel = time,
        statusLabel = status,
        failed = session.status == "FAILED",
    )
}
