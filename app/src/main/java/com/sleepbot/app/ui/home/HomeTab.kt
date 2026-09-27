package com.sleepbot.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.data.Debt
import com.sleepbot.app.data.DebtSummary
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.session.PunchResult
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.ui.common.drawableBackground
import com.sleepbot.app.ui.theme.RobotoRegular
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@Composable
fun HomeTab(onOpenAlarms: () -> Unit, onOpenEntry: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val session = app.session
    val scope = rememberCoroutineScope()
    val asleep by session.asleep.collectAsState()
    val nextAlarm by app.alarms.nextAlarm.collectAsState()
    val prefTick by remember { prefs.changes() }.collectAsState(null)

    // Minute ticker so "today" / countdowns stay current.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = System.currentTimeMillis() } }

    val entries by remember(prefTick, now / Debt.DAY) {
        app.db.entries().observeRange(minOf(Debt.windowFrom(prefs), Debt.todayStart()) - Debt.DAY, Debt.todayStart() + Debt.DAY)
    }.collectAsState(emptyList())
    val summary = remember(entries, prefTick, now) { Debt.summarize(entries, prefs) }

    val canSmart = nextAlarm?.let { it - now in 0 until Debt.DAY } == true
    var smart by remember { mutableStateOf(prefs.smartAlarm) }
    var motion by remember { mutableStateOf(prefs.trackMotion) }
    var sound by remember { mutableStateOf(prefs.recordSound) }
    LaunchedEffect(canSmart, asleep) {
        if (!canSmart && !asleep && prefs.smartAlarm) { prefs.smartAlarm = false; smart = false }
    }

    var showDebt by remember { mutableStateOf(prefs.homeShowsDebt) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var resetAsked by remember { mutableStateOf(false) }
    LaunchedEffect(summary) {
        if (!resetAsked && entries.isNotEmpty() && Debt.needsReset(summary, prefs)) { resetAsked = true; dialog = HomeDialog.AskResetDebt }
    }

    fun toast(@StringRes s: Int) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()

    fun doPunch() = scope.launch {
        val wasAwake = prefs.isAwake
        when (val r = session.toggle()) {
            PunchResult.PunchedIn -> if (wasAwake) toast(modeMessage(smart, motion, sound))
            is PunchResult.PunchedOut -> session.pendingWakeDialog.value = r.result.entryId
            PunchResult.OffsetInFuture -> dialog = HomeDialog.FutureOffset
            PunchResult.Blocked -> {}
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { prefs.recordSound = false; sound = false }
        doPunch()
    }

    fun onPunchClick() {
        if (prefs.isAwake && prefs.recordSound &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else doPunch()
    }

    Column(
        Modifier.fillMaxSize().drawableBackground(R.drawable.background).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SleepBox(
            summary = summary,
            showDebt = showDebt,
            optimal = prefs.optimalHours,
            showAlt = prefs.showAltNumber,
            onToggleMode = { if (prefs.showAltNumber) { showDebt = !showDebt; prefs.homeShowsDebt = showDebt } },
            onNumberClick = { dialog = if (showDebt) HomeDialog.ResetDebt else if (asleep) HomeDialog.CurrentSession else HomeDialog.EditToday },
        )

        // "Wake up: 9:00 AM ~ 9:30 AM"
        Row(Modifier.padding(top = 22.dp).height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val next = nextAlarm
            if (canSmart && next != null) {
                Text(stringResource(R.string.wake_up), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp))
                val text = if (smart) TimeFormat.time(context, next - prefs.smartWindowMin * 60_000L) + " ~ " + TimeFormat.time(context, next)
                else TimeFormat.time(context, next)
                Text(text, color = Color.White, fontSize = 16.sp, fontStyle = FontStyle.Italic,
                    modifier = Modifier.clickable(onClick = onOpenAlarms))
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }

        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val img = when {
            asleep && pressed -> R.drawable.mainbuttonwake_selected
            asleep -> R.drawable.mainbuttonwake_unselected
            pressed -> R.drawable.mainbuttonsleep_selected
            else -> R.drawable.mainbuttonsleep_unselected
        }
        Image(
            painterResource(img), stringResource(if (asleep) R.string.wake_caps else R.string.sleep_caps),
            Modifier.padding(top = 4.dp).width(306.dp).height(111.dp)
                .clickable(interactionSource = source, indication = null) { onPunchClick() },
            contentScale = ContentScale.Fit,
        )

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            OptionToggle(R.string.smart_alarm, smart, Modifier.padding(end = 21.dp)) {
                if (asleep) return@OptionToggle toast(R.string.cannot_change_settings_after_punchin)
                when {
                    canSmart -> { smart = !smart; prefs.smartAlarm = smart }
                    prefs.autoAlarm -> dialog = HomeDialog.AutoAlarm
                    else -> { toast(R.string.no_alarm_set_warning); onOpenAlarms() }
                }
            }
            OptionToggle(R.string.track_motion, motion, Modifier.padding(end = 21.dp)) {
                if (asleep) return@OptionToggle toast(R.string.cannot_change_settings_after_punchin)
                motion = !motion; prefs.trackMotion = motion
            }
            OptionToggle(R.string.record_sound, sound) {
                if (asleep) return@OptionToggle toast(R.string.cannot_change_settings_after_punchin)
                sound = !sound; prefs.recordSound = sound
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        HomeDialog.FutureOffset -> FutureOffsetDialog(
            onDismiss = { dialog = null },
            onNoRecord = { session.abandon(); toast(R.string.future_no_record_toast) },
            onZero = { scope.launch { val n = System.currentTimeMillis(); val r = session.punchOut(SleepSession.Overrides(sleep = n, awake = n)); session.pendingWakeDialog.value = r.entryId } },
            onNoOffset = { scope.launch { val n = System.currentTimeMillis(); val r = session.punchOut(SleepSession.Overrides(sleep = minOf(prefs.sleepState, n - 50))); session.pendingWakeDialog.value = r.entryId } },
        )
        HomeDialog.CurrentSession -> {
            val start = session.sleepStart
            val dur = TimeFormat.durationText(context, (System.currentTimeMillis() - start) / 3_600_000.0)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.current_session_title)) },
                text = { Text(stringResource(R.string.current_session_msg, TimeFormat.time(context, start), dur)) },
                confirmButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.confirm)) } },
                dismissButton = { TextButton({ session.restartSession(); dialog = null }) { Text(stringResource(R.string.reset)) } },
            )
        }
        HomeDialog.EditToday -> EditTodayDialog(entries, onOpenEntry) { dialog = null }
        HomeDialog.ResetDebt, HomeDialog.AskResetDebt -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.reset_debt_title)) },
            text = { Text(stringResource(if (dialog == HomeDialog.AskResetDebt) R.string.reset_debt_q else R.string.reset_debt_msg)) },
            confirmButton = {
                TextButton({ prefs.debtResetTime = Debt.todayStart(); toast(R.string.reset_debt_done); dialog = null }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        HomeDialog.AutoAlarm -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(stringResource(R.string.auto_alarm_prompt)) },
            confirmButton = { TextButton({ dialog = null; prefs.smartAlarm = true; smart = true; onPunchClick() }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> {}
    }
}

private enum class HomeDialog { FutureOffset, CurrentSession, EditToday, ResetDebt, AskResetDebt, AutoAlarm }

@StringRes
private fun modeMessage(smart: Boolean, motion: Boolean, sound: Boolean): Int = when {
    smart && !motion -> R.string.mode_smart
    smart && motion && !sound -> R.string.mode_smart_motion
    smart && motion && sound -> R.string.mode_complete
    motion && !sound -> R.string.mode_motion
    motion && sound -> R.string.mode_motion_sound
    sound -> R.string.mode_sound
    else -> R.string.mode_classic
}

/** The "TODAY'S SLEEP" / "CURRENT DEBT" panel with its progress bar. */
@Composable
private fun SleepBox(
    summary: DebtSummary,
    showDebt: Boolean,
    optimal: Float,
    showAlt: Boolean,
    onToggleMode: () -> Unit,
    onNumberClick: () -> Unit,
) {
    val main = if (showDebt) summary.debt else summary.todayHours
    val alt = if (showDebt) summary.todayHours else summary.debt
    val (h, m) = TimeFormat.formattedHour(main)
    val negative = main < 0 && (h != 0 || m != 0)
    val color = when {
        !showDebt -> SB.DisplayHours
        summary.debt > 0 -> SB.DebtRed
        else -> SB.DebtGreen
    }
    Box(Modifier.width(288.dp).height(112.dp)) {
        Image(painterResource(R.drawable.sleepdebttable_todayssleepbar), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (showAlt) {
            val (ah, am) = TimeFormat.formattedHour(alt)
            Text((if (alt < 0) "-" else "") + TimeFormat.pad2(kotlin.math.abs(ah)) + ":" + TimeFormat.pad2(am),
                color = SB.HiddenText, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 18.dp).clickable(onClick = onToggleMode))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(if (showDebt) R.string.current_debt else R.string.todays_sleep),
                    color = Color.White, fontSize = 13.sp, fontFamily = RobotoRegular, maxLines = 1, softWrap = false,
                    modifier = Modifier.width(112.dp).padding(end = 4.dp, bottom = 14.dp).clickable(onClick = onToggleMode),
                )
                Row(Modifier.clickable(onClick = onNumberClick), verticalAlignment = Alignment.Bottom) {
                    if (negative) Text("-", color = SB.DebtGreen, fontSize = 56.sp, maxLines = 1)
                    Text(TimeFormat.pad2(kotlin.math.abs(h)) + ":" + TimeFormat.pad2(m), color = color, fontSize = 56.sp, lineHeight = 56.sp, maxLines = 1, softWrap = false)
                }
            }
            val filled = if (showDebt) -1f else (if (summary.todayHours >= optimal) 1f else (summary.todayHours / optimal).toFloat().coerceIn(0f, 1f))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 21.dp).height(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (filled >= 0f) {
                    if (filled > 0f) Row(Modifier.weight(filled), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.home_progressfilled), null, Modifier.weight(1f).height(2.dp), contentScale = ContentScale.FillBounds)
                        Image(painterResource(R.drawable.home_progressmarker), null, Modifier.width(18.dp).height(6.dp))
                    }
                    if (filled < 1f) Image(painterResource(R.drawable.home_progressempty), null, Modifier.weight(1f - filled).height(2.dp), contentScale = ContentScale.FillBounds)
                }
            }
        }
    }
}

@Composable
private fun OptionToggle(@StringRes label: Int, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val img = when {
        on && pressed -> R.drawable.home_optionselectedpressed
        on -> R.drawable.home_optionsselected
        pressed -> R.drawable.home_optionsunselectedpressed
        else -> R.drawable.home_optionsunselected
    }
    Column(modifier.clickable(interactionSource = source, indication = null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(img), null, Modifier.size(56.dp))
        Text(stringResource(label), color = if (on) SB.GraphLine else Color.White, fontSize = 14.sp, fontFamily = RobotoRegular)
    }
}

@Composable
private fun FutureOffsetDialog(onDismiss: () -> Unit, onNoRecord: () -> Unit, onZero: () -> Unit, onNoOffset: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.future_title)) },
        confirmButton = {
            Row {
                TextButton({ onDismiss(); onNoRecord() }) { Text(stringResource(R.string.future_no_record)) }
                TextButton({ onDismiss(); onZero() }) { Text(stringResource(R.string.future_zero_record)) }
                TextButton({ onDismiss(); onNoOffset() }) { Text(stringResource(R.string.future_no_offset)) }
            }
        },
    )
}

/** Awake: tap the number to edit today's entry (one → open, several → pick, none → "awake since"). */
@Composable
private fun EditTodayDialog(entries: List<SleepEntry>, onOpenEntry: (Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val start = Debt.todayStart()
    val today = entries.filter { it.awake in start until start + Debt.DAY }.sortedByDescending { it.awake }
    if (today.size == 1) {
        LaunchedEffect(Unit) { onDismiss(); onOpenEntry(today[0].id) }
        return
    }
    if (today.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.edit_data)) },
            text = {
                Column {
                    today.forEach { e ->
                        val label = TimeFormat.table(e.sleep) + " - " + TimeFormat.table(e.awake) +
                            if (e.note.isNotEmpty()) " (" + e.note.take(30) + ")" else ""
                        Text(label, Modifier.fillMaxWidth().clickable { onDismiss(); onOpenEntry(e.id) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
        )
        return
    }
    val last = entries.filter { it.awake < System.currentTimeMillis() }.maxByOrNull { it.awake }
    if (last == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val (h, m) = TimeFormat.formattedHour((System.currentTimeMillis() - last.awake) / 3_600_000.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_data)) },
        text = { Text(stringResource(R.string.awake_since, h, m)) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}
