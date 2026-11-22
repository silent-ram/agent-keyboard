package com.agentkeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.agent.AgentKit
import com.agentkeyboard.mcp.McpServerConfig
import com.agentkeyboard.mcp.McpServerStore
import com.agentkeyboard.mcp.McpToolBridge
import kotlinx.coroutines.launch

/**
 * G12 MCP 服务器管理(设置页区块):
 * - 仅用户显式添加的远程 server;名称/URL/可选 Authorization;
 * - 保存/删除/开关后立即强制同步工具,状态行展示工具数或失败原因(失败诚实);
 * - 工具调用授权由 PermissionGate 逐次询问,本页不提供"免授权"开关。
 */
@Composable
fun McpServersSection(kit: AgentKit) {
    val context = LocalContext.current
    val store = remember { McpServerStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<McpServerConfig>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<McpToolBridge.ServerStatus>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            servers = store.list()
            statuses = kit.mcpBridge.lastStatus
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MCP 连接", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = BodyText)
        servers.forEach { server ->
            val status = statuses.firstOrNull { it.serverId == server.id }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, color = BodyText, fontSize = 14.sp)
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { checked ->
                            if (busy) return@Switch
                            busy = true
                            scope.launch {
                                store.setEnabled(server.id, checked)
                                kit.mcpBridge.syncInto(kit.registry, force = true)
                                busy = false
                                refresh()
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !server.enabled -> "已停用"
                            status?.error != null -> "同步失败"
                            status != null -> "${status.toolCount} 个工具已就绪"
                            else -> "未同步"
                        },
                        color = if (status?.error != null) WarningText else MutedText,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            scope.launch {
                                store.delete(server.id)
                                kit.mcpBridge.syncInto(kit.registry, force = true)
                                busy = false
                                refresh()
                            }
                        },
                    ) { Text("删除", fontSize = 12.sp) }
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        TextButton(onClick = { showAdd = true }, enabled = !busy) { Text("添加服务器") }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var auth by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) showAdd = false },
            title = { Text("添加 MCP 服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("名称") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("服务地址") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    )
                    OutlinedTextField(
                        value = auth, onValueChange = { auth = it },
                        label = { Text("授权凭据（可选）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    )
                    error?.let { Text(it, color = WarningText, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isBlank() || url.isBlank()) {
                            error = "名称与 URL 必填"
                            return@TextButton
                        }
                        busy = true
                        scope.launch {
                            val saved = runCatching {
                                store.add(name, url, auth.ifBlank { null })
                                kit.mcpBridge.syncInto(kit.registry, force = true)
                            }
                            busy = false
                            if (saved.isSuccess) {
                                showAdd = false
                                refresh()
                            } else {
                                error = "保存失败，请检查服务地址和授权凭据"
                            }
                        }
                    },
                ) { Text("保存并连接") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showAdd = false }) { Text("取消") }
            },
        )
    }
}

private val WarningText get() = com.agentkeyboard.ui.AppTheme.colors.warnText
