package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

class ShiftSummaryIsolationTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.parse("2026-09-14")
    private val employee = Employee(id = "e1", fullName = "An")
    private val main = WorkShift(id = "main", name = "Main", startTime = "08:00", endTime = "17:00",
        effectiveFrom = "2026-01-01")
    private val overtime = main.copy(id = SUPPLEMENTARY_SHIFT_ID, name = "Overtime", category = "SUPPLEMENTARY",
        startTime = "18:00", endTime = "22:00")
    private val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = main.id)
    private val now = date.plusDays(1).atStartOfDay(zone).toInstant()

    private fun scan(type: String, time: String, shiftId: String?) = Attendance(
        employeeId = employee.id, type = type, shiftId = shiftId, scheduleDate = date.toString(),
        timestamp = Timestamp(Date.from(date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()))
    )

    private fun summary(rows: List<Attendance>, shift: WorkShift = main) = employeeDaySummary(
        employee.id, date, rows, schedule.copy(shiftId = shift.id), shift, false, zone, now = now
    )

    @Test fun mainCheckInCannotPairWithOvertimeCheckoutAcrossDayMonthReportAndWeeklySummaries() {
        val rows = listOf(scan("CHECK_IN", "08:00", main.id),
            scan("CHECK_IN", "18:00", overtime.id), scan("CHECK_OUT", "22:00", overtime.id))
        val day = summary(rows)
        assertEquals(EmployeeAttendanceStatus.MISSING_CHECK_OUT, day.status)
        assertNull(day.checkOut)
        assertEquals(0.0, day.workedHours, 0.001)
        val month = employeeMonthSummaries(employee.id, date, rows, listOf(schedule), listOf(main),
            emptySet(), zone, now = now).single { it.date == date }
        assertEquals(day, month)
        val report = attendanceReportRows(ReportFilter(startDate = date, endDate = date), listOf(employee),
            rows, listOf(schedule), listOf(main), emptyList(), zone, now = now).single()
        assertEquals("08:00", report.checkIn)
        assertEquals("", report.checkOut)
        assertEquals("MISSING_CHECK_OUT", report.status)
        assertEquals(0.0, report.workedHours, 0.001)
        val week = summarizeWeeklyWork(listOf(employee), rows, listOf(schedule), mapOf(main.id to main),
            emptyList(), date, zone)
        assertEquals(0.0, week.totalWorkedHours, 0.001)
        assertEquals(0, week.workdays)
        assertEquals(4.0, summary(rows, overtime).workedHours, 0.001)
    }

    @Test fun overtimeCheckoutCannotBorrowMainCheckInEvenWhenMainHasACompletePair() {
        val rows = listOf(scan("CHECK_IN", "08:00", main.id), scan("CHECK_OUT", "17:00", main.id),
            scan("CHECK_OUT", "22:00", overtime.id))
        assertEquals(9.0, summary(rows).workedHours, 0.001)
        val day = summary(rows, overtime)
        assertNull(day.checkIn)
        assertNull(day.checkOut)
        assertEquals(0.0, day.workedHours, 0.001)
        assertEquals(EmployeeAttendanceStatus.MISSING_CHECK_IN, day.status)
    }

    @Test fun explicitForeignShiftInsideMainWindowIsExcludedEvenWithoutScheduleDate() {
        val rows = listOf(scan("CHECK_IN", "08:00", main.id), scan("CHECK_OUT", "17:00", "other"))
            .map { it.copy(scheduleDate = null) }
        assertNull(summary(rows).checkOut)
        assertEquals(0.0, summary(rows).workedHours, 0.001)
    }

    @Test fun pendingOvertimeDoesNotMakeCompleteMainShiftAbnormal() {
        val rows = listOf(scan("CHECK_IN", "08:00", main.id), scan("CHECK_OUT", "17:00", main.id),
            scan("CHECK_IN", "18:00", overtime.id).copy(resolutionStatus = "OVERTIME_PENDING"))
        assertEquals(EmployeeAttendanceStatus.ON_TIME, summary(rows).status)
        assertEquals(9.0, summary(rows).workedHours, 0.001)
    }

    @Test fun untaggedLegacyRowsStillPairWithinMainAndOvernightWindows() {
        val rows = listOf(scan("CHECK_IN", "08:00", null), scan("CHECK_OUT", "17:00", null),
            scan("CHECK_OUT", "22:00", null)).map { it.copy(scheduleDate = null) }
        assertEquals(9.0, summary(rows).workedHours, 0.001)
        val night = main.copy(startTime = "22:00", endTime = "06:00")
        val nightRows = listOf(scan("CHECK_IN", "22:00", null).copy(scheduleDate = null),
            scan("CHECK_OUT", "06:00", null).copy(scheduleDate = null,
                timestamp = Timestamp(Date.from(date.plusDays(1).atTime(6, 0).atZone(zone).toInstant()))))
        assertEquals(8.0, summary(nightRows, night).workedHours, 0.001)
    }

    @Test fun twoMainShiftsUseFourPunchesWithoutCreatingOneContinuousPair() {
        val morning = WorkShift(
            id = "morning", name = "Ca sáng", category = "MORNING",
            startTime = "08:00", endTime = "12:00", allowEarlyMinutes = 120,
            effectiveFrom = date.toString()
        )
        val afternoon = WorkShift(
            id = "afternoon", name = "Ca chiều", category = "EVENING",
            startTime = "13:00", endTime = "17:00", effectiveFrom = date.toString()
        )
        val schedule = WorkSchedule(
            employeeId = employee.id, date = date.toString(),
            shiftId = morning.id, shiftIds = listOf(morning.id, afternoon.id),
            shiftName = "Ca sáng + Ca chiều"
        )
        val rows = listOf(
            scan("CHECK_IN", "10:00", morning.id),
            scan("CHECK_OUT", "12:00", morning.id),
            scan("CHECK_IN", "13:00", afternoon.id),
            scan("CHECK_OUT", "17:00", afternoon.id)
        )

        val day = employeeDaySummaryForSchedule(
            employeeId = employee.id, date = date, attendance = rows,
            schedule = schedule, shifts = listOf(morning, afternoon),
            approvedLeave = false, zoneId = zone, now = now
        )

        assertEquals(6.0, day.workedHours, 0.001)
        assertEquals(0, day.lateMinutes)
        assertEquals(0, day.earlyLeaveMinutes)
        assertEquals(2, day.shiftSummaries.size)
        assertEquals(2.0, day.shiftSummaries.first { it.shiftId == morning.id }.workedHours, 0.001)
        assertEquals(4.0, day.shiftSummaries.first { it.shiftId == afternoon.id }.workedHours, 0.001)
    }
}
