package com.sleepbot.app.alarm

import androidx.core.content.edit
import com.sleepbot.app.data.Prefs

/* Extra preferences owned by the alarm/settings agent (not in data/Prefs.kt). */

/** Legacy NOTIFICATION_ICON: ongoing "next alarm" status notification. */
val Prefs.alarmStatusIcon: Boolean get() = sp.getBoolean("alarm_notification_icon", true)

/** Legacy ALARM_TIMEOUT, minutes. */
val Prefs.alarmTimeoutMin: Int get() = sp.getString("alarm_timeout", "10")?.toIntOrNull() ?: 10

/** Legacy "14" AutoSilence: 0 nothing, 1 vibrate, 2 silent. */
val Prefs.autoSilence: Int get() = sp.getString("auto_silence", "0")?.toIntOrNull() ?: 0

/** Legacy "90": no "remind later" flow on bedtime reminders. */
val Prefs.reminderNoLater: Boolean get() = sp.getBoolean("reminder_no_later", false)

/** Legacy "92_1": alarms armed by Auto Alarm at the last punch-in (comma separated ids). */
internal var Prefs.autoAlarmIds: List<Long>
    get() = sp.getString("auto_alarm_ids", "")!!.split(',').mapNotNull { it.toLongOrNull() }
    set(v) = sp.edit { putString("auto_alarm_ids", v.joinToString(",")) }

/** Ringer mode / interruption filter to restore after AutoSilence (−1 = none saved). */
internal var Prefs.autoSilenceSaved: Int
    get() = sp.getInt("auto_silence_saved", -1)
    set(v) = sp.edit { putInt("auto_silence_saved", v) }
