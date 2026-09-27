package com.sleepbot.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.sleepbot.app.MainActivity
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.session.PunchResult
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.ui.graph.GraphSpec
import com.sleepbot.app.ui.graph.GraphType
import com.sleepbot.app.ui.graph.GraphView
import com.sleepbot.app.ui.theme.RobotoThin
import com.sleepbot.app.ui.theme.SleepBotTheme
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Night screen (legacy SensorsActivity): starfield, clock, live movement graph, "waking up!". */
class NightActivity : ComponentActivity() {
    private var dimmed by mutableStateOf(false)
    private val dimRunnable = Runnable { dim() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.isAwake) { finish(); return }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (!app.prefs.screenOffTracking) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    dimmed -> { undim(); scheduleDim(false) }
                    app.prefs.isAwake -> finish()
                    else -> {} // legacy: back is ignored while asleep and not dimmed
                }
            }
        })
        lifecycleScope.launch {
            app.session.asleep.collect { if (!it) finish() }
        }
        setContent { SleepBotTheme { NightScreen(dimmed = dimmed, onWake = ::wake) } }
    }

    override fun onResume() {
        super.onResume()
        if (app.prefs.isAwake) { finish(); return }
        if (!dimmed) scheduleDim(true)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dimRunnable)
    }

    private fun scheduleDim(withToast: Boolean) {
        handler.removeCallbacks(dimRunnable)
        if (withToast && !app.prefs.screenOffTracking) toast(R.string.trk_screen_dim_soon)
        handler.postDelayed(dimRunnable, DIM_DELAY)
    }

    private fun dim() {
        if (isFinishing) return
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        if (!app.prefs.screenOffTracking) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dimmed = true
        toast(if (app.prefs.screenOffTracking) R.string.trk_screen_off_toast else R.string.trk_screen_already_dimmed)
    }

    private fun undim() {
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        dimmed = false
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun wake() {
        if (dimmed) return
        lifecycleScope.launch {
            val r = app.session.toggle(SleepSession.Restrict.WAKE_ONLY)
            val main = Intent(this@NightActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (r is PunchResult.PunchedOut) main.putExtra(EXTRA_WAKE_DIALOG, r.result.entryId)
            startActivity(main)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        const val EXTRA_WAKE_DIALOG = "show_wake_dialog_entry_id"
        private const val DIM_DELAY = 15_000L

        fun intent(context: Context): Intent =
            Intent(context, NightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}

@Composable
private fun NightScreen(dimmed: Boolean, onWake: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val charging = rememberCharging()
    val showWarning = !charging && (!prefs.screenOffTracking || !prefs.hideChargingWarning)
    LaunchedEffect(showWarning) {
        if (showWarning) runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }
    val overlay by animateFloatAsState(if (dimmed) 1f else 0f, tween(300), label = "dim")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painterResource(R.drawable.nightstand_bg), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (showWarning) {
                Text(
                    stringResource(R.string.trk_phone_is_not_plugged),
                    Modifier.fillMaxWidth().background(Color.Black).padding(17.dp),
                    color = Color.White, fontSize = 20.sp,
                )
            }
            AlarmButton(Modifier.align(Alignment.End))
            Clock(Modifier.fillMaxWidth())
            Spacer(Modifier.height(43.dp))
            WakeButton(onWake, Modifier.align(Alignment.CenterHorizontally))
            LiveGraph(!dimmed, Modifier.fillMaxWidth().weight(1f))
            if (prefs.recordSound) VuBar(!dimmed, Modifier.fillMaxWidth().padding(20.dp))
        }
        if (overlay > 0f) {
            Box(
                Modifier.fillMaxSize().alpha(overlay).background(Color.Black)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

@Composable
private fun AlarmButton(modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val next by ctx.app.alarms.nextAlarm.collectAsState()
    val text = next?.let { n ->
        if (prefs.smartAlarm) TimeFormat.time(ctx, n - prefs.smartWindowMin * 60_000L) + " ~ " + TimeFormat.time(ctx, n)
        else TimeFormat.time(ctx, n)
    } ?: stringResource(R.string.trk_no_alarm_set)
    Row(
        modifier.clickable {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.nightstand_alarm_unselected), null, Modifier.size(33.dp))
        Text(text, color = Color.White, fontSize = 20.sp)
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = Calendar.getInstance()
            delay((60 - c.get(Calendar.SECOND)) * 1000L - c.get(Calendar.MILLISECOND) + 50)
            now = System.currentTimeMillis()
        }
    }
    val is24 = DateFormat.is24HourFormat(ctx)
    val time = SimpleDateFormat(if (is24) "HH:mm" else "hh:mm", Locale.getDefault()).format(Date(now))
    val pm = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.AM_PM) == Calendar.PM
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Text(time, color = Color.White, fontSize = 84.sp, fontFamily = RobotoThin, textAlign = TextAlign.Center)
        if (!is24) {
            Text(
                stringResource(if (pm) R.string.trk_pm else R.string.trk_am),
                Modifier.padding(bottom = 18.dp),
                color = Color.White, fontSize = 20.sp, fontFamily = RobotoThin,
            )
        }
    }
}

@Composable
private fun WakeButton(onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Image(
        painterResource(if (pressed) R.drawable.mainbuttonwake_selected else R.drawable.mainbuttonwake_unselected),
        stringResource(R.string.trk_waking_up),
        modifier.width(306.dp).clickable(interactionSource = source, indication = null, onClick = onClick),
        contentScale = ContentScale.FillWidth,
    )
}

@Composable
private fun LiveGraph(active: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val max = remember { movementMax(ctx.app.prefs.motionSensitivity).toFloat() }
    val live by TrackingService.movement.collectAsState()
    var shown by remember { mutableStateOf(live) }
    if (active) shown = live // legacy: graph stops updating while dimmed
    val labels = remember(shown) { List(MotionTracker.GRAPH_SIZE) { shown.labels.getOrElse(it) { "" } } }
    GraphView(
        GraphSpec(
            type = GraphType.SMOOTH,
            xLabels = labels,
            yLabels = emptyList(),
            values = shown.values,
            max = max, diff = max,
            markers = false,
            background = Color.Transparent,
            lineColor = Color(0x8CFFFFFF),
            yFade = false,
        ),
        modifier,
    )
}

@Composable
private fun VuBar(active: Boolean, modifier: Modifier) {
    val amp by TrackingService.amplitude.collectAsState()
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(amp, active) { if (active && amp > level) level = amp.toFloat() }
    LaunchedEffect(Unit) {
        // Legacy VU fall-off: −1000 every 20 ms.
        while (true) { delay(20); if (level > 0f) level = (level - 1000f).coerceAtLeast(0f) }
    }
    LinearProgressIndicator(
        progress = { level / 32767f },
        modifier = modifier,
        color = Color(0xFF33B5E5),
        trackColor = Color(0x33FFFFFF),
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun rememberCharging(): Boolean {
    val ctx = LocalContext.current
    var charging by remember { mutableStateOf(isCharging(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                charging = when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    else -> isCharging(i)
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return charging
}

private fun isCharging(i: Intent?): Boolean {
    val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    return plugged != 0
}
