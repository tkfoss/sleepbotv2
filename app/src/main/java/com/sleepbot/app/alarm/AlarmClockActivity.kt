package com.sleepbot.app.alarm

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sleepbot.app.R
import com.sleepbot.app.alarm.ui.AlarmEditScreen
import com.sleepbot.app.alarm.ui.AlarmListScreen
import com.sleepbot.app.app
import com.sleepbot.app.ui.theme.SleepBotTheme
import com.sleepbot.app.util.TimeFormat

/**
 * Stand-alone alarm list (legacy ActivityAlarmClock, singleTask). Opened from the "next alarm"
 * notification, the system alarm-clock info and SET_ALARM without extras. In-app navigation uses
 * the routes in ui/main/Nav.kt instead.
 */
class AlarmClockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SleepBotTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        AlarmListScreen(onBack = { finish() }, onEdit = { nav.navigate("edit/$it") })
                    }
                    composable("edit/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                        AlarmEditScreen(it.arguments!!.getLong("id")) { nav.popBackStack() }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AlarmRingService.current.value != null) startActivity(Intent(this, AlarmRingingActivity::class.java))
    }
}

/** android.intent.action.SET_ALARM handler (legacy SetAlarmActivity, spec §3.8). */
class SetAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent) {
        val repo = app.alarms
        if (!intent.hasExtra(android.provider.AlarmClock.EXTRA_HOUR)) {
            startActivity(Intent(this, AlarmClockActivity::class.java))
            return
        }
        val hour = intent.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, 0).coerceIn(0, 23)
        val minute = intent.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
        val message = intent.getStringExtra(android.provider.AlarmClock.EXTRA_MESSAGE) ?: ""
        val skipUi = intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
        val sec = hour * 3600 + minute * 60
        val target = Week.next(sec, 0, System.currentTimeMillis())

        val matched = mutableListOf<Long>()
        for (a in repo.alarms.value) {
            if (message.isNotEmpty() && a.name == message) {
                repo.update(a.copy(timeSec = sec, enabled = true, snoozeUntil = 0))
                matched += a.id
            } else if (Math.abs(a.nextOccurrence() - target) <= 60_000L) {
                repo.setEnabled(a.id, true)
                matched += a.id
            }
        }
        if (matched.isEmpty()) repo.create(hour, minute, 0, message)
        repo.refresh()
        val at = repo.nextPending()?.second ?: target
        Toast.makeText(this, getString(R.string.alarm_scheduled, TimeFormat.time(this, at), TimeFormat.countdown(this, at).trim()), Toast.LENGTH_LONG).show()

        val msg = message.lowercase()
        if ("go to sleep" in msg && app.prefs.isAwake) {
            val prefs = app.prefs
            if ("smart alarm" in msg) prefs.smartAlarm = true
            if ("sound" in msg) prefs.recordSound = true
            if ("movement" in msg) prefs.trackMotion = true
            app.session.punchIn()
            return
        }
        if (!skipUi) startActivity(Intent(this, AlarmClockActivity::class.java))
    }

}
