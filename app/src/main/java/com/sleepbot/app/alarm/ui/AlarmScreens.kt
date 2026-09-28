package com.sleepbot.app.alarm.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sleepbot.app.R
import com.sleepbot.app.alarm.Alarm
import com.sleepbot.app.alarm.AlarmSettings
import com.sleepbot.app.alarm.Week
import com.sleepbot.app.app
import com.sleepbot.app.settings.MultiChoiceDialog
import com.sleepbot.app.settings.NamebarHeader
import com.sleepbot.app.settings.SbCheckbox
import com.sleepbot.app.settings.SingleChoiceDialog
import com.sleepbot.app.settings.SummaryColor
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.delay
import java.util.Calendar

/** Ticks at every minute boundary (legacy list refresh). */
@Composable
private fun rememberMinuteTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun timeOfDayText(context: Context, sec: Int): String {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, sec / 3600); set(Calendar.MINUTE, (sec / 60) % 60); set(Calendar.SECOND, 0)
    }
    return TimeFormat.time(context, c.timeInMillis)
}

@Composable
private fun ConfirmDeleteDialog(title: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.alarm_confirm_delete)) },
        confirmButton = { TextButton(onOk) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Time picker dialog with a live countdown line (legacy AlarmTimePickerDialog). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmTimeDialog(title: String, hour: Int, minute: Int, onOk: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(hour, minute, DateFormat.is24HourFormat(ctx))
    val now = rememberMinuteTick()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                val t = Week.next(state.hour * 3600 + state.minute * 60, 0, now)
                Text(TimeFormat.countdown(ctx, t, now), color = SummaryColor, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(12.dp))
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        selectorColor = SB.HoloBlue,
                        timeSelectorSelectedContainerColor = SB.Highlighted,
                        periodSelectorSelectedContainerColor = SB.Highlighted,
                    ),
                )
            }
        },
        confirmButton = { TextButton({ onOk(state.hour, state.minute) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Legacy ActivityAlarmClock (alarm_list.xml). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlarmListScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.alarms
    val alarms by repo.alarms.collectAsState()
    val pending by repo.pending.collectAsState()
    val now = rememberMinuteTick()
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf<Alarm?>(null) }
    var confirmDelete by remember { mutableStateOf<Alarm?>(null) }
    var permTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        repo.ensurePresets()
        repo.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++; repo.refresh() }

    Column(Modifier.fillMaxSize().background(SB.EditorBg).windowInsetsPadding(WindowInsets.systemBars)) {
        NamebarHeader(stringResource(R.string.alarms), fontSize = 26) {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.alarm_delete_all)) }, { menu = false; confirmDeleteAll = true })
                DropdownMenuItem({ Text(stringResource(R.string.alarm_default_settings)) }, { menu = false; onEdit(-1) })
            }
        }
        PermissionHints(permTick)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            items(alarms, key = { it.id }) { a ->
                val shownTime = pending[a.id]
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onEdit(a.id) }, onLongClick = { longPressed = a })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 24.dp)) {
                        Row {
                            Text(
                                if (shownTime != null) TimeFormat.time(ctx, shownTime) else timeOfDayText(ctx, a.timeSec),
                                Modifier.weight(1f), fontSize = 24.sp, color = Color.White, maxLines = 1,
                            )
                            Text(
                                a.name, Modifier.widthIn(max = 120.dp), fontSize = 20.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            )
                        }
                        Row {
                            Text(
                                if (shownTime != null) TimeFormat.countdown(ctx, shownTime, now) else "",
                                Modifier.weight(1f), fontStyle = FontStyle.Italic, color = SummaryColor, fontSize = 14.sp, maxLines = 1,
                            )
                            if (a.repeats) Text(Week.summary(ctx, a.dow), color = SummaryColor, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                    SbCheckbox(a.enabled, { repo.setEnabled(a.id, it) })
                }
                HorizontalDivider(color = Color(0x33FFFFFF))
                if (longPressed?.id == a.id) {
                    DropdownMenu(true, { longPressed = null }) {
                        DropdownMenuItem({ Text(stringResource(R.string.alarm_edit)) }, { longPressed = null; onEdit(a.id) })
                        DropdownMenuItem({ Text(stringResource(R.string.alarm_delete_ellipsis)) }, { longPressed = null; confirmDelete = a })
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(SB.Divider))
        Row(
            Modifier.fillMaxWidth().height(52.dp).clickable { adding = true },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.new_icon), null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.add_alarm), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }

    if (adding) {
        AlarmTimeDialog(stringResource(R.string.add_alarm), 8, 0, onOk = { h, m ->
            adding = false
            repo.create(h, m)
        }, onDismiss = { adding = false })
    }
    if (confirmDeleteAll) {
        ConfirmDeleteDialog(stringResource(R.string.alarm_delete_all), { confirmDeleteAll = false; repo.deleteAll() }, { confirmDeleteAll = false })
    }
    confirmDelete?.let { a ->
        ConfirmDeleteDialog(stringResource(R.string.alarm_delete_ellipsis), { confirmDelete = null; repo.delete(a.id) }, { confirmDelete = null })
    }
}

/** Prompts for exact-alarm / full-screen-intent access when the platform denied them. */
@Composable
private fun PermissionHints(@Suppress("UNUSED_PARAMETER") tick: Int) {
    val ctx = LocalContext.current
    val am = ctx.getSystemService(AlarmManager::class.java)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val exactMissing = Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()
    val fsiMissing = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
    @Composable
    fun hint(text: String, action: String) {
        Text(
            text,
            Modifier.fillMaxWidth().background(Color(0xFF2B3A55))
                .clickable {
                    runCatching {
                        ctx.startActivity(Intent(action, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .padding(12.dp),
            color = Color.White, fontSize = 14.sp,
        )
    }
    if (exactMissing) hint(stringResource(R.string.alarm_exact_permission), Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    if (fsiMissing) hint(stringResource(R.string.alarm_fsi_permission), Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
}

private fun toneTitle(ctx: Context, uri: Uri?): String {
    if (uri == null) return ctx.getString(R.string.alarm_default_tone)
    if (uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) || uri == Settings.System.DEFAULT_ALARM_ALERT_URI) {
        return ctx.getString(R.string.alarm_default_tone)
    }
    if (uri.scheme == "content" && uri.authority != "media" && uri.authority?.contains("media") != true) {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    return runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: ctx.getString(R.string.alarm_unknown_name)
}

/** Legacy ActivityAlarmSettings (settings.xml); [alarmId] −1 edits the default alarm settings. */
@Composable
fun AlarmEditScreen(alarmId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.alarms
    val isDefaults = alarmId == -1L
    val original = remember(alarmId) { if (isDefaults) null else repo.get(alarmId) }
    val originalSettings = remember(alarmId) { repo.settingsFor(alarmId) }
    if (!isDefaults && original == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var alarm by remember(alarmId) { mutableStateOf(original) }
    var settings by remember(alarmId) { mutableStateOf(originalSettings) }
    var dialog by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            ?: return@rememberLauncherForActivityResult
        val isDefault = uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) || uri == Settings.System.DEFAULT_ALARM_ALERT_URI
        settings = settings.copy(toneUri = if (isDefault) null else uri.toString(), toneName = toneTitle(ctx, uri))
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            Toast.makeText(ctx, R.string.alarm_file_not_chosen, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settings = settings.copy(toneUri = uri.toString(), toneName = toneTitle(ctx, uri))
    }

    Column(Modifier.fillMaxSize().background(SB.EditorBg).windowInsetsPadding(WindowInsets.systemBars)) {
        Text(
            stringResource(if (isDefaults) R.string.alarm_default_settings_header else R.string.alarm_settings_header),
            Modifier.fillMaxWidth().background(Color(0xFF838183)).padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = 20.sp, color = Color.White,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            alarm?.let { a ->
                SettingItem(stringResource(R.string.alarm_row_time), timeOfDayText(ctx, a.timeSec)) { dialog = "time" }
                SettingItem(stringResource(R.string.alarm_row_label), a.name.ifEmpty { stringResource(R.string.alarm_label_none) }) { dialog = "label" }
                SettingItem(stringResource(R.string.alarm_row_repeat), Week.summary(ctx, a.dow, noRepeatText = true).trim()) { dialog = "repeat" }
            }
            SettingItem(stringResource(R.string.alarm_row_tone), if (settings.toneUri == null) stringResource(R.string.alarm_default_tone) else settings.toneName) { dialog = "tone" }
            SettingItem(stringResource(R.string.alarm_row_snooze), settings.snooze.toString()) { dialog = "snooze" }
            SettingItem(
                stringResource(R.string.alarm_row_vibrate),
                stringResource(if (settings.vibrate) R.string.alarm_enabled else R.string.alarm_disabled),
            ) { settings = settings.copy(vibrate = !settings.vibrate) }
            SettingItem(
                stringResource(R.string.alarm_row_fade),
                pluralStringResource(R.plurals.alarm_fade_summary, settings.volTime, settings.volStart, settings.volEnd, settings.volTime),
            ) { dialog = "fade" }
        }
        val btn = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3A55), contentColor = Color.White)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Button({
                val a = alarm
                if (a != null && a != original) repo.update(a)
                if (settings != originalSettings) repo.setSettings(alarmId, settings)
                onBack()
            }, Modifier.weight(1f).padding(4.dp), colors = btn) { Text(stringResource(R.string.ok)) }
            Button(onBack, Modifier.weight(1f).padding(4.dp), colors = btn) { Text(stringResource(R.string.cancel)) }
        }
        if (!isDefaults) {
            Button({ dialog = "delete" }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), colors = btn) {
                Text(stringResource(R.string.alarm_delete_ellipsis))
            }
        }
    }

    when (dialog) {
        "time" -> alarm?.let { a ->
            AlarmTimeDialog(stringResource(R.string.alarm_row_time), a.hour, a.minute, onOk = { h, m ->
                alarm = a.copy(timeSec = h * 3600 + m * 60, snoozeUntil = 0); dialog = null
            }, onDismiss = { dialog = null })
        }
        "label" -> alarm?.let { a ->
            LabelDialog(a.name, onOk = { alarm = a.copy(name = it); dialog = null }, onDismiss = { dialog = null })
        }
        "repeat" -> alarm?.let { a ->
            MultiChoiceDialog(
                stringResource(R.string.alarm_scheduled_days), Week.longNames(), (0..6).map { Week.has(a.dow, it) },
                onToggle = { i, on -> alarm = alarm!!.let { cur -> cur.copy(dow = if (on) cur.dow or (1 shl i) else cur.dow and (1 shl i).inv()) } },
                onDismiss = { dialog = null },
            )
        }
        "tone" -> SingleChoiceDialog(
            stringResource(R.string.alarm_row_tone),
            listOf(stringResource(R.string.alarm_pick_system_tone), stringResource(R.string.alarm_pick_file)), -1,
            onSelect = { i ->
                dialog = null
                if (i == 0) {
                    ringtoneLauncher.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.toneUriOrDefault()),
                    )
                } else {
                    runCatching { fileLauncher.launch(arrayOf("audio/*")) }
                }
            },
            onDismiss = { dialog = null },
        )
        "snooze" -> SingleChoiceDialog(
            stringResource(R.string.alarm_row_snooze), (1..60).map { it.toString() }, settings.snooze - 1,
            onSelect = { settings = settings.copy(snooze = it + 1); dialog = null }, onDismiss = { dialog = null },
        )
        "fade" -> FadeDialog(settings, onOk = { settings = it; dialog = null }, onDismiss = { dialog = null })
        "delete" -> ConfirmDeleteDialog(stringResource(R.string.alarm_delete_ellipsis), {
            dialog = null
            repo.delete(alarmId)
            onBack()
        }, { dialog = null })
    }
}

@Composable
private fun SettingItem(name: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(name, fontSize = 20.sp, color = Color.White)
        Text(value, Modifier.padding(start = 6.dp), fontSize = 14.sp, color = SummaryColor)
    }
    HorizontalDivider(color = Color(0x33FFFFFF))
}

@Composable
private fun LabelDialog(initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_label_title)) },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.alarm_row_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton({ onOk(text.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FadeDialog(s: AlarmSettings, onOk: (AlarmSettings) -> Unit, onDismiss: () -> Unit) {
    var start by remember { mutableStateOf(s.volStart.toString()) }
    var end by remember { mutableStateOf(s.volEnd.toString()) }
    var time by remember { mutableStateOf(s.volTime.toString()) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_row_fade)) },
        text = {
            Column {
                OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.alarm_fade_start)) }, singleLine = true, keyboardOptions = num)
                OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.alarm_fade_end)) }, singleLine = true, keyboardOptions = num)
                OutlinedTextField(time, { time = it }, label = { Text(stringResource(R.string.alarm_fade_time)) }, singleLine = true, keyboardOptions = num)
            }
        },
        confirmButton = {
            TextButton({
                val a = start.trim().toIntOrNull(); val b = end.trim().toIntOrNull(); val c = time.trim().toIntOrNull()
                onOk(
                    if (a == null || b == null || c == null) s.copy(volStart = 0, volEnd = 100, volTime = 20)
                    else s.copy(volStart = a, volEnd = b, volTime = c).clamped(),
                )
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

