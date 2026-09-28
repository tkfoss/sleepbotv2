package com.sleepbot.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sleepbot.app.MainActivity
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.tracking.NightActivity

object Notifications {
    const val CH_SESSION = "session"
    const val CH_TRACKING = "tracking"
    const val CH_ALARM = "alarm"
    const val CH_REMINDER = "reminder"
    const val CH_ALARM_STATUS = "alarm_status"
    /** High importance so its full-screen intent may open the night screen over the lock screen. */
    const val CH_NIGHT = "night_screen"

    const val ID_PUNCH = 1
    const val ID_TRACKING = 2
    const val ID_NIGHT = 3
    const val ID_ALARM_RINGING = 69
    const val ID_ALARM_STATUS = 70

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_SESSION, context.getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_TRACKING, context.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
            NotificationChannel(CH_REMINDER, context.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_ALARM_STATUS, context.getString(R.string.channel_alarm_status), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CH_NIGHT, context.getString(R.string.channel_night), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
            },
        ))
    }

    fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Legacy "Punched in since …" ongoing notification (mode 0/1). */
    @SuppressLint("MissingPermission") // canPost() checked
    fun showAsleep(context: Context, since: Long) {
        val prefs = context.app.prefs
        if (prefs.notificationMode == 2 || !canPost(context)) return
        val time = TimeFormat.time(context, since)
        val n = NotificationCompat.Builder(context, CH_SESSION)
            .setSmallIcon(R.drawable.ic_stat_asleep)
            .setContentTitle(context.getString(R.string.punched_in_since, time))
            .setContentText(prefs.sleepNotificationText)
            .setTicker(context.getString(R.string.status_set_sleep))
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_PUNCH, n)
    }

    @SuppressLint("MissingPermission") // canPost() checked
    fun showAwake(context: Context) {
        val prefs = context.app.prefs
        val nm = NotificationManagerCompat.from(context)
        if (prefs.notificationMode != 0 || !canPost(context)) {
            nm.cancel(ID_PUNCH)
            return
        }
        val n = NotificationCompat.Builder(context, CH_SESSION)
            .setSmallIcon(R.drawable.ic_stat_asleep)
            .setContentTitle(context.getString(R.string.not_punched_in))
            .setContentText(prefs.wakeNotificationText)
            .setTicker(context.getString(R.string.status_set_wake))
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()
        nm.notify(ID_PUNCH, n)
    }

    /**
     * Legacy screen-on launch of the night screen. Apps can't start activities from the background any
     * more, but a full-screen intent is launched by the system when the phone is locked.
     */
    @SuppressLint("MissingPermission") // canPost() checked
    fun showNightScreen(context: Context) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, ID_NIGHT, NightActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_NIGHT)
            .setSmallIcon(R.drawable.ic_stat_asleep)
            .setContentTitle(context.getString(R.string.trk_notification_title))
            .setContentText(context.getString(R.string.trk_night_screen_open))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(NIGHT_TIMEOUT)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_NIGHT, n)
    }

    fun cancelNightScreen(context: Context) = NotificationManagerCompat.from(context).cancel(ID_NIGHT)

    private const val NIGHT_TIMEOUT = 10_000L
}
