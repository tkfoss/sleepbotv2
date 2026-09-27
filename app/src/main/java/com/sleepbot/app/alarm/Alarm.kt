package com.sleepbot.app.alarm

import android.content.Context
import android.media.RingtoneManager
import com.sleepbot.app.R
import org.json.JSONObject
import java.util.Calendar

/** Per-alarm (or default, id −1) settings; legacy `settings` table. */
data class AlarmSettings(
    /** Tone URI string; null = system default alarm sound. */
    val toneUri: String? = null,
    val toneName: String = "Default",
    val snooze: Int = 10,
    val vibrate: Boolean = false,
    val volStart: Int = 0,
    val volEnd: Int = 100,
    val volTime: Int = 20,
) {
    fun clamped() = copy(
        snooze = snooze.coerceIn(1, 60),
        volStart = volStart.coerceIn(0, 100),
        volEnd = volEnd.coerceIn(0, 100),
        volTime = volTime.coerceIn(1, 600),
    )

    fun toneUriOrDefault(): android.net.Uri =
        toneUri?.let { android.net.Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    fun toJson(): JSONObject = JSONObject()
        .put("tone_url", toneUri ?: JSONObject.NULL)
        .put("tone_name", toneName)
        .put("snooze", snooze)
        .put("vibrate", vibrate)
        .put("vol_start", volStart)
        .put("vol_end", volEnd)
        .put("vol_time", volTime)

    companion object {
        fun fromJson(o: JSONObject) = AlarmSettings(
            toneUri = if (o.isNull("tone_url")) null else o.optString("tone_url"),
            toneName = o.optString("tone_name", "Default"),
            snooze = o.optInt("snooze", 10),
            vibrate = o.optBoolean("vibrate", false),
            volStart = o.optInt("vol_start", 0),
            volEnd = o.optInt("vol_end", 100),
            volTime = o.optInt("vol_time", 20),
        ).clamped()
    }
}

/**
 * One alarm (legacy `alarms` row). [dow] bit i = weekday i with SUN=0..SAT=6; 0 = no repeats.
 * [snoozeUntil] (>0) is a one-shot override of the next ring time (snooze or smart-alarm early
 * ring) — the stored [timeSec] is never rewritten by those.
 */
data class Alarm(
    val id: Long,
    val name: String = "",
    val dow: Int = 0,
    /** Seconds after midnight, 0..86399. */
    val timeSec: Int,
    val enabled: Boolean = false,
    /** Null = use the defaults (id −1). */
    val settings: AlarmSettings? = null,
    val snoozeUntil: Long = 0,
    /** True while this alarm is firing and not yet dismissed/snoozed. */
    val ringing: Boolean = false,
) {
    val hour get() = timeSec / 3600
    val minute get() = (timeSec / 60) % 60
    val repeats get() = dow != 0

    /** Next wall-clock occurrence strictly after [now] (legacy AlarmTime). */
    fun nextOccurrence(now: Long = System.currentTimeMillis()): Long = Week.next(timeSec, dow, now)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("dow", dow).put("time", timeSec).put("enabled", enabled)
        .put("settings", settings?.toJson() ?: JSONObject.NULL)
        .put("snooze_until", snoozeUntil).put("ringing", ringing)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getLong("id"),
            name = o.optString("name", ""),
            dow = o.optInt("dow", 0),
            timeSec = o.optInt("time", 0),
            enabled = o.optBoolean("enabled", false),
            settings = o.optJSONObject("settings")?.let { AlarmSettings.fromJson(it) },
            snoozeUntil = o.optLong("snooze_until", 0),
            ringing = o.optBoolean("ringing", false),
        )
    }
}

object Week {
    const val NO_REPEATS = 0
    const val EVERYDAY = 0b1111111
    const val WEEKDAYS = 0b0111110
    const val WEEKENDS = 0b1000001
    private val SHORT = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    val LONG = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    fun has(dow: Int, day: Int) = dow and (1 shl day) != 0

    /** List-row summary; "" for no repeats (legacy Week.toString). */
    fun summary(context: Context, dow: Int, noRepeatText: Boolean = false): String = when (dow) {
        NO_REPEATS -> if (noRepeatText) context.getString(R.string.alarm_no_repeats) else ""
        EVERYDAY -> context.getString(R.string.alarm_every_day)
        WEEKDAYS -> context.getString(R.string.alarm_weekdays)
        WEEKENDS -> context.getString(R.string.alarm_weekends)
        else -> buildString { for (i in 0..6) if (has(dow, i)) append(' ').append(SHORT[i]) }
    }

    /** Legacy AlarmTime: today at H:M:S, +1 day if not after now, then advance to an enabled weekday. */
    fun next(timeSec: Int, dow: Int, now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, timeSec / 3600)
            set(Calendar.MINUTE, (timeSec / 60) % 60)
            set(Calendar.SECOND, timeSec % 60)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        if (dow != 0) {
            var n = 0
            while (!has(dow, c.get(Calendar.DAY_OF_WEEK) - 1) && n < 7) {
                c.add(Calendar.DAY_OF_YEAR, 1); n++
            }
        }
        return c.timeInMillis
    }
}
