package com.agentkeyboard.ime.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.UserContentFlags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MutedText @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.mutedText
private val BodyText @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.bodyText
private val DayHeaderBg @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.systemBg
private val ErrorRed @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.errorText
private val OkGreen @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.okGreen
private val OkBg @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.okBg
private val ErrorBg @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.errorBg
private val WarnOrange @Composable get() = com.agentkeyboard.ui.AppTheme.componentColors.warnText

/** 活动条目展示模型(11 号设计 §5.3 文案模板的解析结果) */
data class ActivityEntryUi(
    val entryId: Long,
    val timeLabel: String,
    val dayLabel: String,
    val toolName: String,
    val outcomeLabel: String,
    val contentBadge: String?,
    val detail: String,
    val denied: Boolean,
)

object ActivityLogUi {

    fun toolDisplayName(toolId: String): String = when (toolId) {
        "web_search" -> "联网搜索"
        "fetch_page" -> "读取网页"
        "insert_text" -> "上屏"
        "read_clipboard" -> "读取剪贴板"
        "llm_call" -> "模型调用"
        "kb_search", "zhipu_knowledge" -> "资料检索"
        else -> "外部工具"
    }

    fun outcomeLabel(outcome: String): String = when (outcome) {
        "AUTO_ALLOW" -> "自动执行"
        "CONFIRMED" -> "你已确认"
        "ALLOWED_ONCE" -> "当场确认"
        "DENIED" -> "你已拒绝"
        "FAILED" -> "执行失败"
        else -> "已记录"
    }

    fun contentBadge(entry: ActivityLogEntity): String? = when (entry.containsUserContent) {
        UserContentFlags.CLIPBOARD -> "📋 含剪贴板内容"
        UserContentFlags.CONTEXT -> "📄 含输入框内容"
        else -> null
    }

    private val dayFormat = SimpleDateFormat("M月d日", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 单条映射(纯函数,可测) */
    fun toEntryUi(entry: ActivityLogEntity): ActivityEntryUi = ActivityEntryUi(
        entryId = entry.id,
        timeLabel = timeFormat.format(Date(entry.ts)),
        dayLabel = dayFormat.format(Date(entry.ts)),
        toolName = toolDisplayName(entry.toolId),
        outcomeLabel = outcomeLabel(entry.outcome),
        contentBadge = contentBadge(entry),
        detail = "",
        denied = entry.outcome == "DENIED",
    )

    /** 按日分组保持倒序(纯函数,可测):key = M月d日,value = 组内条目 */
    fun groupByDay(entries: List<ActivityLogEntity>): List<Pair<String, List<ActivityEntryUi>>> =
        entries.map(::toEntryUi)
            .groupBy { it.dayLabel }
            .map { (day, list) -> day to list }
            .sortedByDescending { parseDayKey(it.first) }
}

private fun parseDayKey(label: String): Long = runCatching {
    SimpleDateFormat("M月d日", Locale.getDefault()).parse(label)?.time ?: 0L
}.getOrDefault(0L)

/** 单条活动条目渲染(11 §5.3:`14:32 · 联网搜索 · 自动执行 · 搜索词"…"`) */
@Composable
fun ActivityEntryRow(entry: ActivityEntryUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.timeLabel, fontSize = 11.sp, color = MutedText)
            Text("·", fontSize = 11.sp, color = MutedText)
            Text(entry.toolName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
            Text("·", fontSize = 11.sp, color = MutedText)
            Text(
                entry.outcomeLabel,
                fontSize = 11.sp,
                color = when {
                    entry.denied -> ErrorRed
                    else -> OkGreen
                },
                modifier = Modifier
                    .background(
                        if (entry.denied) ErrorBg else OkBg,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            entry.contentBadge?.let { badge ->
                Text(badge, fontSize = 10.sp, color = WarnOrange)
            }
        }
        if (entry.detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(entry.detail, fontSize = 11.sp, color = MutedText, maxLines = 2)
        }
    }
}

/** 日分组头 */
@Composable
fun ActivityDayHeader(day: String) {
    Text(
        day,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MutedText,
        modifier = Modifier
            .fillMaxWidth()
            .background(DayHeaderBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
