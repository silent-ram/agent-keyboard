package com.agentkeyboard

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentkeyboard.data.BackupAuthenticationException
import com.agentkeyboard.data.BackupFormatException
import com.agentkeyboard.data.BackupVersionException
import com.agentkeyboard.data.ConfigBackupCodec
import com.agentkeyboard.data.ConnectionConfig
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.ui.AssistantIcon
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R

@Composable
internal fun ConfigBackupSection(store: ModelStore, onImported: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val backupFilename = "${stringResource(R.string.app_name)}.akcfg"
    val scope = rememberCoroutineScope()
    var pendingFile by remember { mutableStateOf<ByteArray?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<ConnectionConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun notice(message: Int, error: Boolean = false) {
        status = message
        failed = error
    }

    fun work(error: (Exception) -> Int, block: suspend () -> Unit) {
        status = null
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(error(e), true)
            } finally {
                busy = false
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val file = pendingFile
        pendingFile = null
        if (uri != null && file != null) {
            work({ R.string.config_backup_write_failed }) {
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException()
                    output.use { it.write(file) }
                }
                notice(R.string.config_backup_exported)
            }
        } else if (uri != null) {
            notice(R.string.config_backup_write_failed, true)
        }
    }

    fun chooseDestination(file: ByteArray) {
        pendingFile = file
        try {
            saveFile.launch(backupFilename)
        } catch (_: ActivityNotFoundException) {
            pendingFile = null
            notice(R.string.config_backup_picker_failed, true)
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            work({ e ->
                when (e) {
                    is BackupAuthenticationException, is BackupFormatException -> R.string.config_backup_invalid
                    is BackupVersionException -> R.string.config_backup_version_failed
                    else -> R.string.config_backup_import_failed
                }
            }) {
                pendingConfig = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: throw IOException()
                    input.use(ConfigBackupCodec::decode)
                }
            }
        }
    }

    fun pickConfig() {
        status = null
        try {
            openFile.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            notice(R.string.config_backup_picker_failed, true)
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.config_backup_export)) },
            text = { Text(stringResource(R.string.config_backup_share_warning)) },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    work({ R.string.config_backup_export_failed }) {
                        val config = store.exportConnections()
                        val bytes = withContext(Dispatchers.Default) { ConfigBackupCodec.encode(config) }
                        chooseDestination(bytes)
                    }
                }) { Text(stringResource(R.string.config_backup_choose_destination)) }
            },
        )
    }

    pendingConfig?.let { config ->
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            title = { Text(stringResource(R.string.config_backup_import)) },
            text = { Text(stringResource(R.string.config_backup_replace)) },
            dismissButton = { TextButton(onClick = { pendingConfig = null }) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfig = null
                    work({ R.string.config_backup_import_failed }) {
                        store.restoreConnections(config)
                        notice(R.string.config_backup_imported)
                        onImported()
                    }
                }) { Text(stringResource(R.string.config_backup_apply)) }
            },
        )
    }

    val available = !busy && pendingFile == null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.config_backup_title), color = BodyText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = available, onClick = {
                status = null
                confirmExport = true
            }) {
                AssistantIcon(R.drawable.ic_baseline_save_24, tint = PrimaryBlue)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.config_backup_export))
            }
            TextButton(enabled = available, onClick = ::pickConfig) {
                AssistantIcon(R.drawable.ic_baseline_settings_backup_restore_24, tint = PrimaryBlue)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.config_backup_import))
            }
        }
        if (busy) Text(stringResource(R.string.config_backup_working), color = MutedText, fontSize = 13.sp)
        status?.let { Text(stringResource(it), color = if (failed) ErrorText else BodyText, fontSize = 13.sp) }
    }
}
