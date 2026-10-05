package vn.chamcong.iot.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class CalculationReadRangesTest {
    private fun AttendanceDateRange.contains(date: LocalDate) = date >= start && date <= endInclusive

    @Test fun historicalMonthAndSeparatePresenceDateNeverReadInterveningYears() {
        val selected = LocalDate.parse("2018-09-17")
        val presence = LocalDate.parse("2021-04-15")
        val today = LocalDate.parse("2026-10-04")
        val ranges = scheduleReadRanges(selected, presence, today)
        assertEquals(3, ranges.size)
        assertTrue(ranges.all { ChronoUnit.DAYS.between(it.start, it.endInclusive) <= 43 })
        assertTrue(ranges.sumOf { ChronoUnit.DAYS.between(it.start, it.endInclusive) + 1 } <= 56)
        assertFalse(ranges.any { it.contains(LocalDate.parse("2020-01-01")) })
        assertFalse(ranges.any { it.contains(LocalDate.parse("2024-01-01")) })
        assertTrue(ranges.any { it.contains(presence.minusDays(1)) && it.contains(presence.plusDays(1)) })
        assertTrue(ranges.any { it.contains(today) })
    }

    @Test fun selectedMonthCoverageIncludesEveryCalendarDayAndAdjacentOvernightDate() {
        for (month in listOf(YearMonth.of(2026, 2), YearMonth.of(2026, 8), YearMonth.of(2026, 12))) {
            val selected = month.atDay(15)
            val ranges = scheduleReadRanges(selected, LocalDate.parse("2020-01-01"), LocalDate.parse("2028-01-01"))
            val selectedRange = ranges.single { it.contains(selected) }
            assertTrue(selectedRange.contains(month.atDay(1).minusDays(1)))
            assertTrue(selectedRange.contains(month.atEndOfMonth().plusDays(1)))
            assertTrue((1..month.lengthOfMonth()).all { selectedRange.contains(month.atDay(it)) })
            assertTrue(weekDates(selected).all { selectedRange.contains(it) })
        }
    }

    @Test fun yearBoundarySelectedWeekAndCurrentWeekBothRetainTheirOwnNightBuffers() {
        val selected = LocalDate.parse("2018-12-31")
        val today = LocalDate.parse("2026-01-01")
        val ranges = scheduleReadRanges(selected, today, today)
        assertTrue(ranges.any { it.contains(LocalDate.parse("2018-12-30")) && it.contains(LocalDate.parse("2019-01-05")) })
        val currentMonday = mondayOfWeek(today)
        assertTrue(ranges.any { it.contains(currentMonday.minusDays(1)) && it.contains(currentMonday.plusDays(7)) })
        assertEquals(ranges.distinct(), ranges)
        assertFalse(ranges.any { it.contains(LocalDate.parse("2023-06-01")) })
    }
}
