package com.agentkeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import com.agentkeyboard.ui.AppTheme
import com.agentkeyboard.ui.AssistantPageBar
import com.agentkeyboard.ui.AssistantTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.db.AgentDatabase
import org.fcitx.fcitx5.android.R
import com.agentkeyboard.ime.panel.ActivityDayHeader
import com.agentkeyboard.ime.panel.ActivityEntryRow
import com.agentkeyboard.ime.panel.ActivityLogUi
import com.agentkeyboard.data.db.RetentionPolicy

// BodyText/MutedText 共用 MainActivity.kt 的 internal 声明(M2-T4)

/**
 * 最近 AI 活动(11-权限设计 §5):activity_log 按日分组,保留策略内倒序。
 * 全局入口 = 设置页;对话管理可按会话过滤(2026-11-22,EXTRA_SESSION_ID)。
 * 空态文案照 §5.3。
 */
class RecentActivityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0 }
        val dao = AgentDatabase.get(applicationContext).activityLogDao()
        setContent {
            AssistantTheme {
                val entries by (
                    sessionId?.let { dao.bySession(it, RetentionPolicy.MAX_LOG_ENTRIES) }
                        ?: dao.recent(RetentionPolicy.MAX_LOG_ENTRIES)
                    ).collectAsState(initial = emptyList())
                ActivityListScreen(entries, sessionScoped = sessionId != null, onBack = ::finish)
            }
        }
    }

    companion object {
        /** 对话管理入口:仅展示该会话绑定的 AI 活动记录 */
        const val EXTRA_SESSION_ID = "session_id"
    }
}

@Composable
private fun ActivityListScreen(
    entries: List<com.agentkeyboard.data.db.ActivityLogEntity>,
    sessionScoped: Boolean,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.surface)
            .safeDrawingPadding(),
    ) {
        AssistantPageBar(
            title = if (sessionScoped) "该对话的活动记录" else stringResource(R.string.activity_title),
            backLabel = stringResource(R.string.qa_back),
            onBack = onBack,
        )
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.activity_empty),
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            val grouped = ActivityLogUi.groupByDay(entries)
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                grouped.forEach { (day, items) ->
                    item(key = "day_$day") { ActivityDayHeader(day) }
                    items(items, key = { "log_${it.entryId}" }) { e ->
                        ActivityEntryRow(e)
                    }
                }
            }
        }
    }
}
