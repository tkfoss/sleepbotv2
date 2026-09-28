package com.sleepbot.app.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.settings.MessageDialog
import com.sleepbot.app.settings.PrefRow
import com.sleepbot.app.settings.SingleChoiceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun readBytes(ctx: Context, uri: Uri): ByteArray? =
    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

@Composable
private fun ProgressDialog(title: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.please_wait))
            }
        },
        confirmButton = {},
    )
}

/**
 * "Backup & Sync" rows: Backup, Restore from Backup file, Restore from an Export file. Dialogs are
 * shown through the host's [show]/[close] single-dialog slot.
 */
@Composable
fun BackupSection(show: (@Composable () -> Unit) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    fun s(id: Int, vararg args: Any) = res.getString(id, *args)
    fun q(id: Int, n: Int, vararg args: Any) = res.getQuantityString(id, n, *args)
    val scope = rememberCoroutineScope()
    var csvPattern by remember { mutableStateOf("MM/dd/yy") }

    fun done(title: String, msg: String) = show { MessageDialog(title, msg, onConfirm = close, onDismiss = close) }

    fun importCsv(bytes: ByteArray, pattern: String) {
        show { ProgressDialog(s(R.string.restore_progress_title)) }
        scope.launch {
            val r = withContext(Dispatchers.IO) { Csv.import(ctx, String(bytes, Charsets.UTF_8), pattern) }
            val msg = q(R.plurals.restore_csv_done, r.imported, r.imported) +
                if (r.failed) "\n\n" + s(R.string.restore_csv_partial) else ""
            done(s(R.string.restore_old_file_title), msg)
        }
    }

    fun chooseFormat(onChosen: (String) -> Unit) {
        val system = ctx.app.prefs.dateFormat()
        val sel = Csv.IMPORT_PATTERNS.indexOf(system).coerceAtLeast(0)
        show {
            SingleChoiceDialog(
                s(R.string.date_format), res.getStringArray(R.array.date_entries).toList(), sel,
                onSelect = { close(); onChosen(Csv.IMPORT_PATTERNS[it]) }, onDismiss = close,
            )
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        show { ProgressDialog(s(R.string.backup_progress_title)) }
        scope.launch {
            val n = runCatching { Backup.write(ctx, uri) }
            n.onSuccess {
                done(s(R.string.done_label), q(R.plurals.backup_done_n, it, it))
            }.onFailure { done(s(R.string.error), s(R.string.backup_failed, it.message.orEmpty())) }
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readBytes(ctx, uri)
        if (bytes == null) { done(s(R.string.error), s(R.string.error_restore_file_malformatted)); return@rememberLauncherForActivityResult }
        if (Backup.looksLikeCsv(bytes)) {
            chooseFormat { importCsv(bytes, it) }
            return@rememberLauncherForActivityResult
        }
        show { ProgressDialog(s(R.string.restore_progress_title)) }
        scope.launch {
            when (val r = Backup.restore(ctx, bytes)) {
                is Backup.RestoreResult.Ok -> done(
                    s(R.string.pref_adv_restore_title),
                    q(R.plurals.restore_done, r.total, r.restored, r.total),
                )
                is Backup.RestoreResult.Error -> done(s(R.string.error), s(r.message))
            }
        }
    }

    val openExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readBytes(ctx, uri) ?: return@rememberLauncherForActivityResult
        importCsv(bytes, csvPattern)
    }

    PrefRow(stringResource(R.string.pref_adv_backup_title), stringResource(R.string.pref_adv_backup_summary), onClick = {
        val name = "sleepbot_backup_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"
        runCatching { createBackup.launch(name) }
    })
    PrefRow(
        stringResource(R.string.restore_backup_title),
        stringResource(R.string.restore_backup_summary),
        onClick = {
            show {
                MessageDialog(
                    stringResource(R.string.restore_confirm_title),
                    stringResource(R.string.restore_warning_double) + "\n" + stringResource(R.string.restore_choose_backup_hint),
                    confirm = stringResource(R.string.restore_choose_file_button),
                    onConfirm = { close(); runCatching { openBackup.launch(arrayOf("*/*")) } },
                    dismiss = stringResource(R.string.cancel), onDismiss = close,
                )
            }
        },
    )
    PrefRow(
        stringResource(R.string.restore_old_file_title),
        stringResource(R.string.restore_old_file_summary),
        onClick = {
            show {
                MessageDialog(
                    stringResource(R.string.restore_old_file_title), stringResource(R.string.restore_old_file_body),
                    confirm = stringResource(R.string.next), onConfirm = {
                    chooseFormat { p -> csvPattern = p; runCatching { openExport.launch(arrayOf("text/*", "application/octet-stream", "*/*")) } }
                }, onDismiss = close)
            }
        },
    )
}
