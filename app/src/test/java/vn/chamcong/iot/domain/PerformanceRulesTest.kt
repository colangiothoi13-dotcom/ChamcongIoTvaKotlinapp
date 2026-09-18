package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.createPayroll
import vn.chamcong.iot.model.workedHoursForMonth
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PerformanceRulesTest {
    private val month = YearMonth.of(2026, 9)
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val shift = WorkShift(id = "main", name = "Main", startTime = "08:00", endTime = "16:00", effectiveFrom = "2026-01-01")

    @Test
    fun monthlyPerformanceRanksEligibleEmployeesAndFeedsTheSamePayrollBonus() {
        val employees = (1..5).map { Employee(id = "e$it", code = "NV000$it", baseSalary = 50_000) }
        val requests = employees.flatMap { employee ->
            (10 until (10 + if (employee.id == "e5") 3 else 1)).map { request(employee.id, it) }
        }
        val rows = requests.flatMap { pair(it) } + mainPair("e5", "08:30")
        val schedules = listOf(WorkSchedule(employeeId = "e5", date = "2026-09-10", shiftId = "main"))
        val values = calculateMonthlyKpiBonuses(employees.reversed(), month, rows, schedules, listOf(shift), requests, emptyList(), zone)

        assertEquals(listOf(1, 2, 3, null, null), employees.map { values.getValue(it.id).top3Rank })
        assertEquals(50_000L, values.getValue("e4").totalBonus)
        val late = values.getValue("e5")
        assertEquals(3, late.overtimeShiftCount)
        assertEquals(9.0, late.overtimeHours, 0.001)
        assertEquals(1, late.lateCount)
        assertEquals(0L, late.top3Bonus)
        assertEquals(150_000L, late.overtimeBonus)
        assertEquals(100_000L, late.latePenalty)
        assertEquals(50_000L, late.totalBonus)
        val payroll = createPayroll(employees.last(), month.toString(), workedHoursForMonth(
            rows, "e5", month, zone, schedules, listOf(shift), emptyList(), requests
        ), late.totalBonus, 20_000)
        assertEquals(16.5, payroll.hoursWorked, 0.001)
        assertEquals(50_000L, payroll.bonus)
        assertEquals(20_000L, payroll.deduction)
        assertEquals(855_000L, payroll.netSalary)
    }

    @Test
    fun previewRecalculatesAfterApprovalAttendanceAndAdjustmentChanges() {
        val employee = Employee(id = "e1", code = "NV0001")
        val request = request("e1", 10)
        val schedule = WorkSchedule(employeeId = "e1", date = request.workDate, shiftId = "main")
        val rows = mainPair("e1", "08:30") + pair(request)
        fun preview(events: List<Attendance>, status: String, adjustments: List<AttendanceAdjustment> = emptyList()) =
            calculateMonthlyKpiBonuses(listOf(employee), month, events, listOf(schedule), listOf(shift),
                listOf(request.copy(status = status)), adjustments, zone).getValue(employee.id)

        assertEquals(0, preview(rows, "PENDING").overtimeShiftCount)
        assertEquals(1, preview(rows, "APPROVED").overtimeShiftCount)
        assertEquals(0L, preview(rows, "APPROVED").totalBonus)
        assertEquals(0, preview(rows.dropLast(1), "APPROVED").overtimeShiftCount)
        val adjusted = preview(rows, "APPROVED", listOf(AttendanceAdjustment(
            employeeId = "e1", employeeName = "Employee 1", scheduleDate = request.workDate,
            checkInAt = Instant.parse("2026-09-10T01:00:00Z"),
            reason = "Correct check-in", actorId = "admin", actorName = "Admin"
        )))
        assertEquals(0, adjusted.lateCount)
        assertEquals(1, adjusted.top3Rank)
        assertEquals(550_000L, adjusted.totalBonus)
    }

    @Test
    fun monthSelectionExcludesOtherMonthsAndOvertimeDoesNotCountAsMainShiftLateness() {
        val employee = Employee(id = "e1")
        val request = request("e1", 10)
        val rows = pair(request)
        val september = calculateMonthlyKpiBonuses(listOf(employee), month, rows, emptyList(), emptyList(), listOf(request), emptyList(), zone).getValue("e1")
        val october = calculateMonthlyKpiBonuses(listOf(employee), month.plusMonths(1), rows, emptyList(), emptyList(), listOf(request), emptyList(), zone).getValue("e1")
        assertEquals(0, september.lateCount)
        assertEquals(1, september.top3Rank)
        assertEquals(550_000L, september.totalBonus)
        assertEquals(0, october.overtimeShiftCount)
        assertEquals(0.0, october.overtimeHours, 0.001)
        assertEquals(0L, october.overtimeBonus)
        assertEquals(500_000L, october.totalBonus)
        assertEquals(emptyMap<String, KpiBonusBreakdown>(), calculateMonthlyKpiBonuses(
            emptyList(), month, rows, emptyList(), emptyList(), listOf(request), emptyList(), zone))
    }

    private fun request(employeeId: String, day: Int) = OvertimeRequest(
        employeeId = employeeId, workDate = "2026-09-$day", status = "APPROVED"
    )

    private fun pair(request: OvertimeRequest) = listOf(
        scan(request.employeeId, request.workDate, "CHECK_IN", "17:30", SUPPLEMENTARY_SHIFT_ID),
        scan(request.employeeId, request.workDate, "CHECK_OUT", "20:30", SUPPLEMENTARY_SHIFT_ID)
    )

    private fun mainPair(employeeId: String, checkIn: String) = listOf(
        scan(employeeId, "2026-09-10", "CHECK_IN", checkIn, "main"),
        scan(employeeId, "2026-09-10", "CHECK_OUT", "16:00", "main")
    )

    private fun scan(employeeId: String, date: String, type: String, time: String, shiftId: String) = Attendance(
        employeeId = employeeId, type = type, scheduleDate = date, shiftId = shiftId,
        timestamp = Timestamp(Date.from(ZonedDateTime.parse("${date}T$time:00+07:00").toInstant()))
    )
}
