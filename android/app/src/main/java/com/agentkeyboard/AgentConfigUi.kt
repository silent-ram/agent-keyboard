package com.agentkeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.agentkeyboard.agent.AgentProfileStore
import com.agentkeyboard.agent.ProfileSaveResult
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.tools.ToolIds
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.agent.AgentKit
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.AgentMemoryEntity
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.ui.AppTheme
import com.agentkeyboard.ui.AssistantIcon
import org.fcitx.fcitx5.android.R
import kotlinx.coroutines.launch

/**
 * Agent 完整配置界面(App 默认入口,2026-11-22 交互改版):
 * 模型连接 / MCP / Skills 能力 / Memory 显式记忆 / user.md / (对话管理见底部 tab)。
 * 设置页(Settings)仅保留输入法相关配置。
 */
@Composable
fun AgentConfigScreen(
    store: ModelStore,
    settingsRepository: SettingsRepository,
    agentKit: AgentKit,
    onOpenManage: () -> Unit,
) {
    var configRevision by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModelConnectionSection(store, configRevision, onOpenManage)
        HorizontalDivider(color = AppTheme.colors.lineGray)
        ConfigBackupSection(store) { configRevision++ }
        HorizontalDivider(color = AppTheme.colors.lineGray)
        McpServersSection(kit = agentKit)
        HorizontalDivider(color = AppTheme.colors.lineGray)
        SkillsSection(agentKit)
        HorizontalDivider(color = AppTheme.colors.lineGray)
        MemorySection(settingsRepository)
        HorizontalDivider(color = AppTheme.colors.lineGray)
        UserMdSection(settingsRepository)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
}

@Composable
private fun ModelConnectionSection(store: ModelStore, configRevision: Int, onOpenManage: () -> Unit) {
    var modelLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store, configRevision) {
        modelLabel = runCatching {
            val file = store.load()
            com.agentkeyboard.data.model.ModelConfigLogic.resolveDefaultModel(file)?.display()
        }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenManage)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AssistantIcon(R.drawable.ic_baseline_tune_24, tint = PrimaryBlue)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("模型管理")
                Text(
                    modelLabel ?: "未配置",
                    color = MutedText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AssistantIcon(R.drawable.ic_baseline_keyboard_arrow_right_24)
        }
    }
}

/** Skills:当前 Agent 可调用的能力(名称即可,不做冗长说明)。 */
@Composable
private fun SkillsSection(agentKit: AgentKit) {
    var skills by remember { mutableStateOf<List<String>>(emptyList()) }
    // MCP 工具在装配预热/任务前同步后才进注册表;页面进入时读一次,
    // 并在 MCP 区块触发同步后刷新(registry 无变更通知,故由 McpServersSection 回调驱动)
    LaunchedEffect(agentKit) {
        skills = agentKit.registry.schemas().map {
            when (it.id) {
                ToolIds.KB_SEARCH -> "资料检索"
                ToolIds.WEB_SEARCH -> "联网搜索"
                else -> "外部工具"
            }
        }.distinct()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("工具能力")
        if (skills.isEmpty()) {
            Text("暂无", color = MutedText, fontSize = 13.sp)
        } else {
            skills.forEach { name ->
                Text(
                    "· $name",
                    color = BodyText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** Memory:显式记忆(G5),完全显式、完全可删、不出网,注入系统提示。 */
@Composable
private fun MemorySection(settings: SettingsRepository) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, settings) { AgentProfileStore(AgentDatabase.get(context), settings) }
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<AgentMemoryEntity>>(emptyList()) }
    var newFact by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { memories = store.memories() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("记忆")
        memories.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    m.fact,
                    color = BodyText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            store.deleteMemory(m.id)
                            memories = store.memories()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            notice = "删除失败"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("删除", color = ErrorText) }
            }
        }
        ProfileEditor(newFact, { newFact = it; notice = null }, "新增记忆", 2, !busy)
        Button(
            modifier = Modifier.align(Alignment.End),
            onClick = {
                busy = true
                scope.launch {
                    notice = try {
                        val safety = AgentFactory.historySafety(context)
                        val outcome = if (safety == null || safety.hasConfig(newFact)) ProfileSaveResult.INVALID
                            else store.saveMemory(newFact)
                        if (outcome == ProfileSaveResult.SAVED) {
                            newFact = ""
                            memories = store.memories()
                        }
                        profileNotice(outcome)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        "保存失败"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && newFact.isNotBlank(),
        ) { Text("添加") }
        notice?.let { Text(it, color = ErrorText) }
    }
}

/** user.md:用户自述背景,注入 Agent 系统提示。 */
@Composable
private fun UserMdSection(settingsRepository: SettingsRepository) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, settingsRepository) { AgentProfileStore(AgentDatabase.get(context), settingsRepository) }
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { saved = store.userNote(); content = saved }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("用户说明")
        ProfileEditor(content.orEmpty(), { content = it; notice = null }, "添加说明", 4, !busy && content != null)
        Button(
            modifier = Modifier.align(Alignment.End),
            enabled = content != null && content != saved && !busy,
            onClick = {
                busy = true
                scope.launch {
                    notice = try {
                        val draft = content.orEmpty().trim()
                        val safety = AgentFactory.historySafety(context)
                        val outcome = if (draft.isNotEmpty() && (safety == null || safety.hasConfig(draft))) ProfileSaveResult.INVALID
                            else store.saveUserNote(draft)
                        if (outcome == ProfileSaveResult.SAVED) {
                            saved = draft
                            content = draft
                        }
                        profileNotice(outcome)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        "保存失败"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("保存") }
        notice?.let { Text(it, color = ErrorText) }
    }
}

private fun profileNotice(result: ProfileSaveResult): String? = when (result) {
    ProfileSaveResult.SAVED -> null
    ProfileSaveResult.ALREADY_PRESENT -> "已有相同内容"
    ProfileSaveResult.INVALID -> "内容过长或含有敏感信息"
}

@Composable
private fun ProfileEditor(value: String, onValueChange: (String) -> Unit, hint: String, lines: Int, enabled: Boolean) {
    val colors = AppTheme.componentColors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = { Text(hint) },
        minLines = lines,
        maxLines = lines + 2,
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceAlt,
            unfocusedContainerColor = colors.surfaceAlt,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.lineGray,
        ),
    )
}
