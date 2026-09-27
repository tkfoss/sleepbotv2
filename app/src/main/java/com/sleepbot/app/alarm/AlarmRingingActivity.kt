package com.sleepbot.app.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.sleepbot.app.MainActivity
import com.sleepbot.app.R
import com.sleepbot.app.alarm.ui.DismissSlider
import com.sleepbot.app.app
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.ui.theme.SleepBotTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/** Legacy ActivityAlarmNotification (layout notification.xml). */
class AlarmRingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SleepBotTheme { RingingScreen() } }
    }

    private fun onDismiss(ring: AlarmRingService.Ring) {
        AlarmRingService.dismiss(this, ring.id)
        val app = app
        if (!app.prefs.isAwake) {
            lifecycleScope.launch {
                val prefs = app.prefs
                val delayMs = prefs.punchInDelayMin * 60_000L
                val overrides = if (prefs.sleepState + delayMs > System.currentTimeMillis())
                    SleepSession.Overrides(sleep = prefs.sleepState) else SleepSession.Overrides()
                val result = app.session.punchOut(overrides)
                startActivity(
                    Intent(this@AlarmRingingActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("show_wake_dialog_entry_id", result.entryId),
                )
                finish()
            }
        } else {
            finish()
        }
    }

    @Composable
    private fun RingingScreen() {
        val ring by AlarmRingService.current.collectAsState()
        val timedOut by AlarmRingService.timedOut.collectAsState()
        var shown by remember { mutableStateOf(ring) }
        var dismissing by remember { mutableStateOf(false) }
        LaunchedEffect(ring, timedOut) {
            if (ring != null) shown = ring
            if (ring == null && timedOut == null && !dismissing) finish()
        }
        val r = shown
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            if (r != null) RingContent(r, onSnooze = { min ->
                AlarmRingService.snooze(this@AlarmRingingActivity, r.id, min)
                finish()
            }, onDismiss = { dismissing = true; onDismiss(r) })
        }
        if (timedOut != null) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.alarm_time_out)) },
                text = { Text(stringResource(R.string.time_out_error)) },
                confirmButton = {
                    TextButton(onClick = { AlarmRingService.timedOut.value = null; finish() }) { Text(stringResource(R.string.ok)) }
                },
            )
        }
    }

    @Composable
    private fun RingContent(r: AlarmRingService.Ring, onSnooze: (Int) -> Unit, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        var snooze by rememberSaveable(r.id) { mutableIntStateOf(r.snooze) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) } }
        val btnColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A), contentColor = Color.White)
        val shape = RoundedCornerShape(2.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(150.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    { snooze = (snooze - 5).coerceIn(5, 60) }, Modifier.weight(1f).fillMaxHeight(),
                    shape = shape, colors = btnColors,
                ) { Text(stringResource(R.string.minus_five), fontSize = 24.sp) }
                Text(
                    stringResource(R.string.snooze_n_minutes, snooze), Modifier.weight(1f),
                    fontSize = 24.sp, textAlign = TextAlign.Center, color = Color.White, lineHeight = 30.sp,
                )
                Button(
                    { snooze = (snooze + 5).coerceIn(5, 60) }, Modifier.weight(1f).fillMaxHeight(),
                    shape = shape, colors = btnColors,
                ) { Text(stringResource(R.string.plus_five), fontSize = 24.sp) }
            }
            val locale = LocalConfiguration.current.locales[0]
            val clockPattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm:ss" else "h:mm:ss a"
            Text(
                SimpleDateFormat(clockPattern, locale).format(Date(now)),
                Modifier.fillMaxWidth(), fontSize = 56.sp, textAlign = TextAlign.Center, color = Color.White,
            )
            Button(
                { onSnooze(snooze) }, Modifier.fillMaxWidth().weight(4f).padding(4.dp),
                shape = shape, colors = btnColors,
            ) { Text(stringResource(R.string.snooze), fontSize = 32.sp) }
            val info = SimpleDateFormat("HH:mm.ss MMMM dd yyyy", locale).format(Date(r.time)) + "\n" + r.name
            Text(info, Modifier.weight(3f).padding(horizontal = 4.dp), color = Color.White, fontSize = 14.sp)
            DismissSlider(onComplete = onDismiss, modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 60.dp))
        }
    }
}

private val AlarmRingService.Ring.name get() = label
