package com.agentkeyboard.ime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.agent.AgentProfileStore
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.agent.ProfileSaveResult
import com.agentkeyboard.agent.ProfileSuggestion
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.AgentMemoryEntity
import com.agentkeyboard.ui.AppTheme
import com.agentkeyboard.ui.AssistantIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R

@Composable
internal fun ProfileSuggestionRow(
    suggestion: ProfileSuggestion,
    saving: Boolean,
    onDecision: (ProfileSuggestion, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        HorizontalDivider(color = AppTheme.componentColors.lineGray)
        Text(suggestion.text, color = AppTheme.componentColors.bodyText, fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = { onDecision(suggestion, false) }, enabled = !saving) {
                Text(stringResource(R.string.panel_profile_ignore))
            }
            TextButton(onClick = { onDecision(suggestion, true) }, enabled = !saving) {
                Text(stringResource(when (suggestion.kind) {
                    ProfileSuggestion.Kind.MEMORY -> R.string.panel_profile_save_memory
                    ProfileSuggestion.Kind.USER_NOTE -> R.string.panel_profile_save_note
                }))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilePanel(state: PanelUiState, callbacks: PanelCallbacks) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AgentProfileStore(AgentDatabase.get(context), SettingsRepository(context)) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<AgentMemoryEntity>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var editId by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var ready by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableStateOf(0) }
    val editor = state.profileEditValue

    suspend fun refresh() {
        rows = store.memories()
        note = store.userNote()
    }

    LaunchedEffect(reload) {
        error = null
        try {
            refresh()
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.panel_profile_load_failed
        }
    }

    fun edit(id: Long?, text: String) {
        if (working) return
        editId = id
        deleteId = null
        error = null
        callbacks.onStartProfileEdit(text)
    }

    fun cancelEdit() {
        callbacks.onStopProfileEdit()
        error = null
    }

    fun write(failure: Int = R.string.panel_profile_failed, change: suspend () -> ProfileSaveResult) {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                error = when (change()) {
                    ProfileSaveResult.SAVED -> {
                        callbacks.onStopProfileEdit()
                        deleteId = null
                        ready = false
                        reload += 1
                        null
                    }
                    ProfileSaveResult.ALREADY_PRESENT -> R.string.panel_profile_duplicate
                    ProfileSaveResult.INVALID -> R.string.panel_profile_invalid
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = failure
            } finally {
                working = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistantIconButton(R.drawable.ic_baseline_arrow_back_24, stringResource(R.string.panel_profile_back),
                onClick = { if (editor != null) cancelEdit() else callbacks.onCloseProfile() }, enabled = !working)
            Text(stringResource(R.string.panel_profile), color = AppTheme.componentColors.bodyText, fontSize = 14.sp)
        }
        SecondaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.panel_profile_memory, R.string.panel_profile_note).forEachIndexed { i, title ->
                Tab(selected = tab == i, enabled = !working && editor == null, onClick = {
                    tab = i
                    deleteId = null
                    if (ready) error = null
                }, text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            error?.let { label -> item {
                Text(stringResource(label), color = AppTheme.componentColors.errorText, fontSize = 13.sp)
            } }
            if (!ready) {
                item {
                    if (error == null) CircularProgressIndicator(Modifier.padding(8.dp))
                    else TextButton(onClick = { reload += 1 }) { Text(stringResource(R.string.panel_profile_retry)) }
                }
            } else if (editor != null) {
                item {
                    PanelTextField(editor, callbacks::onProfileInputChanged, state.imeInputMode,
                        stringResource(if (tab == 0) R.string.panel_profile_memory else R.string.panel_profile_note),
                        Modifier.fillMaxWidth().padding(top = 8.dp), minLines = if (tab == 0) 2 else 3)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::cancelEdit, enabled = !working) { Text(stringResource(R.string.panel_profile_cancel)) }
                        Button(onClick = {
                            val draft = editor.text
                            val id = editId
                            val memory = tab == 0
                            write {
                                val safety = AgentFactory.historySafety(context)
                                when {
                                    draft.isNotBlank() && (safety == null || safety.hasConfig(draft)) -> ProfileSaveResult.INVALID
                                    memory -> store.saveMemory(draft, id)
                                    else -> store.saveUserNote(draft)
                                }
                            }
                        }, enabled = !working && (tab == 1 || editor.text.isNotBlank())) {
                            Text(stringResource(R.string.panel_profile_save))
                        }
                    }
                }
            } else if (tab == 0) {
                item { TextButton(onClick = { edit(null, "") }, enabled = !working) { Text(stringResource(R.string.panel_profile_add)) } }
                items(rows, key = { it.id }) { row ->
                    HorizontalDivider(color = AppTheme.componentColors.lineGray)
                    Text(row.fact, color = AppTheme.componentColors.bodyText, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (deleteId == row.id) {
                            Text(stringResource(R.string.panel_profile_confirm_delete), Modifier.weight(1f), fontSize = 13.sp)
                            TextButton(onClick = { deleteId = null }, enabled = !working) { Text(stringResource(R.string.panel_profile_cancel)) }
                            TextButton(onClick = { write(R.string.panel_profile_delete_failed) {
                                store.deleteMemory(row.id)
                                ProfileSaveResult.SAVED
                            } }, enabled = !working) { Text(stringResource(R.string.panel_profile_delete)) }
                        } else {
                            TextButton(onClick = { edit(row.id, row.fact) }, enabled = !working) { Text(stringResource(R.string.panel_profile_edit)) }
                            TextButton(onClick = { deleteId = row.id }, enabled = !working) { Text(stringResource(R.string.panel_profile_delete)) }
                        }
                    }
                }
            } else {
                item {
                    if (note.isNotBlank()) Text(note, color = AppTheme.componentColors.bodyText, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { edit(null, note) }, enabled = !working) { Text(stringResource(R.string.panel_profile_edit)) }
                }
            }
        }
    }
}
