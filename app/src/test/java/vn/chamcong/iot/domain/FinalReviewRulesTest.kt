package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.*
import java.time.*
import java.util.Date

class FinalReviewRulesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.of(2026, 9, 14)
    private val employee = Employee(id = "e1", fullName = "An")
    private val shift = WorkShift(id = "day", name = "Day", startTime = "08:00", endTime = "17:00", effectiveFrom = date.toString())
    private val schedule = WorkSchedule(employeeId = "e1", date = date.toString(), shiftId = "day")
    private fun row(type: String, time: String) = Attendance(
        employeeId = "e1", type = type, status = "NORMAL", scheduleDate = date.toString(),
        timestamp = Timestamp(Date.from(LocalDateTime.parse(time).atZone(zone).toInstant()))
    )
    private val pair get() = listOf(row("CHECK_IN", "2026-09-14T08:00"), row("CHECK_OUT", "2026-09-14T17:00"))

    @Test fun serverDuplicateDoesNotOverrideCompletedOrOpenPair() {
        val duplicate = row("DUPLICATE", "2026-09-14T08:01").copy(resolutionStatus = "DUPLICATE", status = "ABNORMAL")
        val rows = pair + duplicate
        assertEquals(PresenceStatus.LEFT, classifyPresence(employee, rows, emptyList(), date, zone, shift = shift).status)
        assertEquals(EmployeeAttendanceStatus.ON_TIME, employeeDaySummary("e1", date, rows, schedule, shift, false, zone).status)
        assertEquals(PresenceStatus.PRESENT, classifyPresence(employee, listOf(pair.first(), duplicate), emptyList(), date, zone,
            now = date.atTime(9, 0).atZone(zone).toInstant(), shift = shift).status)
    }

    @Test fun serverUnscheduledAndOutOfOrderRemainAbnormal() {
        for (type in listOf("UNSCHEDULED", "OUT_OF_ORDER")) {
            val rows = pair + row(type, "2026-09-14T17:01").copy(resolutionStatus = type, status = "ABNORMAL")
            assertEquals(PresenceStatus.ABNORMAL, classifyPresence(employee, rows, emptyList(), date, zone, shift = shift).status)
            assertEquals(EmployeeAttendanceStatus.ABNORMAL, employeeDaySummary("e1", date, rows, schedule, shift, false, zone).status)
        }
    }

    @Test fun acceptedMalformedStatusCannotCreatePayableHours() {
        for (invalid in listOf("", "UNKNOWN", "PENDING", " normal ")) {
            for (index in pair.indices) {
                val rows = pair.mapIndexed { i, row -> if (i == index) row.copy(status = invalid) else row }
                assertEquals(0.0, workedHoursForMonth(rows, "e1", YearMonth.from(date), zone, listOf(schedule), listOf(shift)), 0.001)
                val summary = employeeDaySummary("e1", date, rows, schedule, shift, false, zone)
                assertEquals(EmployeeAttendanceStatus.ABNORMAL, summary.status)
                assertEquals(0.0, summary.workedHours, 0.001)
                assertEquals(PresenceStatus.ABNORMAL, classifyPresence(employee, rows, emptyList(), date, zone, shift = shift).status)
                val report = attendanceReportRows(ReportFilter(startDate = date, endDate = date), listOf(employee), rows,
                    listOf(schedule), listOf(shift), emptyList(), zone).single()
                assertEquals(0.0, report.workedHours, 0.001)
                assertEquals("ABNORMAL", report.status)
                assertEquals(0.0, summarizeWeeklyWork(listOf(employee), rows, listOf(schedule), mapOf(shift.id to shift), emptyList(), date, zone).totalWorkedHours, 0.001)
            }
        }
    }

    @Test fun omittedLegacyStatusStillPays() {
        val rows = pair.map { it.copy(status = Attendance().status, resolutionStatus = Attendance().resolutionStatus) }
        assertEquals(9.0, workedHoursForMonth(rows, "e1", YearMonth.from(date), zone, listOf(schedule), listOf(shift)), 0.001)
    }

    @Test fun openPairBeforeDeadlineIsInProgressInEmployeeSummaryAndReport() {
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val rows = listOf(pair.first().copy(scheduleDate = tomorrow.toString(), timestamp = Timestamp(Date.from(tomorrow.atTime(8, 0).atZone(zone).toInstant()))))
        val planned = schedule.copy(date = tomorrow.toString())
        assertEquals("PRESENT", employeeDaySummary("e1", tomorrow, rows, planned, shift, false, zone).status.name)
        assertEquals("PRESENT", attendanceReportRows(ReportFilter(startDate = tomorrow, endDate = tomorrow), listOf(employee), rows,
            listOf(planned), listOf(shift), emptyList(), zone).single().status)
    }

    @Test fun approvedLeaveBelongsOnlyToItsEmployee() {
        val second = Employee(id = "e2", fullName = "Binh")
        val result = summarizeWeeklyWork(listOf(employee, second), emptyList(), listOf(schedule, schedule.copy(employeeId = "e2")),
            mapOf(shift.id to shift), listOf(LeaveRequest(employeeId = "e1", type = "LEAVE", status = "APPROVED", startDate = date.toString(), endDate = date.toString())), date, zone)
        assertEquals(1, result.approvedLeaveDays)
        assertEquals(1, result.unauthorizedAbsenceDays)
    }

    @Test fun employeeMonthAndReportShareDaytimeAndOvernightGraceBoundaries() {
        for ((start, end, dayOffset) in listOf(Triple("08:00", "17:00", 0L), Triple("22:00", "06:00", 1L))) {
            for (grace in listOf(0, 60)) {
                val selectedShift = shift.copy(startTime = start, endTime = end, missingCheckOutGraceMinutes = grace)
                val checkIn = date.atTime(LocalTime.parse(start)).atZone(zone).toInstant()
                val deadline = date.plusDays(dayOffset).atTime(LocalTime.parse(end)).atZone(zone).toInstant().plusSeconds(grace * 60L)
                val rows = listOf(pair.first().copy(timestamp = Timestamp(Date.from(checkIn))))
                for ((now, expected) in listOf(deadline.minusSeconds(1) to "PRESENT", deadline to "PRESENT", deadline.plusSeconds(1) to "MISSING_CHECK_OUT")) {
                    assertEquals(expected, employeeDaySummary("e1", date, rows, schedule, selectedShift, false, zone, now = now).status.name)
                    assertEquals(expected, employeeMonthSummaries("e1", date, rows, listOf(schedule), listOf(selectedShift), emptySet(), zone, now = now).first { it.date == date }.status.name)
                    assertEquals(expected, attendanceReportRows(ReportFilter(startDate = date, endDate = date), listOf(employee), rows,
                        listOf(schedule), listOf(selectedShift), emptyList(), zone, now = now).single().status)
                }
            }
        }
    }

    @Test fun noShiftOpenPairKeepsLegacyNextDayDeadline() {
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals("PRESENT", employeeDaySummary("e1", date, listOf(pair.first()), null, null, false, zone, now = midnight.minusSeconds(1)).status.name)
        assertEquals("MISSING_CHECK_OUT", employeeDaySummary("e1", date, listOf(pair.first()), null, null, false, zone, now = midnight).status.name)
    }

    @Test fun postMidnightBreakIsDeductedFromOvernightShift() {
        val night = shift.copy(startTime = "22:00", endTime = "06:00", breakStartTime = "02:00", breakEndTime = "02:30")
        val result = calculateWorkTime(date.atTime(22, 0).atZone(zone).toInstant(), date.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), night, 0, zone, date)
        assertEquals(7.5, result.workedHours, 0.001)
    }

    @Test fun sameDayAndMidnightCrossingBreaksRemainDeducted() {
        val day = shift.copy(breakStartTime = "12:00", breakEndTime = "12:30")
        assertEquals(8.5, calculateWorkTime(date.atTime(8, 0).atZone(zone).toInstant(), date.atTime(17, 0).atZone(zone).toInstant(), day, 0, zone, date).workedHours, 0.001)
        val night = shift.copy(startTime = "22:00", endTime = "06:00", breakStartTime = "23:45", breakEndTime = "00:15")
        assertEquals(7.5, calculateWorkTime(date.atTime(22, 0).atZone(zone).toInstant(), date.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), night, 0, zone, date).workedHours, 0.001)
    }
}
