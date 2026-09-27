package com.sleepbot.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.ui.common.StarRating
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.launch

/** "Sleep Entry Created!" dialog shown after punching out: rate + note, Confirm / Edit / Cancel. */
@Composable
fun WakeDialog(entryId: Long, onDismiss: () -> Unit, onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = context.app.db.entries()
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<SleepEntry?>(null) }
    var rating by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(entryId) {
        entry = dao.get(entryId)
        entry?.let { rating = it.rating.coerceAtLeast(0); note = it.note }
    }
    val e = entry ?: return
    fun save(then: () -> Unit) = scope.launch {
        if (rating != e.rating.coerceAtLeast(0) || note != e.note) {
            dao.update(e.copy(rating = if (rating == 0) e.rating else rating, note = note, modified = System.currentTimeMillis()))
        }
        then()
    }
    AlertDialog(
        onDismissRequest = { save(onDismiss) },
        title = { Text(stringResource(R.string.entry_created_title)) },
        text = {
            Column {
                Text(stringResource(R.string.entry_created_msg,
                    TimeFormat.time(context, e.sleep), TimeFormat.time(context, e.awake),
                    TimeFormat.durationText(context, e.durationHours.coerceAtLeast(0.0))))
                Spacer(Modifier.height(12.dp))
                StarRating(rating, { rating = it }, Modifier.fillMaxWidth(), starSize = 40.dp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.note)) }, maxLines = 4)
            }
        },
        confirmButton = { TextButton({ save(onDismiss) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = {
            TextButton({ save { onDismiss(); onEdit(e.id) } }) { Text(stringResource(R.string.edit)) }
        },
    )
}
