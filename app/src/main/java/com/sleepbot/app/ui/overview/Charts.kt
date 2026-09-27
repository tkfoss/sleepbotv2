package com.sleepbot.app.ui.overview

import com.sleepbot.app.data.Prefs
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.data.Stats
import com.sleepbot.app.ui.graph.GraphSpec
import com.sleepbot.app.ui.graph.GraphType
import com.sleepbot.app.ui.graph.PatternSegment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** Builders for the legacy chart types (GraphActivity / OverviewFragment). */
object Charts {
    private val trendY = listOf("12+") + (11 downTo 0).map { it.toString() }

    private fun dayLabel(d: LocalDate, prefs: Prefs, withDow: Boolean): String {
        val p = (if (prefs.dayFirst()) "dd/MM" else "MM/dd") + if (withDow) " EEE" else ""
        return d.format(DateTimeFormatter.ofPattern(p, Locale.US))
    }

    fun trend(entries: List<SleepEntry>, days: Int, title: String, prefs: Prefs, ratings: Boolean, overview: Boolean, zone: ZoneId = ZoneId.systemDefault()): GraphSpec {
        val today = LocalDate.now(zone)
        val stats = Stats(entries, today.minusDays(days - 1L), today, zone)
        val step = if (overview) 1 else max(1, days / 8)
        return GraphSpec(
            type = GraphType.TREND,
            title = title,
            xLabels = stats.days.mapIndexed { i, d -> if (i % step == 0) dayLabel(d.date, prefs, !overview) else "" },
            yLabels = trendY,
            values = FloatArray(stats.days.size) { stats.days[it].hours.coerceIn(0.0, 12.0).toFloat() },
            ratings = if (ratings) FloatArray(stats.days.size) { stats.days[it].rating.toFloat() } else null,
            optimal = prefs.optimalHours,
            disconnectedLines = prefs.showDisconnectedLines,
            markers = days <= 30,
            titleCentered = overview,
        )
    }

    private fun tallyY(maxCount: Int, divisor: Int): List<String> {
        val stepY = max(1, maxCount / divisor)
        return (maxCount downTo 0).map { v -> if (v == maxCount || v % stepY == 0) v.toString() else "" }
    }

    fun length(entries: List<SleepEntry>, days: Int, title: String, zone: ZoneId = ZoneId.systemDefault()): GraphSpec {
        val today = LocalDate.now(zone)
        val hist = Stats(entries, today.minusDays(days - 1L), today, zone).lengthHistogram()
        val m = hist.max()
        return GraphSpec(
            type = GraphType.BARS, title = title,
            xLabels = (0..11).map { it.toString() } + "12+",
            yLabels = tallyY(m, 10),
            values = FloatArray(13) { hist[it].toFloat() },
            max = m.toFloat(), diff = max(1, m).toFloat(),
        )
    }

    fun hourTally(entries: List<SleepEntry>, wake: Boolean, title: String, is24: Boolean, zone: ZoneId = ZoneId.systemDefault()): GraphSpec {
        val (s, w) = Stats.hourHistograms(entries, zone)
        val hist = if (wake) w else s
        val m = hist.max()
        val labels = (0 until 24).map { h ->
            if (h % 3 != 0) "" else if (is24) "$h:00" else when (h) { 0 -> "12am"; 12 -> "12pm"; else -> if (h < 12) "${h}am" else "${h - 12}pm" }
        }
        return GraphSpec(
            type = GraphType.BARS, title = title, xLabels = labels, yLabels = tallyY(m, 6),
            values = FloatArray(24) { hist[it].toFloat() }, max = m.toFloat(), diff = max(1, m).toFloat(),
        )
    }

    fun pattern(entries: List<SleepEntry>, days: Int, title: String, prefs: Prefs, overview: Boolean, zone: ZoneId = ZoneId.systemDefault()): GraphSpec {
        val today = LocalDate.now(zone)
        val first = today.minusDays(days.toLong())
        val keys = (0..days).map { first.plusDays(it.toLong()) }
        val segs = ArrayList<PatternSegment>()
        val rangeStart = first.atStartOfDay(zone).toInstant().toEpochMilli()
        fun hourOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour + it.minute / 60f }
        entries.filter { it.awake > it.sleep }.forEach { e ->
            var start = max(e.sleep, rangeStart)
            var firstDay = e.sleep >= rangeStart
            var guard = 0
            while (start < e.awake && guard++ < 50) {
                val d = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val idx = (d.toEpochDay() - first.toEpochDay()).toInt()
                val sh = if (firstDay) hourOf(start) else 0f
                val eh = if (e.awake >= dayEnd) 24f else hourOf(e.awake)
                if (idx in keys.indices) segs += PatternSegment(idx, sh, eh)
                start = dayEnd
                firstDay = false
            }
        }
        val is24 = prefs.is24h()
        val y = (0..24).map { h ->
            if (h % 3 != 2 || h == 24) "" else if (is24) String.format(Locale.US, "%02d:00", h)
            else if (h < 12) "${h}am" else if (h == 12) "12pm" else "${h - 12}pm"
        }
        val step = if (overview) max(1, keys.size / 10) else max(1, keys.size / 8)
        return GraphSpec(
            type = GraphType.PATTERN, title = title,
            xLabels = keys.mapIndexed { i, d -> if (i % step == 0) dayLabel(d, prefs, !overview) else "" },
            yLabels = y, pattern = segs, titleCentered = overview, yFade = true,
        )
    }
}
