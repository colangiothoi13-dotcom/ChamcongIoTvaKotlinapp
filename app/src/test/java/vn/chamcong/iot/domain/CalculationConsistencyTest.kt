package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.data.attendanceRowsToCsv
import vn.chamcong.iot.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date

class CalculationConsistencyTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.parse("2026-09-14")
    private val month = YearMonth.from(date)
    private val employee = Employee(id = "e1", fullName = "An")
    private val morning = WorkShift(id = "morning", name = "Morning", startTime = "08:00", endTime = "12:00")
    private val afternoon = WorkShift(id = "afternoon", name = "Afternoon", category = "EVENING", startTime = "13:00", endTime = "17:00")
    private val shifts = listOf(morning, afternoon)
    private val now = date.plusDays(1).atStartOfDay(zone).toInstant()
    private val schedule = WorkSchedule(employeeId = employee.id, date = date.toString(), shiftId = morning.id)
    private val overtime = OvertimeRequest(id = "ot", employeeId = employee.id, workDate = date.toString(), status = "APPROVED")

    private fun scan(type: String, time: String, shiftId: String): Attendance = Attendance(
        employeeId = employee.id, type = type, shiftId = shiftId, scheduleDate = date.toString(),
        timestamp = Timestamp(Date.from(date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()))
    )

    private fun pair(shiftId: String, start: String, end: String) = listOf(
        scan("CHECK_IN", start, shiftId), scan("CHECK_OUT", end, shiftId)
    )

    private fun leave(shiftIds: List<String>? = null) = LeaveRequest(
        employeeId = employee.id, type = "LEAVE", status = "APPROVED",
        startDate = date.toString(), endDate = date.toString(),
        leaveShiftsByDate = shiftIds?.let { mapOf(date.toString() to it) }
    )

    private fun daily(rows: List<Attendance>, schedules: List<WorkSchedule>, leaves: List<LeaveRequest> = emptyList(),
                      requests: List<OvertimeRequest> = listOf(overtime), adjustments: List<AttendanceAdjustment> = emptyList()) =
        employeeMonthSummaries(employee.id, date, rows, schedules, shifts, emptySet(), zone, adjustments,
            overtimeRequests = requests, now = now, leaveRequests = leaves).single { it.date == date }

    private fun report(rows: List<Attendance>, schedules: List<WorkSchedule>, leaves: List<LeaveRequest> = emptyList(),
                       requests: List<OvertimeRequest> = listOf(overtime), adjustments: List<AttendanceAdjustment> = emptyList()) =
        attendanceReportRows(ReportFilter(date, date), listOf(employee), rows, schedules, shifts, leaves, zone,
            adjustments, now, requests).single()

    private fun pay(rows: List<Attendance>, schedules: List<WorkSchedule>, leaves: List<LeaveRequest> = emptyList(),
                    requests: List<OvertimeRequest> = listOf(overtime), adjustments: List<AttendanceAdjustment> = emptyList()) =
        payrollHoursForMonth(employee.id, month, rows, schedules, shifts, requests, adjustments, zone, leaves)

    @Test fun fourRegularHoursAndFourApprovedOvertimeHoursAgreeAcrossConsumers() {
        val rows = pair(morning.id, "08:00", "12:00") + pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        val daily = daily(rows, listOf(schedule))
        val report = report(rows, listOf(schedule))
        assertEquals(4.0, daily.workedHours, 0.001)
        assertEquals(4.0, daily.overtimeHours, 0.001)
        assertEquals(daily.workedHours, report.workedHours, 0.001)
        assertEquals(daily.overtimeHours, report.overtimeHours, 0.001)
        assertEquals(8.0, pay(rows, listOf(schedule)), 0.001)
    }

    @Test fun overtimeOnlyHasZeroRegularHoursAndIsExportableOnceEvenWithDuplicateRequests() {
        val rows = pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        val requests = listOf(overtime, overtime.copy(id = "duplicate"))
        val daily = daily(rows, emptyList(), requests = requests)
        val report = report(rows, emptyList(), requests = requests)
        assertEquals(0.0, daily.workedHours, 0.001)
        assertEquals(0.0, daily.shiftSummaries.single().workedHours, 0.001)
        assertEquals(4.0, daily.overtimeHours, 0.001)
        assertEquals("18:00", report.checkIn)
        assertEquals("22:00", report.checkOut)
        assertEquals(1, filterAttendanceReportRows(listOf(report), ReportType.OVERTIME).size)
        assertEquals(4.0, pay(rows, emptyList(), requests = requests), 0.001)
    }

    @Test fun morningLeaveDoesNotCountMorningHoursOrLatenessButAfternoonDoes() {
        val schedules = listOf(schedule.copy(shiftIds = listOf(morning.id, afternoon.id)))
        val rows = pair(morning.id, "09:00", "12:00") + pair(afternoon.id, "13:30", "17:00")
        val leaves = listOf(leave(listOf(morning.id)))
        val daily = daily(rows, schedules, leaves, emptyList())
        val report = report(rows, schedules, leaves, emptyList())
        assertEquals(3.5, daily.workedHours, 0.001)
        assertEquals(30, daily.lateMinutes)
        assertEquals(30, report.lateMinutes)
        assertEquals(1, report.approvedLeaveShiftCount)
        assertEquals(1, filterAttendanceReportRows(listOf(report), ReportType.LEAVE).size)
        assertEquals(3.5, pay(rows, schedules, leaves, emptyList()), 0.001)
        val kpi = calculateMonthlyKpiBonuses(listOf(employee), month, rows, schedules, shifts, emptyList(), emptyList(), zone, leaves)
        assertEquals(1, kpi.getValue(employee.id).lateCount)
    }

    @Test fun approvedLeaveSuppressesMainLatenessAndKpiButRetainsIndependentOvertime() {
        val rows = pair(morning.id, "09:00", "11:00") + pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        val leaves = listOf(leave())
        val daily = daily(rows, listOf(schedule), leaves)
        val report = report(rows, listOf(schedule), leaves)
        assertEquals(0.0, daily.workedHours, 0.001)
        assertEquals(0, daily.lateMinutes)
        assertEquals(0, report.earlyLeaveMinutes)
        assertEquals(4.0, report.overtimeHours, 0.001)
        assertEquals(4.0, pay(rows, listOf(schedule), leaves), 0.001)
        val kpi = calculateMonthlyKpiBonuses(listOf(employee), month, rows, listOf(schedule), shifts, listOf(overtime), emptyList(), zone, leaves)
        assertEquals(0, kpi.getValue(employee.id).lateCount)
        assertEquals(1, kpi.getValue(employee.id).overtimeShiftCount)
    }

    @Test fun lateAndEarlyTogetherRemainInSharedReportFilterAndCsv() {
        val rows = pair(morning.id, "08:30", "11:30")
        val report = report(rows, listOf(schedule), requests = emptyList())
        assertEquals("ABNORMAL", report.status)
        assertEquals(30, report.lateMinutes)
        assertEquals(30, report.earlyLeaveMinutes)
        val filtered = filterAttendanceReportRows(listOf(report), ReportType.LATE_EARLY)
        assertEquals(1, filtered.size)
        val csv = attendanceRowsToCsv(filtered)
        assertTrue(csv.contains("lateMinutes,earlyLeaveMinutes"))
        assertTrue(csv.contains(",30,30,"))
    }

    @Test fun pendingOrIncompleteOvertimeCannotBecomeRegularOrPayableHours() {
        val rows = pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        for ((events, request) in listOf(rows to overtime.copy(status = "PENDING"), rows.take(1) to overtime)) {
            assertEquals(0.0, daily(events, emptyList(), requests = listOf(request)).workedHours, 0.001)
            assertEquals(0.0, pay(events, emptyList(), requests = listOf(request)), 0.001)
            assertEquals(0.0, report(events, emptyList(), requests = listOf(request)).overtimeHours, 0.001)
        }
    }

    @Test fun latestCorrectionIncludingHoursOverrideIsSharedAndDoesNotReplaceOvertime() {
        val rows = pair(morning.id, "08:30", "12:00") + pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        val adjustment = AttendanceAdjustment(employeeId = employee.id, employeeName = employee.fullName,
            scheduleDate = date.toString(), checkInAt = date.atTime(8, 0).atZone(zone).toInstant(),
            workedHoursOverride = 3.0, reason = "Correction", actorId = "admin", actorName = "Admin",
            createdAt = Instant.parse("2026-10-01T00:00:00Z"))
        val adjustments = listOf(adjustment, adjustment.copy(workedHoursOverride = 1.0, createdAt = adjustment.createdAt.minusSeconds(1)))
        assertEquals(3.0, daily(rows, listOf(schedule), adjustments = adjustments).workedHours, 0.001)
        assertEquals(3.0, report(rows, listOf(schedule), adjustments = adjustments).workedHours, 0.001)
        assertEquals(0, report(rows, listOf(schedule), adjustments = adjustments).lateMinutes)
        assertEquals(7.0, pay(rows, listOf(schedule), adjustments = adjustments), 0.001)
    }

    @Test fun weeklySummaryDoesNotCountAnOvertimeOnlyPairAsRegularWorkOrDuplicateIt() {
        val rows = pair(SUPPLEMENTARY_SHIFT_ID, "18:00", "22:00")
        val weekly = summarizeWeeklyWork(listOf(employee), rows, emptyList(), shifts.associateBy { it.id },
            emptyList(), date, zone, overtimeRequests = listOf(overtime, overtime.copy(id = "duplicate")))
        assertEquals(0.0, weekly.totalWorkedHours, 0.001)
        assertEquals(4.0, weekly.totalOvertimeHours, 0.001)
        assertEquals(0, weekly.workdays)
    }

    @Test fun leaveSuppressesLatenessInDashboardAndScanFilterAsItDoesInKpi() {
        val rows = pair(morning.id, "09:00", "11:00").map { it.copy(status = "LATE") }
        val leaves = listOf(leave())
        val dashboard = summarizeDashboard(listOf(employee), rows, date, zone, listOf(schedule), shifts,
            requests = leaves)
        assertEquals(0, dashboard.lateEmployees)
        val lateRows = filterAttendance(rows, "LATE", null, listOf(schedule), shifts, zoneId = zone, leaveRequests = leaves)
        assertTrue(lateRows.isEmpty())
        val earlyRows = filterAttendance(rows, "EARLY_LEAVE", null, listOf(schedule), shifts, zoneId = zone, leaveRequests = leaves)
        assertTrue(earlyRows.isEmpty())
    }

    @Test fun partialLeaveDoesNotHideWorkingAfternoonLatenessInDashboardOrScanFilter() {
        val schedules = listOf(schedule.copy(shiftIds = listOf(morning.id, afternoon.id)))
        val rows = pair(morning.id, "09:00", "12:00") + pair(afternoon.id, "13:30", "17:00")
        val leaves = listOf(leave(listOf(morning.id)))
        val dashboard = summarizeDashboard(listOf(employee), rows, date, zone, schedules, shifts, requests = leaves)
        assertEquals(1, dashboard.lateEmployees)
        val lateRows = filterAttendance(rows, "LATE", null, schedules, shifts, zoneId = zone, leaveRequests = leaves)
        assertEquals(listOf(afternoon.id), lateRows.map { it.shiftId })
        val dailyDashboard = summarizeDailyDashboard(listOf(employee), rows, schedules, shifts, leaves, emptyList(), date, zone, now)
        assertEquals(listOf(employee.id), dailyDashboard.lateEmployees.map { it.id })
    }

    @Test fun standardMainShiftOvertimeMatchesReportAndPayroll() {
        val schedules = listOf(schedule.copy(shiftId = afternoon.id))
        val rows = pair(afternoon.id, "13:00", "17:30")
        val daily = daily(rows, schedules, requests = emptyList())
        val report = report(rows, schedules, requests = emptyList())
        assertEquals(4.0, daily.workedHours, 0.001)
        assertEquals(0.5, daily.overtimeHours, 0.001)
        assertEquals(daily.workedHours + daily.overtimeHours, report.workedHours + report.overtimeHours, 0.001)
        assertEquals(4.5, pay(rows, schedules, requests = emptyList()), 0.001)
        assertEquals(4.5, workedHoursForMonth(rows, employee.id, month, zone, schedules, shifts), 0.001)
    }

    @Test fun customContinuousShiftIsSplitWithoutChangingItsTotalPayableHours() {
        val custom = morning.copy(id = "custom", startTime = "08:00", endTime = "17:00",
            breakStartTime = "12:00", breakEndTime = "13:00", countsOvertime = true)
        val schedules = listOf(schedule.copy(shiftId = custom.id))
        val rows = pair(custom.id, "08:00", "18:00")
        val daily = employeeMonthSummaries(employee.id, date, rows, schedules, listOf(custom), emptySet(), zone).single { it.date == date }
        val report = attendanceReportRows(ReportFilter(date, date), listOf(employee), rows, schedules,
            listOf(custom), emptyList(), zone).single()
        assertEquals(8.0, daily.workedHours, 0.001)
        assertEquals(1.0, daily.overtimeHours, 0.001)
        assertEquals(daily.workedHours, report.workedHours, 0.001)
        assertEquals(daily.overtimeHours, report.overtimeHours, 0.001)
        assertEquals(9.0, payrollHoursForMonth(employee.id, month, rows, schedules, listOf(custom), emptyList(), emptyList(), zone), 0.001)
        assertEquals(9.0, workedHoursForMonth(rows, employee.id, month, zone, schedules, listOf(custom)), 0.001)
        // The generic primitive retains its legacy continuous-interval contract.
        assertEquals(9.0, calculateWorkTime(rows[0].timestamp.toDate().toInstant(), rows[1].timestamp.toDate().toInstant(), custom, 0, zone, date).workedHours, 0.001)
    }
}
