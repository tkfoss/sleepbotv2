package com.sleepbot.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-day aggregate keyed by the calendar date the sleep ended on (legacy Statistics.Stat). */
data class DayStat(
    val date: LocalDate,
    val hours: Double,
    /** Mean rating of rated entries that day, 0 when none were rated. */
    val rating: Double,
    val hasData: Boolean,
)

/** Port of legacy Statistics: sleep is attributed to the wake-up date. */
class Stats(
    entries: List<SleepEntry>,
    val from: LocalDate,
    val to: LocalDate,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    val days: List<DayStat>
    val totalHours: Double
    /** Number of distinct wake dates that have at least one entry. */
    val uniqueDays: Int

    init {
        val byDay = entries.filter { it.durationHours >= 0 }
            .groupBy { wakeDate(it, zone) }
        days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val list = byDay[d].orEmpty()
            val rated = list.filter { it.rating > 0 }
            DayStat(
                date = d,
                hours = list.sumOf { it.durationHours },
                rating = if (rated.isEmpty()) 0.0 else rated.map { it.rating }.average(),
                hasData = list.isNotEmpty(),
            )
        }.toList()
        totalHours = days.sumOf { it.hours }
        uniqueDays = days.count { it.hasData }
    }

    private fun size(uniqueOnly: Boolean) = if (uniqueOnly) uniqueDays else days.size

    fun averageHours(uniqueOnly: Boolean): Double =
        if (days.isEmpty() || uniqueDays == 0) 0.0 else totalHours / size(uniqueOnly)

    fun averageRating(): Double = days.filter { it.rating > 0 }.map { it.rating }.average().takeIf { !it.isNaN() } ?: 0.0

    fun debt(optimalHours: Float, uniqueOnly: Boolean): Double = optimalHours * size(uniqueOnly) - totalHours

    /** Histogram of day lengths rounded to the hour, 0..11 and a final 12+ bucket (legacy numPerHour). */
    fun lengthHistogram(): IntArray {
        val out = IntArray(13)
        days.filter { it.hasData }.forEach { d ->
            if (d.hours < 12.0) out[Math.round(d.hours).toInt()]++ else out[12]++
        }
        return out
    }

    companion object {
        fun wakeDate(e: SleepEntry, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochMilli(e.awake).atZone(zone).toLocalDate()

        /** Bedtime / wake-up hour-of-day histograms (legacy numPerSleep / numPerAwake). */
        fun hourHistograms(entries: List<SleepEntry>, zone: ZoneId = ZoneId.systemDefault()): Pair<IntArray, IntArray> {
            val sleep = IntArray(24)
            val wake = IntArray(24)
            entries.forEach {
                sleep[Instant.ofEpochMilli(it.sleep).atZone(zone).hour]++
                wake[Instant.ofEpochMilli(it.awake).atZone(zone).hour]++
            }
            return sleep to wake
        }
    }
}
