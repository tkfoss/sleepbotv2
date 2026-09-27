package com.sleepbot.app.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.data.Prefs
import com.sleepbot.app.util.Notifications
import kotlinx.coroutines.launch

/** "90": no "remind later" dialog (auto-cancel reminder). Same key as alarm/AlarmPrefs.kt. */
private val Prefs.noRemindLater: Boolean get() = sp.getBoolean(Reminders.KEY_NO_LATER, false)

/** Bedtime reminders (legacy ReminderService). */
object Reminders {
    const val KEY_NO_LATER = "reminder_no_later"
    private const val KEY_LATER_AT = "reminder_later_at"
    private const val KEY_LAST_FIRED = "reminder_last_fired"
    const val TAG = "ReminderService"
    internal const val ACTION_NOTIFY = "com.sleepbot.app.reminder.NOTIFY"

    /** Reschedule both reminders from the next alarm (boot, pref change, alarm change, punch in/out). */
    fun reschedule(context: Context) = schedule(context, null)

    /** SleepCycle "Remind Later": both reminders at exactly [time]. */
    fun remindAt(context: Context, time: Long) = schedule(context, time)

    private fun schedule(context: Context, time: Long?) {
        val app = context.app
        val prefs = app.prefs
        val next = app.alarms.nextAlarm.value
        if (next == null || !prefs.isAwake) {
            cancelNotifications(context)
            cancelAlarm(context, 1); cancelAlarm(context, 2)
            prefs.sp.edit { remove(KEY_LATER_AT) }
            return
        }
        val now = System.currentTimeMillis()
        if (time != null) {
            cancelAlarm(context, 1); cancelAlarm(context, 2)
            prefs.sp.edit { putLong(KEY_LATER_AT, time) }
        }
        val later = prefs.sp.getLong(KEY_LATER_AT, 0).takeIf { it > now }
        val bedtime = next - (prefs.optimalHours * 3_600_000L).toLong()
        if (prefs.reminder1) set(context, 1, later ?: (bedtime - prefs.reminder1Offset * 60_000L)) else cancelAlarm(context, 1)
        if (prefs.reminder2) set(context, 2, later ?: (bedtime - prefs.reminder2Offset * 60_000L)) else cancelAlarm(context, 2)
    }

    private fun pending(context: Context, which: Int, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 2 + which,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_NOTIFY).putExtra("which", which),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun set(context: Context, which: Int, at: Long) {
        // Only future times: a past bedtime would otherwise fire at once on every reschedule.
        if (at <= System.currentTimeMillis()) { cancelAlarm(context, which); return }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, which, PendingIntent.FLAG_UPDATE_CURRENT)!!
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context, which: Int) {
        pending(context, which, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
        }
    }

    fun cancelNotifications(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(TAG, 1); nm.cancel(TAG, 2)
    }

    /** Alarm fired (legacy action "notify"). */
    internal suspend fun onNotify(context: Context) {
        val app = context.app
        val prefs = app.prefs
        prefs.sp.edit { remove(KEY_LATER_AT) }
        if (app.alarms.nextAlarm.value == null || !prefs.isAwake) {
            cancelNotifications(context); return
        }
        val nm = NotificationManagerCompat.from(context)
        when {
            prefs.reminder1 -> { nm.cancel(TAG, 2); remind(context, 1) }
            prefs.reminder2 -> { nm.cancel(TAG, 1); remind(context, 2) }
            else -> cancelNotifications(context)
        }
    }

    private suspend fun remind(context: Context, id: Int) {
        val app = context.app
        val prefs = app.prefs
        val now = System.currentTimeMillis()
        if (now - prefs.sp.getLong(KEY_LAST_FIRED, 0) < 20_000) return
        // A real sleep that ended < 3 h ago means no reminder (a nap is not an excuse).
        val last = app.db.entries().lastBefore(Long.MAX_VALUE)
        if (last != null && now - last.awake < 10_800_000L && last.durationHours / prefs.optimalHours > 0.5) return
        prefs.sp.edit { putLong(KEY_LAST_FIRED, now) }
        if (!Notifications.canPost(context)) return
        val noLater = prefs.noRemindLater
        val tap = Intent(context, SleepCycleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (noLater) tap.action = SleepCycleActivity.ACTION_CANCEL
        val pi = PendingIntent.getActivity(context, 40 + id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.trk_sleep_reminder_toast)
        val b = NotificationCompat.Builder(context, Notifications.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_asleep)
            .setContentTitle(context.getString(R.string.trk_sleep_reminder_title))
            .setContentText(text)
            .setTicker(text)
            .setContentIntent(pi)
            .setAutoCancel(noLater)
            .setSilent(prefs.reminderMuted)
        if (!noLater) b.setLights(0xFF33B5E5.toInt(), 1000, 3000)
        try {
            NotificationManagerCompat.from(context).notify(TAG, id, b.build())
        } catch (_: SecurityException) {}
    }
}

/** Reminder alarms + reschedule triggers (boot, time change, app update). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        context.app.appScope.launch {
            try {
                runCatching { context.app.alarms.refresh() }
                if (intent.action == Reminders.ACTION_NOTIFY) Reminders.onNotify(context)
                else Reminders.reschedule(context)
            } finally {
                pr.finish()
            }
        }
    }
}
