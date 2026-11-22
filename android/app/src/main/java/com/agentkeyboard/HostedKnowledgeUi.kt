package com.agentkeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.RetentionPolicy
import com.agentkeyboard.data.db.UserContentFlags
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.rag.KnowledgeProvider
import com.agentkeyboard.rag.ZhipuKnowledgeBase
import com.agentkeyboard.rag.ZhipuKnowledgeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HostedKnowledgeSection() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ModelStore(context) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(ZhipuKnowledgeSettings() to null as String?) }
    var provider by remember { mutableStateOf(KnowledgeProvider.ZHIPU) }
    var url by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingLibraries?>(null) }

    fun perform(failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = failure
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(store) {
        perform("资料库配置读取失败") {
            saved = store.loadKnowledgeConnection()
            provider = saved.first.provider
            url = saved.first.baseUrl
            ready = true
        }
    }

    val address = provider.validBaseUrl(url)
    val isSavedConnection = provider == saved.first.provider && address == saved.first.baseUrl
    val key = keyInput.trim().ifEmpty { saved.second.orEmpty().takeIf { isSavedConnection }.orEmpty() }
    val hasSelection = isSavedConnection && saved.first.knowledgeId.isNotBlank()

    fun refresh() {
        val endpoint = address ?: run {
            error = if (provider == KnowledgeProvider.DIFY) "请输入以 /v1 结尾的 HTTPS 地址"
            else "请输入以 /api/v1 结尾的 HTTPS 地址"
            return
        }
        val selectedProvider = provider
        val expected = saved
        perform("无法获取资料库，请检查连接和 API Key") {
            val libraries = try {
                selectedProvider.connect(endpoint, key).list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordLibraryRefresh(context, LogOutcomes.FAILED)
                throw e
            }
            recordLibraryRefresh(context, LogOutcomes.CONFIRMED)
            if (libraries.isEmpty()) {
                error = "没有可选资料库"
            } else {
                pending = PendingLibraries(selectedProvider, endpoint, key, libraries, expected)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KnowledgeProvider.entries.forEach { option ->
                FilterChip(
                    selected = provider == option,
                    onClick = {
                        if (provider != option) {
                            provider = option
                            url = saved.first.baseUrl.takeIf { option == saved.first.provider }.orEmpty()
                            keyInput = ""
                            error = null
                        }
                    },
                    label = { Text(option.label) },
                    enabled = ready && !busy,
                )
            }
        }
        if (provider != KnowledgeProvider.ZHIPU) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("服务地址") },
                enabled = ready && !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it; error = null },
            label = { Text(if (isSavedConnection && saved.second != null) "更换 API Key" else "API Key") },
            enabled = ready && !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        val action = if (busy) "正在连接…" else if (hasSelection) "已选资料库 · 更换" else "选择资料库"
        if (hasSelection) {
            OutlinedButton(onClick = ::refresh, enabled = ready && !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        } else {
            Button(onClick = ::refresh, enabled = ready && !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = ErrorText) }
    }

    pending?.let { selection ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("选择资料库") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(selection.libraries, key = { _, library -> library.id }) { index, library ->
                        val selected = isSavedConnection && saved.first.knowledgeId == library.id
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton) {
                                pending = null
                                val settings = ZhipuKnowledgeSettings(
                                    library.id, library.name, library.documentCount, selection.provider, selection.url,
                                )
                                perform("资料库保存失败，请重新选择") {
                                    store.saveKnowledgeConnection(settings, selection.key, selection.expected)
                                    saved = settings to selection.key
                                    keyInput = ""
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected, onClick = null)
                            Text(libraryOptionLabel(index, library.documentCount), color = BodyText, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
}

internal fun libraryOptionLabel(index: Int, documentCount: Int?): String =
    "资料库 ${index + 1}" + (documentCount?.let { " · $it 个文档" } ?: "")

private data class PendingLibraries(
    val provider: KnowledgeProvider,
    val url: String,
    val key: String,
    val libraries: List<ZhipuKnowledgeBase>,
    val expected: Pair<ZhipuKnowledgeSettings, String?>,
)

private suspend fun recordLibraryRefresh(context: android.content.Context, outcome: String) {
    val now = System.currentTimeMillis()
    val dao = AgentDatabase.get(context).activityLogDao()
    dao.insert(ActivityLogEntity(
        ts = now, toolId = "zhipu_knowledge", outcome = outcome, containsUserContent = UserContentFlags.NONE,
        target = "资料检索", summary = "刷新资料库列表", preview = "", sessionId = null,
    ))
    dao.cleanup(now - RetentionPolicy.LOG_TTL_MS, RetentionPolicy.MAX_LOG_ENTRIES)
}
