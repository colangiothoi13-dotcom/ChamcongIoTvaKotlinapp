package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.domain.SUPPLEMENTARY_SHIFT_ID
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PayrollRulesTest {
    private val kpiMonth = YearMonth.of(2026, 9)
    private val kpiZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val kpiEmployee = Employee(id = "e1", code = "NV0001", baseSalary = 50_000)
    private val mainShift = WorkShift(id = "main", startTime = "08:00", endTime = "16:00", effectiveFrom = "2026-01-01")
    private val mainSchedule = WorkSchedule(employeeId = "e1", date = "2026-09-10", shiftId = "main")

    @Test
    fun approvedOvertimeAddsFixedHoursAndAutomaticBonusToPayrollSnapshot() {
        val rows = regularPair() + overtimePair()
        val requests = listOf(overtimeRequest())
        val breakdown = payrollBreakdown(rows, requests)
        val payroll = createPayroll(kpiEmployee, kpiMonth.toString(), payrollHours(rows, requests), breakdown.totalBonus, 25_000)

        assertEquals(11.0, payroll.hoursWorked, 0.001)
        assertEquals(550_000L, payroll.baseSalary)
        assertEquals(3.0, breakdown.overtimeHours, 0.001)
        assertEquals(50_000L, breakdown.overtimeBonus)
        assertEquals(500_000L, breakdown.top3Bonus)
        assertEquals(550_000L, payroll.bonus)
        assertEquals(25_000L, payroll.deduction)
        assertEquals(1_075_000L, payroll.netSalary)

        // A later request change affects the next preview, never the saved snapshot.
        val refreshed = payrollBreakdown(rows, listOf(overtimeRequest("REJECTED")))
        assertEquals(0L, refreshed.overtimeBonus)
        assertEquals(550_000L, payroll.bonus)
        assertEquals(11.0, payroll.hoursWorked, 0.001)
    }

    @Test
    fun nonPayableOvertimeDoesNotLeakIntoPayrollHoursOrShiftBonus() {
        val complete = overtimePair()
        val cases = listOf(
            overtimeRequest("PENDING") to complete,
            overtimeRequest("REJECTED") to complete,
            overtimeRequest() to complete.take(1),
            overtimeRequest().copy(startTime = "17:00") to complete,
            overtimeRequest() to complete.map { it.copy(verified = false) },
            overtimeRequest() to complete.map { it.copy(resolutionStatus = "OVERTIME_PENDING") },
            overtimeRequest() to complete.map { it.copy(resolutionStatus = "OVERTIME_REJECTED") },
            overtimeRequest() to complete.map { it.copy(resolutionStatus = "DUPLICATE") }
        )
        for ((request, overtime) in cases) {
            val rows = regularPair() + overtime
            val breakdown = payrollBreakdown(rows, listOf(request))
            assertEquals(8.0, payrollHours(rows, listOf(request)), 0.001)
            assertEquals(0, breakdown.overtimeShiftCount)
            assertEquals(0.0, breakdown.overtimeHours, 0.001)
            assertEquals(0L, breakdown.overtimeBonus)
            // Existing domain rules can award Top 3 even without completed overtime.
            assertEquals(500_000L, breakdown.totalBonus)
        }
    }

    @Test
    fun latePenaltyFloorsBonusWithoutIncreasingManualDeduction() {
        val rows = regularPair().map {
            if (it.type == "CHECK_IN") attendance("e1", "CHECK_IN", "2026-09-10T08:30:00+07:00")
                .copy(shiftId = "main", scheduleDate = "2026-09-10") else it
        } + overtimePair()
        val requests = listOf(overtimeRequest())
        val breakdown = payrollBreakdown(rows, requests)
        val payroll = createPayroll(kpiEmployee, kpiMonth.toString(), payrollHours(rows, requests), breakdown.totalBonus, 25_000)

        assertEquals(1, breakdown.lateCount)
        assertEquals(null, breakdown.top3Rank)
        assertEquals(100_000L, breakdown.latePenalty)
        assertEquals(50_000L, breakdown.overtimeBonus)
        assertEquals(0L, payroll.bonus)
        assertEquals(25_000L, payroll.deduction)
        assertEquals(10.5, payroll.hoursWorked, 0.001)
        assertEquals(500_000L, payroll.netSalary)
    }

    @Test
    fun overtimeAwareOverloadKeepsLegacyAndAdjustedRegularHours() {
        val rows = regularPair()
        assertEquals(8.0, workedHoursForMonth(rows, "e1", kpiMonth, kpiZone), 0.001)
        assertEquals(8.0, payrollHours(rows, emptyList()), 0.001)
        val adjustment = AttendanceAdjustment(
            employeeId = "e1", scheduleDate = "2026-09-10", workedHoursOverride = 6.5,
            reason = "Correct regular hours", actorId = "admin", actorName = "Admin"
        )
        assertEquals(6.5, workedHoursForMonth(rows, "e1", kpiMonth, kpiZone,
            listOf(mainSchedule), listOf(mainShift), listOf(adjustment)), 0.001)
        assertEquals(6.5, payrollHours(rows, emptyList(), listOf(adjustment)), 0.001)
        assertEquals(9.5, payrollHours(rows + overtimePair(), listOf(overtimeRequest()), listOf(adjustment)), 0.001)
    }

    private fun payrollHours(rows: List<Attendance>, requests: List<OvertimeRequest>, adjustments: List<AttendanceAdjustment> = emptyList()) =
        workedHoursForMonth(rows, "e1", kpiMonth, kpiZone,
            schedules = listOf(mainSchedule), shifts = listOf(mainShift), adjustments = adjustments,
            overtimeRequests = requests)

    private fun payrollBreakdown(rows: List<Attendance>, requests: List<OvertimeRequest>) =
        calculateMonthlyKpiBonuses(listOf(kpiEmployee), kpiMonth, rows, listOf(mainSchedule),
            listOf(mainShift), requests, emptyList(), kpiZone).getValue("e1")

    private fun overtimeRequest(status: String = "APPROVED") = OvertimeRequest(
        employeeId = "e1", workDate = "2026-09-10", status = status,
        rejectionReason = if (status == "REJECTED") "Not needed" else null
    )

    private fun regularPair() = listOf(
        attendance("e1", "CHECK_IN", "2026-09-10T08:00:00+07:00"),
        attendance("e1", "CHECK_OUT", "2026-09-10T16:00:00+07:00")
    ).map { it.copy(shiftId = "main", scheduleDate = "2026-09-10") }

    private fun overtimePair() = listOf(
        attendance("e1", "CHECK_IN", "2026-09-10T17:30:00+07:00"),
        attendance("e1", "CHECK_OUT", "2026-09-10T20:30:00+07:00")
    ).map { it.copy(shiftId = SUPPLEMENTARY_SHIFT_ID, scheduleDate = "2026-09-10") }

    @Test
    fun acceptedLookingUnverifiedScansCannotProduceWorkAcrossConsumers() {
        val date = java.time.LocalDate.parse("2026-09-10")
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        for ((verifiedIn, verifiedOut) in listOf(false to true, true to false, false to false)) {
            for (scheduleDate in listOf(null, date.toString())) {
                val rows = listOf(
                    attendance("e1", "CHECK_IN", "2026-09-10T08:00:00+07:00").copy(verified = verifiedIn, scheduleDate = scheduleDate),
                    attendance("e1", "CHECK_OUT", "2026-09-10T17:00:00+07:00").copy(verified = verifiedOut, scheduleDate = scheduleDate)
                )
                val day = vn.chamcong.iot.domain.employeeDaySummary("e1", date, rows, null, null, false, zone)
                val report = vn.chamcong.iot.domain.attendanceReportRows(ReportFilter(date, date), listOf(Employee(id = "e1")), rows, emptyList(), emptyList(), emptyList(), zone).single()
                assertEquals(0.0, day.workedHours, 0.001)
                assertEquals(0.0, report.workedHours, 0.001)
                assertEquals(0.0, workedHoursForMonth(rows, "e1", YearMonth.of(2026, 9), zone), 0.001)
                assertEquals(EmployeeAttendanceStatus.ABNORMAL, day.status)
                assertEquals("ABNORMAL", report.status)
                if (!verifiedIn) assertEquals(null, day.checkIn)
                if (!verifiedOut) assertEquals(null, day.checkOut)
            }
        }
    }

    @Test
    fun adjacentLegacyWindowsHaveOneAssignmentRegardlessOfInputOrder() {
        val date = java.time.LocalDate.parse("2026-09-30")
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val shifts = listOf(
            WorkShift(id = "night", name = "Night", startTime = "22:00", endTime = "06:00", effectiveFrom = "2026-01-01"),
            WorkShift(id = "day", name = "Day", startTime = "06:00", endTime = "14:00", effectiveFrom = "2026-01-01")
        )
        val schedules = listOf(WorkSchedule(employeeId = "e1", date = date.toString(), shiftId = "night"),
            WorkSchedule(employeeId = "e1", date = date.plusDays(1).toString(), shiftId = "day"))
        // Both scans fit both inclusive windows. They must not form the same pair twice.
        val overlap = listOf(attendance("e1", "CHECK_IN", "2026-10-01T06:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T06:30:00+07:00"))
        val complete = listOf(attendance("e1", "CHECK_IN", "2026-09-30T22:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T06:00:00+07:00"),
            attendance("e1", "CHECK_IN", "2026-10-01T06:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T14:00:00+07:00"))
        for ((events, expectedHours) in listOf(overlap to 0.0, complete to 8.0)) {
            val original = events.map { it.copy() }
            for (orderedSchedules in listOf(schedules, schedules.reversed())) {
                for (orderedEvents in listOf(events, events.reversed())) {
                    val reports = vn.chamcong.iot.domain.attendanceReportRows(ReportFilter(date, date.plusDays(1)), listOf(Employee(id = "e1")), orderedEvents, orderedSchedules, shifts, emptyList(), zone)
                    assertEquals(listOf(expectedHours, expectedHours), reports.map { it.workedHours })
                    for (day in listOf(date, date.plusDays(1))) {
                        val summaries = vn.chamcong.iot.domain.employeeMonthSummaries("e1", day, orderedEvents, orderedSchedules, shifts, emptySet(), zone)
                        assertEquals(expectedHours, summaries.first { it.date == day }.workedHours, 0.001)
                        assertEquals(expectedHours, workedHoursForMonth(orderedEvents, "e1", YearMonth.from(day), zone, orderedSchedules, shifts), 0.001)
                    }
                }
            }
            assertEquals(original, events)
        }
    }

    @Test
    fun latestAdjustmentAgreesAcrossPayrollReportAndEmployeeSummary() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val date = java.time.LocalDate.parse("2026-09-30")
        val employee = Employee(id = "e1", fullName = "An")
        val shift = WorkShift(id = "s", name = "Night", startTime = "22:00", endTime = "06:00", effectiveFrom = "2026-01-01")
        val schedule = WorkSchedule(employeeId = "e1", date = date.toString(), shiftId = "s")
        val rows = listOf(attendance("e1", "CHECK_IN", "2026-09-30T22:00:00+07:00").copy(scheduleDate = date.toString()))
        val snapshot = rows.map { it.copy() }
        val older = AttendanceAdjustment(employeeId = "e1", employeeName = "An", scheduleDate = date.toString(),
            checkOutAt = java.time.Instant.parse("2026-09-30T22:00:00Z"), reason = "Correction", actorId = "admin", actorName = "Admin",
            createdAt = java.time.Instant.parse("2026-10-01T01:00:00Z"))
        val latest = older.copy(checkOutAt = java.time.Instant.parse("2026-09-30T23:00:00Z"), createdAt = older.createdAt.plusSeconds(1))
        for ((correction, expected) in listOf(latest to 8.0, latest.copy(workedHoursOverride = 6.5) to 6.5)) {
            val adjustments = listOf(correction, older, correction.copy(employeeId = "other", workedHoursOverride = 1.0, createdAt = correction.createdAt.plusSeconds(1)))
            val report = vn.chamcong.iot.domain.attendanceReportRows(ReportFilter(date, date), listOf(employee), rows, listOf(schedule), listOf(shift), emptyList(), zone, adjustments).single()
            val summary = vn.chamcong.iot.domain.employeeMonthSummaries("e1", date, rows, listOf(schedule), listOf(shift), emptySet(), zone, adjustments).last()
            assertEquals(expected, report.workedHours, 0.001)
            assertEquals(expected, summary.workedHours, 0.001)
            assertEquals(expected, workedHoursForMonth(rows, "e1", YearMonth.of(2026, 9), zone, listOf(schedule), listOf(shift), adjustments), 0.001)
            assertEquals("06:00", report.checkOut)
            assertEquals(PresenceStatus.LEFT, vn.chamcong.iot.domain.classifyPresenceForEmployees(listOf(employee), rows, emptyList(), date, zone,
                adjustments = adjustments, schedules = listOf(schedule), shifts = listOf(shift)).single().status)
        }
        assertEquals(snapshot, rows)
        assertEquals(5.0, workedHoursForMonth(emptyList(), "e1", YearMonth.of(2026, 9), zone,
            adjustments = listOf(latest.copy(checkOutAt = null, workedHoursOverride = 5.0))), 0.001)
    }

    @Test
    fun rejectedScansNeverCreatePayableHours() {
        for (status in listOf("DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER", "PENDING")) {
            val rows = listOf(
                attendance("e1", "CHECK_IN", "2026-09-10T08:00:00+07:00"),
                attendance("e1", "CHECK_OUT", "2026-09-10T17:00:00+07:00").copy(resolutionStatus = status)
            )
            assertEquals(status, 0.0, workedHoursForMonth(rows, "e1", YearMonth.of(2026, 9), ZoneId.of("Asia/Ho_Chi_Minh")), 0.001)
            val date = java.time.LocalDate.parse("2026-09-10")
            val zone = ZoneId.of("Asia/Ho_Chi_Minh")
            val summary = vn.chamcong.iot.domain.employeeDaySummary("e1", date, rows, null, null, false, zone)
            val report = vn.chamcong.iot.domain.attendanceReportRows(ReportFilter(date, date), listOf(Employee(id = "e1")), rows, emptyList(), emptyList(), emptyList(), zone).single()
            assertEquals(0.0, summary.workedHours, 0.001)
            assertEquals(0.0, report.workedHours, 0.001)
            assertEquals(if (status == "DUPLICATE") "MISSING_CHECK_OUT" else "ABNORMAL", report.status)
        }
    }

    @Test
    fun overnightMonthEndPairBelongsToStartMonth() {
        val rows = listOf(
            attendance("e1", "CHECK_IN", "2026-09-30T22:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T06:00:00+07:00")
        ).map { it.copy(scheduleDate = "2026-09-30") }
        assertEquals(8.0, workedHoursForMonth(rows, "e1", YearMonth.of(2026, 9), ZoneId.of("Asia/Ho_Chi_Minh")), 0.001)
        assertEquals(0.0, workedHoursForMonth(rows, "e1", YearMonth.of(2026, 10), ZoneId.of("Asia/Ho_Chi_Minh")), 0.001)
    }

    @Test
    fun calculatesBasePayFromHourlyRateAndWorkedHours() {
        val payroll = createPayroll(
            Employee(id = "e1", code = "NV0001", fullName = "An", baseSalary = 50_000),
            month = "2026-09",
            hoursWorked = 8.5,
            bonus = 100_000,
            deduction = 25_000
        )

        assertEquals(425_000L, payroll.baseSalary)
        assertEquals(50_000L, payroll.hourlyRate)
        assertEquals(8.5, payroll.hoursWorked, 0.001)
        assertEquals(500_000L, payroll.netSalary)
    }

    @Test
    fun calculatesWorkedHoursFromCheckInAndCheckOutPairsInMonth() {
        val attendance = listOf(
            attendance("e1", "CHECK_IN", "2026-09-10T08:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-09-10T17:30:00+07:00"),
            attendance("e1", "CHECK_IN", "2026-10-01T08:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T12:00:00+07:00")
        )

        assertEquals(
            9.5,
            workedHoursForMonth(attendance, "e1", YearMonth.of(2026, 9), ZoneId.of("Asia/Ho_Chi_Minh")),
            0.001
        )
    }

    @Test
    fun hidesRetiredEmployeeAfterPayrollExistsForSelectedMonth() {
        val employees = listOf(
            Employee(id = "active", fullName = "Đang làm"),
            Employee(id = "retired", fullName = "Đã nghỉ", active = false)
        )
        val payroll = listOf(Payroll(employeeId = "retired", month = "2026-09"))

        val candidates = payrollCandidates(employees, payroll, "2026-09")

        assertTrue(candidates.any { it.id == "active" })
        assertTrue(candidates.none { it.id == "retired" })
    }

    @Test
    fun keepsRetiredEmployeeAvailableUntilTheirMonthIsSettled() {
        val retired = Employee(id = "retired", fullName = "Đã nghỉ", active = false)

        assertTrue(payrollCandidates(listOf(retired), emptyList(), "2026-09").contains(retired))
    }

    private fun attendance(employeeId: String, type: String, localTime: String): Attendance =
        Attendance(
            employeeId = employeeId,
            type = type,
            timestamp = Timestamp(Date.from(ZonedDateTime.parse(localTime).toInstant()))
        )
}
