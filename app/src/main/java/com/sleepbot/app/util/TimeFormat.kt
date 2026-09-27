package com.sleepbot.app.util

import android.content.Context
import android.text.format.DateFormat
import com.sleepbot.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Formatting helpers ported from Record / Utils / AppUtils. */
object TimeFormat {
    private val hhmm = SimpleDateFormat("HH:mm", Locale.US)

    /** Localized wall-clock time, "h:mm a" or "HH:mm" per system setting. */
    fun time(context: Context, ms: Long): String =
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

    /** Always-24h table time. */
    fun table(ms: Long): String = synchronized(hhmm) { hhmm.format(Date(ms)) }

    fun pad2(n: Int) = if (n < 10) "0$n" else n.toString()

    /** Utils.numberToHours: signed "HH:MM" with truncated minutes. */
    fun numberToHours(n: Double): String {
        val sign = if (n < 0) "-" else ""
        val a = abs(n)
        val h = floor(a).toInt()
        val m = ((a - h) * 60).toInt()
        return sign + pad2(h) + ":" + pad2(m)
    }

    /** AppUtils.getFormattedHour: rounded minutes with carry. Returns (hour, minute), hour carries sign. */
    fun formattedHour(h: Double): Pair<Int, Int> {
        var hour = h.toInt()
        var minute = ((h - hour) * 60 + if (h < 0) -0.5 else 0.5).toInt()
        if (minute >= 60) { minute -= 60; hour++ }
        if (minute <= -60) { minute += 60; hour-- }
        return hour to abs(minute)
    }

    /** "7 hours 30 minutes" (Record.getDuration_text). */
    fun durationText(context: Context, hoursD: Double): String {
        var hour = hoursD.toInt()
        var min = ((hoursD - hour) * 60 + 0.5).toInt()
        if (min == 60) { min = 0; hour++ }
        val h = context.getString(if (hour > 1) R.string.hours_n else R.string.hour_n, hour)
        val m = context.getString(if (min > 1) R.string.minutes_n else R.string.minute_n, min)
        return "$h $m"
    }

    /** Entry-list hour cell: "#0.0" decimal or "HH:MM". */
    fun hourCell(h: Double, decimal: Boolean): String =
        if (decimal) String.format(Locale.US, "%.1f", h) else pad2(floor(h).toInt()) + ":" + pad2(floor((h - floor(h)) * 60).toInt())

    fun debtCell(d: Double, decimal: Boolean): String = if (d < 0) "-" + hourCell(-d, decimal) else hourCell(d, decimal)

    /** "1 day 2 hours 5 minutes " countdown (AlarmTime.timeUntilString). */
    fun countdown(context: Context, targetMs: Long, now: Long = System.currentTimeMillis()): String {
        val totalMin = (targetMs - now) / 60_000
        if (targetMs < now) return context.getString(R.string.alarm_has_occurred)
        val d = (totalMin / (60 * 24)).toInt()
        val h = ((totalMin / 60) % 24).toInt()
        val m = (totalMin % 60).toInt()
        val sb = StringBuilder()
        if (d > 0) sb.append(context.getString(if (d == 1) R.string.day_n else R.string.days_n, d)).append(' ')
        if (h > 0) sb.append(context.getString(if (h == 1) R.string.hour_n else R.string.hours_n, h)).append(' ')
        if (m > 0) sb.append(context.getString(if (m == 1) R.string.minute_n else R.string.minutes_n, m)).append(' ')
        return sb.toString()
    }
}
