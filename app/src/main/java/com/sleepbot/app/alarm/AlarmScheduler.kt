package com.sleepbot.app.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.util.TimeFormat
import java.util.Calendar

/** Hooks called by SleepSession / tracking (legacy Behaviors.changeAlarm + smart alarm glue). */
object AlarmScheduler {
    const val AUTO_ALARM_NAME = "Auto Alarm"

    /** Auto Alarm creation + arm smart-window movement tracking. */
    fun onPunchIn(context: Context, sleepStart: Long) {
        val ctx = context.applicationContext
        val prefs = ctx.app.prefs
        val repo = ctx.app.alarms
        applyAutoSilence(ctx)
        if (prefs.autoAlarm) {
            val optimal = sleepStart + (prefs.optimalHours * 3_600_000f).toLong() + prefs.punchInDelayMin * 60_000L
            val cal = Calendar.getInstance().apply { timeInMillis = optimal }
            val sec = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
            val armed = mutableListOf<Long>()
            for (a in repo.alarms.value) {
                if (a.name == AUTO_ALARM_NAME) {
                    repo.update(a.copy(timeSec = sec, enabled = true, snoozeUntil = 0, ringing = false))
                    armed += a.id
                } else if (Math.abs(a.nextOccurrence() - optimal) <= 15 * 60_000L) {
                    // Legacy also (re)schedules nearby alarms; only remember the ones we switched on,
                    // so punch-out never disables an alarm the user had enabled themselves.
                    if (!a.enabled) { repo.setEnabled(a.id, true); armed += a.id }
                    else armed += -1
                }
            }
            if (armed.isEmpty()) armed += repo.create(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), AUTO_ALARM_NAME).id
            prefs.autoAlarmIds = armed.filter { it >= 0 }
            repo.refresh()
            val next = repo.nextPending()?.second ?: optimal
            toast(ctx, ctx.getString(R.string.auto_alarm_set, TimeFormat.time(ctx, next), TimeFormat.countdown(ctx, next).trim()))
        } else {
            repo.refresh()
        }
    }

    /** Undo auto alarm, clear smart-alarm override. */
    fun onPunchOut(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.app.prefs
        val repo = ctx.app.alarms
        restoreAutoSilence(ctx)
        val ids = prefs.autoAlarmIds
        if (ids.isNotEmpty()) {
            ids.forEach { id -> repo.get(id)?.takeIf { !it.ringing }?.let { repo.setEnabled(id, false) } }
            prefs.autoAlarmIds = emptyList()
            toast(ctx, ctx.getString(R.string.auto_alarm_disabled))
        }
        // The smart-skipped occurrence stays skipped until it has passed (cleared in refresh()).
        if (prefs.smartFiredFor in 1..System.currentTimeMillis()) prefs.smartFiredFor = 0
        repo.refresh()
    }

    /** Light sleep detected inside the smart window: ring the next alarm now, skip its original occurrence. */
    fun smartTrigger(context: Context) {
        val ctx = context.applicationContext
        val repo = ctx.app.alarms
        val (alarm, t) = repo.nextPending() ?: return
        ctx.app.prefs.smartFiredFor = t
        // Ring through the normal path (exact alarm-clock broadcast) ~2 s from now, like the legacy
        // scheduleAlarmSecondsFromNow(id, 2), without rewriting the stored alarm time.
        repo.setOverride(alarm.id, System.currentTimeMillis() + 2_000)
    }

    // ---- AutoSilence (legacy pref "14") ----------------------------------------------------

    private fun applyAutoSilence(ctx: Context) {
        val prefs = ctx.app.prefs
        val mode = prefs.autoSilence
        if (mode == 0) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (mode == 1) {
                val old = am.ringerMode
                if (old == AudioManager.RINGER_MODE_NORMAL) {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    prefs.autoSilenceSaved = 100 + old
                }
            } else if (nm.isNotificationPolicyAccessGranted) {
                val old = nm.currentInterruptionFilter
                if (old == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    prefs.autoSilenceSaved = 200 + old
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun restoreAutoSilence(ctx: Context) {
        val prefs = ctx.app.prefs
        val saved = prefs.autoSilenceSaved
        if (saved < 0) return
        prefs.autoSilenceSaved = -1
        try {
            if (saved >= 200) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(saved - 200)
            } else {
                ctx.getSystemService(AudioManager::class.java).ringerMode = saved - 100
            }
        } catch (_: SecurityException) {
        }
    }

    private fun toast(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }
}
