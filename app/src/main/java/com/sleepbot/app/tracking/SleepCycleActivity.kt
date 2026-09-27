package com.sleepbot.app.tracking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.ui.theme.SleepBotTheme
import com.sleepbot.app.util.TimeFormat

/** "Remind Later" sleep-cycle chooser opened from the bedtime reminder (legacy SleepCycleActivity). */
class SleepCycleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_CANCEL) { done(true); return }
        app.alarms.refresh()
        val next = app.alarms.nextAlarm.value
        if (next == null) { done(true); return }
        setContent { SleepBotTheme { Chooser(next, ::done) } }
    }

    private fun done(dismiss: Boolean) {
        if (dismiss) Reminders.cancelNotifications(this)
        finish()
    }

    companion object {
        const val ACTION_CANCEL = "action_cancel"

        /** Candidate reminder times, 90 min apart, ending at (alarm − 30 min) − 90 min. */
        fun candidates(nextAlarm: Long, now: Long): List<Long> {
            val cycle = 5_400_000L
            val target = nextAlarm - 1_800_000L
            if (target - now - cycle <= 0) return emptyList()
            var first = target - cycle
            while (first >= now) first -= cycle
            val out = ArrayList<Long>()
            while (true) {
                first += cycle
                if (first <= target - cycle) out += first else break
            }
            return out
        }
    }
}

@Composable
private fun Chooser(next: Long, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var times by remember { mutableStateOf(emptyList<Long>()) }
    var choice by remember { mutableIntStateOf(0) }
    when (step) {
        0 -> AlertDialog(
            onDismissRequest = { done(true) },
            title = { Text(stringResource(R.string.trk_remind_later)) },
            text = { Text(stringResource(R.string.trk_remind_with_sleep_cycle)) },
            confirmButton = {
                TextButton({
                    times = SleepCycleActivity.candidates(next, System.currentTimeMillis())
                    step = if (times.isEmpty()) 1 else 2
                }) { Text(stringResource(R.string.trk_choose_time)) }
            },
            dismissButton = { TextButton({ done(true) }) { Text(stringResource(R.string.cancel)) } },
        )
        1 -> AlertDialog(
            onDismissRequest = { done(true) },
            text = { Text(stringResource(R.string.trk_you_should_really_go_to_sleep)) },
            confirmButton = { TextButton({ done(true) }) { Text(stringResource(R.string.ok)) } },
        )
        else -> AlertDialog(
            onDismissRequest = { done(true) },
            title = { Text(stringResource(R.string.trk_remind_later)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    times.forEachIndexed { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = i }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == i, onClick = { choice = i })
                            Text(TimeFormat.time(ctx, t))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    Reminders.remindAt(ctx, times[choice])
                    done(false)
                }) { Text(stringResource(R.string.trk_remind_later)) }
            },
            dismissButton = { TextButton({ done(true) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
