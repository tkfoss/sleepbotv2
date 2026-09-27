package com.sleepbot.app

import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.data.Stats
import com.sleepbot.app.ui.entries.buildRows
import com.sleepbot.app.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StatsTest {
    private val zone = ZoneId.of("UTC")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun entry(sleep: String, wake: String, rating: Int = -1) = SleepEntry(sleep = ms(sleep), awake = ms(wake), rating = rating)

    @Test fun attributesSleepToWakeDate() {
        val e = listOf(
            entry("2026-02-21T23:35", "2026-02-22T00:08"),
            entry("2026-02-22T04:06", "2026-02-22T08:30"),
            entry("2026-02-22T20:32", "2026-02-22T21:28"),
        )
        val s = Stats(e, LocalDate.parse("2026-02-21"), LocalDate.parse("2026-02-22"), zone)
        assertEquals(2, s.days.size)
        assertEquals(0.0, s.days[0].hours, 1e-9)
        assertEquals(0.55 + 4.4 + 0.9333, s.days[1].hours, 1e-3)
        assertEquals(1, s.uniqueDays)
        // Debt over both days, empty days count (legacy getDebt(false)).
        assertEquals(16 - s.totalHours, s.debt(8f, uniqueOnly = false), 1e-9)
    }

    @Test fun ratingsAverageOnlyRated() {
        val e = listOf(entry("2026-03-01T00:00", "2026-03-01T06:00", 4), entry("2026-03-01T13:00", "2026-03-01T14:00", -1))
        val s = Stats(e, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"), zone)
        assertEquals(4.0, s.days[0].rating, 1e-9)
    }

    @Test fun lengthHistogramBuckets() {
        val e = listOf(entry("2026-03-01T00:00", "2026-03-01T07:40"), entry("2026-03-02T00:00", "2026-03-02T13:00"))
        val h = Stats(e, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"), zone).lengthHistogram()
        assertEquals(1, h[8])
        assertEquals(1, h[12])
    }

    @Test fun rowsShowDateOnFirstAndDebtOnLastOfDay() {
        val e = listOf(
            entry("2026-02-22T20:32", "2026-02-22T21:28"),
            entry("2026-02-22T04:06", "2026-02-22T08:30"),
            entry("2026-02-21T03:37", "2026-02-21T09:21"),
        )
        val rows = buildRows(e, 8f, decimal = true, shortDate = DateTimeFormatter.ofPattern("MM/dd"), zone = zone)
        assertEquals("02/22", rows[0].date)
        assertEquals("", rows[0].debt)
        assertEquals("", rows[1].date)
        assertEquals("2.7", rows[1].debt) // 8 - (0.93 + 4.4)
        assertEquals("02/21", rows[2].date)
        assertEquals("2.3", rows[2].debt)
    }

    @Test fun numberFormatting() {
        assertEquals("07:30", TimeFormat.numberToHours(7.5))
        assertEquals("-02:05", TimeFormat.numberToHours(-2.0834))
        assertEquals("21:44", TimeFormat.numberToHours(21.7334))
        assertEquals(6 to 24, TimeFormat.formattedHour(6.4))
        assertEquals("-1.5", TimeFormat.debtCell(-1.5, true))
        assertEquals("03:30", TimeFormat.hourCell(3.5, false))
    }
}
