package com.sleepbot.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sleepbot.app.app
import com.sleepbot.app.tracking.Reminders
import com.sleepbot.app.tracking.TrackingService

/** Exact alarm-clock broadcast for one alarm (legacy ReceiverAlarm). */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.sleepbot.app.alarm.FIRE"
        const val EXTRA_ID = "alarm_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val alarm = context.app.alarms.get(id) ?: return
        if (!alarm.enabled || alarm.ringing) return
        AlarmRingService.fire(context, id)
    }
}

/** Boot / package replaced / time or timezone change: re-arm everything. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = context.app.alarms
        // A ring interrupted by reboot is treated as timed out (acknowledged).
        repo.alarms.value.filter { it.ringing }.forEach { repo.acknowledge(it.id) }
        repo.refresh()
        runCatching { Reminders.reschedule(context) }
    }
}

/** Smart-alarm window start: begin motion tracking (legacy RecordMovementService start). */
class SmartWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.app.prefs
        if (prefs.isAwake || !prefs.smartAlarm) return
        TrackingService.startSmartWindow(context)
    }
}

/** Minute tick for the "Next alarm" status notification countdown. */
class StatusTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.app.alarms.updateStatusNotification()
    }
}
