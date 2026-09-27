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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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

private const val RESTORE_WARNING =
    "IMPORTANT: Restoring can create duplicate entries if you already have entries in your log " +
        "(clear SleepBot data in Android Application Settings if necessary).\n" +
        "Choose a file created by SleepBot's Backup function. Entries that are already in your log are skipped."

private const val EXPORT_RESTORE_MSG =
    "Because the export files are not originally designed for backup and restore purposes, please make you choose " +
        "the same Date Format in the next step to match the same date format used in the text. Please choose the same " +
        "date format as the one when you export the file to ensure all the data are correctly imported (If another " +
        "application on the market tries to import SleepBot data and does not ask you for date format, you should " +
        "verify that all the data are imported correctly)."

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
                Text("Please wait…")
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
    val scope = rememberCoroutineScope()
    var csvPattern by remember { mutableStateOf("MM/dd/yy") }

    fun done(title: String, msg: String) = show { MessageDialog(title, msg, onConfirm = close, onDismiss = close) }

    fun importCsv(bytes: ByteArray, pattern: String) {
        show { ProgressDialog("Restoring SleepBot") }
        scope.launch {
            val r = withContext(Dispatchers.IO) { Csv.import(ctx, String(bytes, Charsets.UTF_8), pattern) }
            val msg = "${r.imported} entries successfully restored." +
                if (r.failed) "\n\nThe rest of the file could not be read. Please check that the date format matches the file." else ""
            done("Restore from an Export file", msg)
        }
    }

    fun chooseFormat(onChosen: (String) -> Unit) {
        val system = ctx.app.prefs.dateFormat()
        val sel = Csv.IMPORT_PATTERNS.indexOf(system).coerceAtLeast(0)
        show {
            SingleChoiceDialog(
                "Date Format", listOf("Month/Day/Year", "Day/Month/Year", "Year/Month/Day"), sel,
                onSelect = { close(); onChosen(Csv.IMPORT_PATTERNS[it]) }, onDismiss = close,
            )
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        show { ProgressDialog("Backing up SleepBot") }
        scope.launch {
            val n = runCatching { Backup.write(ctx, uri) }
            n.onSuccess {
                done("Done", "Backup finished: $it entries saved, including movement/sound data.\nPlease note that alarm clock settings are not backed up.")
            }.onFailure { done("Error", "Backup failed: ${it.message}") }
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readBytes(ctx, uri)
        if (bytes == null) { done("Error", "Restore file does not have a valid format or it is corrupted, restore canceled."); return@rememberLauncherForActivityResult }
        if (Backup.looksLikeCsv(bytes)) {
            chooseFormat { importCsv(bytes, it) }
            return@rememberLauncherForActivityResult
        }
        show { ProgressDialog("Restoring SleepBot") }
        scope.launch {
            when (val r = Backup.restore(ctx, bytes)) {
                is Backup.RestoreResult.Ok -> done(
                    "Restore",
                    "${r.restored}/${r.total} entries successfully restored.\n\nSleep debt start date successfully reseted to today.",
                )
                is Backup.RestoreResult.Error -> done("Error", r.message)
            }
        }
    }

    val openExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readBytes(ctx, uri) ?: return@rememberLauncherForActivityResult
        importCsv(bytes, csvPattern)
    }

    PrefRow("Backup", "Backup your data to a file (e.g. on your phone or in the cloud).", onClick = {
        val name = "sleepbot_backup_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"
        runCatching { createBackup.launch(name) }
    })
    PrefRow(
        "Restore from Backup file",
        "Restore entries from files made by the Backup function. (Recommended)",
        onClick = {
            show {
                MessageDialog(
                    "Confirm to restore", RESTORE_WARNING, confirm = "Choose file",
                    onConfirm = { close(); runCatching { openBackup.launch(arrayOf("*/*")) } },
                    dismiss = "Cancel", onDismiss = close,
                )
            }
        },
    )
    PrefRow(
        "Restore from an Export file",
        "Restore entries from files generated by the Export function.",
        onClick = {
            show {
                MessageDialog("Restore from an Export file", EXPORT_RESTORE_MSG, confirm = "Next", onConfirm = {
                    chooseFormat { p -> csvPattern = p; runCatching { openExport.launch(arrayOf("text/*", "application/octet-stream", "*/*")) } }
                }, onDismiss = close)
            }
        },
    )
}
