package com.sleepbot.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Home/overview numbers (legacy Functions.getStatsWithResetTime + HomeFragment). */
data class DebtSummary(
    val todayHours: Double,
    val debt: Double,
    val averagePerDay: Double,
    val days: Int,
    val totalHours: Double,
)

object Debt {
    const val DAY = 86_400_000L

    fun todayStart(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Start (exclusive, ms) of the debt window. */
    fun windowFrom(prefs: Prefs, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = todayStart(zone)
        val resetFrom = prefs.debtResetTime - DAY
        val rangeFrom = today - prefs.debtRangeDays * DAY
        return maxOf(resetFrom, rangeFrom) + DAY
    }

    /** [entries] must cover (windowFrom, todayEnd). */
    fun summarize(entries: List<SleepEntry>, prefs: Prefs, zone: ZoneId = ZoneId.systemDefault()): DebtSummary {
        val today = LocalDate.now(zone)
        val todayStart = todayStart(zone)
        val todayHours = entries.filter { it.awake in todayStart until todayStart + DAY }.sumOf { it.durationHours }
        val fromMs = windowFrom(prefs, zone)
        val fromDate = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val inWindow = entries.filter { it.awake > fromMs && it.awake < todayStart + DAY }
        val stats = Stats(inWindow, fromDate, today, zone)
        val n = stats.days.size
        return DebtSummary(
            todayHours = todayHours,
            debt = if (n == 0) 0.0 else prefs.optimalHours * n - stats.totalHours,
            averagePerDay = if (n == 0) 0.0 else stats.totalHours / n,
            days = n,
            totalHours = stats.totalHours,
        )
    }

    /** Legacy Statistics.needReset: total ≤ optimal over ≥ 5 days. */
    fun needsReset(s: DebtSummary, prefs: Prefs) = s.totalHours <= prefs.optimalHours && s.days >= 5
}
