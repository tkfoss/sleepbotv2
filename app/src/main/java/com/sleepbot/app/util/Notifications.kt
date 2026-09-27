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

object Notifications {
    const val CH_SESSION = "session"
    const val CH_TRACKING = "tracking"
    const val CH_ALARM = "alarm"
    const val CH_REMINDER = "reminder"
    const val CH_ALARM_STATUS = "alarm_status"

    const val ID_PUNCH = 1
    const val ID_TRACKING = 2
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
}
