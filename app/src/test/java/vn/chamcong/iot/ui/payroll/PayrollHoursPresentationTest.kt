package vn.chamcong.iot.ui.payroll

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.data.CalculationData
import vn.chamcong.iot.domain.SUPPLEMENTARY_SHIFT_ID
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import vn.chamcong.iot.domain.payrollHoursForMonth
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.MainUiState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date

class PayrollHoursPresentationTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.of(2026, 10, 9)
    private val month = YearMonth.from(date)
    private val now = instant(date, "23:00")
    private val employee = Employee(id = "e1", code = "NV001", fullName = "An", baseSalary = 50_000)
    private val morning = WorkShift(id = "morning", name = "Ca sáng", category = "MORNING",
        startTime = "08:00", endTime = "12:00")
    private val afternoon = WorkShift(id = "afternoon", name = "Ca chiều", category = "EVENING",
        startTime = "13:00", endTime = "17:00")

    private fun instant(day: LocalDate, time: String): Instant =
        day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun scan(type: String, time: String, shiftId: String = morning.id,
                     day: LocalDate = date, employeeId: String = employee.id) = Attendance(
        id = "$employeeId-$day-$shiftId-$type-$time", employeeId = employeeId,
        employeeName = "An", type = type, status = "NORMAL", verified = true,
        resolutionStatus = "ACCEPTED", scheduleDate = day.toString(), shiftId = shiftId,
        timestamp = Timestamp(Date.from(instant(day, time)))
    )

    private fun pair(start: String, end: String, shiftId: String = morning.id,
                     day: LocalDate = date, employeeId: String = employee.id) = listOf(
        scan("CHECK_IN", start, shiftId, day, employeeId),
        scan("CHECK_OUT", end, shiftId, day, employeeId)
    )

    private fun schedule(day: LocalDate = date, employeeId: String = employee.id,
                         bothShifts: Boolean = false) = WorkSchedule(
        id = "${employeeId}_$day", employeeId = employeeId, employeeName = "An",
        date = day.toString(), shiftId = morning.id,
        shiftIds = if (bothShifts) listOf(morning.id, afternoon.id) else listOf(morning.id)
    )

    private fun readyState(rows: List<Attendance> = emptyList(),
                           schedules: List<WorkSchedule> = emptyList()) = MainUiState(
        employees = listOf(employee), attendance = rows, schedules = schedules,
        shifts = listOf(morning, afternoon), historicalCalculationData = CalculationData(),
        attendanceHistoryQueryKey = "${month.atDay(1)}|${month.atEndOfMonth()}|"
    )

    private fun preview(state: MainUiState, employeeId: String = employee.id): PayrollHoursPreview =
        requireNotNull(payrollHoursPreviews(state, month, now)).getValue(employeeId)

    @Test
    fun totalsAndBonusesUseTheSameEffectiveMonthlyDataAsPayroll() {
        val state = readyState(
            pair("07:45", "11:43:11") + pair("13:07:19", "17:00", afternoon.id),
            listOf(schedule(bothShifts = true))
        )
        val hours = preview(state)
        val expected = payrollHoursForMonth(employee.id, month,
            state.historicalAttendanceForSummaries, state.effectiveSchedules, state.calculationShifts,
            state.calculationOvertimeRequests, state.calculationAdjustments, zone,
            state.calculationLeaveRequests)
        val bonuses = calculateMonthlyKpiBonuses(state.historicalEmployees(month), month,
            state.historicalAttendanceForSummaries, state.effectiveSchedules, state.calculationShifts,
            state.calculationOvertimeRequests, state.calculationAdjustments, zone,
            state.calculationLeaveRequests)

        assertEquals(7.6, expected, 0.001)
        assertEquals(expected, hours.totalHours, 0.001)
        assertEquals(bonuses.getValue(employee.id), hours.bonus)
        assertEquals(listOf(date), hours.days.map { it.date })
        assertEquals(0, hours.missingCheckOutDays)
    }

    @Test
    fun pairBeforeShiftRemainsVisibleWithZeroPayableHoursAndOriginalScanTimes() {
        val state = readyState(pair("07:15", "07:45"), listOf(schedule()))
        val hours = preview(state)
        val day = hours.days.single()
        val shift = day.shiftSummaries.single()

        assertEquals(0.0, hours.totalHours, 0.001)
        assertEquals(0, hours.missingCheckOutDays)
        assertEquals(instant(date, "07:15"), day.checkIn)
        assertEquals(instant(date, "07:45"), day.checkOut)
        assertEquals(day.checkIn, shift.rawCheckInAt)
        assertEquals(day.checkOut, shift.rawCheckOutAt)
        assertEquals(0.0, shift.workedHours, 0.001)
    }

    @Test
    fun incompleteCheckInWarnsOnceWhileFutureUnscannedSchedulesStayHidden() {
        val future = date.plusDays(1)
        val unscanned = date.plusDays(2)
        val state = readyState(
            listOf(scan("CHECK_IN", "08:00"), scan("CHECK_IN", "08:00", day = future)),
            listOf(schedule(), schedule(future), schedule(unscanned))
        )
        val hours = preview(state)

        assertEquals(0.0, hours.totalHours, 0.001)
        assertEquals(1, hours.missingCheckOutDays)
        assertTrue(hours.days.any { it.date == date && it.checkIn != null && it.checkOut == null })
        assertFalse(hours.days.any { it.date == unscanned })
    }

    @Test
    fun completedMorningDoesNotHideMissingAfternoonCheckout() {
        val state = readyState(
            pair("08:00", "12:00") + scan("CHECK_IN", "13:00", afternoon.id),
            listOf(schedule(bothShifts = true))
        )
        val hours = preview(state)

        assertEquals(4.0, hours.totalHours, 0.001)
        assertEquals(1, hours.missingCheckOutDays)
        assertEquals(2, hours.days.single().shiftSummaries.size)
    }

    @Test
    fun confirmedHoursOverridePaysIncompleteDayWithoutMissingCheckoutWarning() {
        val base = readyState(listOf(scan("CHECK_IN", "08:00")), listOf(schedule()))
        val adjustment = AttendanceAdjustment(id = "adjustment", employeeId = employee.id,
            employeeName = employee.fullName, scheduleDate = date.toString(), workedHoursOverride = 4.0,
            reason = "Xác nhận công khi quên chấm ra", actorId = "admin", actorName = "Admin",
            createdAt = now)
        val variants = listOf(
            base.copy(attendanceAdjustments = listOf(adjustment)),
            base.copy(schedules = listOf(schedule().copy(workedHoursOverride = 4.0)))
        )

        variants.forEach { state ->
            val hours = preview(state)
            assertEquals(4.0, hours.totalHours, 0.001)
            assertEquals(0, hours.missingCheckOutDays)
            assertEquals(date, hours.days.single().date)
        }
    }

    @Test
    fun manualInOutCorrectionReplacesPreShiftPairWithoutChangingSavedPayrollSnapshot() {
        val oldPayroll = Payroll(employeeId = employee.id, employeeName = employee.fullName,
            month = month.toString(), hourlyRate = 0, hoursWorked = 0.0, baseSalary = 0)
        val correction = AttendanceAdjustment(id = "manual-in-out", employeeId = employee.id,
            employeeName = employee.fullName, scheduleDate = date.toString(),
            checkInAt = instant(date, "08:00"), checkOutAt = instant(date, "12:00"),
            reason = "Admin xác nhận làm đủ ca sáng", actorId = "admin", actorName = "Admin",
            createdAt = now)
        val state = readyState(pair("07:53:36", "07:59:32"),
            listOf(schedule(bothShifts = true))).copy(
            employees = listOf(employee.copy(baseSalary = 26_000)),
            attendanceAdjustments = listOf(correction), payroll = listOf(oldPayroll)
        )

        val hours = preview(state)
        val day = hours.days.single()
        val morningSummary = day.shiftSummaries.single { it.shiftId == morning.id }
        val afternoonSummary = day.shiftSummaries.single { it.shiftId == afternoon.id }

        assertEquals(4.0, hours.regularHours, 0.001)
        assertEquals(0.0, hours.overtimeHours, 0.001)
        assertEquals(4.0, hours.totalHours, 0.001)
        assertEquals(0, hours.missingCheckOutDays)
        assertEquals(instant(date, "07:53:36"), morningSummary.rawCheckInAt)
        assertEquals(instant(date, "07:59:32"), morningSummary.rawCheckOutAt)
        assertEquals(correction.checkInAt, morningSummary.paidCheckInAt)
        assertEquals(correction.checkOutAt, morningSummary.paidCheckOutAt)
        assertEquals(4.0, morningSummary.workedHours, 0.001)
        assertEquals(0.0, afternoonSummary.workedHours, 0.001)
        assertEquals(oldPayroll, state.payroll.single())
        assertEquals(0.0, state.payroll.single().hoursWorked, 0.001)
        assertEquals(0L, state.payroll.single().hourlyRate)
        assertEquals(0L, state.payroll.single().baseSalary)
    }

    @Test
    fun approvedLeaveDoesNotWarnAboutCheckoutOrPayAnIncompleteMainShift() {
        val state = readyState(listOf(scan("CHECK_IN", "08:00")), listOf(schedule())).copy(
            leaveRequests = listOf(LeaveRequest(id = "leave", employeeId = employee.id,
                type = "LEAVE", status = "APPROVED", startDate = date.toString(), endDate = date.toString()))
        )
        val hours = preview(state)

        assertEquals(0.0, hours.totalHours, 0.001)
        assertEquals(0, hours.missingCheckOutDays)
    }

    @Test
    fun eightMainHoursAndFourApprovedOvertimeHoursAreShownAndPaidOnce() {
        val state = readyState(
            pair("08:00", "12:00") + pair("13:00", "17:00", afternoon.id) +
                pair("18:00", "22:00", SUPPLEMENTARY_SHIFT_ID),
            listOf(schedule(bothShifts = true))
        ).copy(overtimeRequests = listOf(OvertimeRequest(id = "overtime", employeeId = employee.id,
            employeeName = employee.fullName, workDate = date.toString(), startTime = "18:00",
            endTime = "22:00", status = "APPROVED", reason = "Tăng ca")))
        val hours = preview(state)

        assertEquals(8.0, hours.regularHours, 0.001)
        assertEquals(4.0, hours.overtimeHours, 0.001)
        assertEquals(12.0, hours.totalHours, 0.001)
        assertEquals(3, hours.days.single().shiftSummaries.size)
        assertEquals(1, hours.bonus.overtimeShiftCount)
        assertEquals(0, hours.missingCheckOutDays)
    }

    @Test
    fun unavailableOrDifferentlyScopedDataIsUnknownInsteadOfZeroHours() {
        val ready = readyState(pair("08:00", "12:00"), listOf(schedule()))
        val unavailable = listOf(
            MainUiState(),
            ready.copy(historicalCalculationData = null),
            ready.copy(attendanceHistoryQueryKey = ""),
            ready.copy(attendanceHistoryQueryKey = "2026-09-01|2026-09-30|"),
            ready.copy(attendanceHistoryQueryKey = "2025-10-01|2025-10-31|"),
            ready.copy(attendanceHistoryQueryKey = "2026-10-01|2026-10-31|${employee.id}"),
            ready.copy(attendanceHistoryLoading = true),
            ready.copy(attendanceHistoryError = "Không tải được dữ liệu tháng"),
            ready.copy(attendanceHistoryTruncated = true)
        )

        unavailable.forEachIndexed { index, state ->
            assertNull("Unavailable calculation state $index", payrollHoursPreviews(state, month, now))
        }
        assertNotNull(payrollHoursPreviews(ready, month, now))
    }

    @Test
    fun employeesWithNoScansHaveKnownZeroOnlyAfterMonthIsCompletelyLoaded() {
        val hours = preview(readyState(schedules = listOf(schedule())))

        assertEquals(0.0, hours.totalHours, 0.001)
        assertTrue(hours.days.isEmpty())
        assertEquals(0, hours.missingCheckOutDays)
    }

    @Test
    fun previewKeepsEmployeeIdentitiesAndFiltersOtherYearsAndRetiredEmployees() {
        val second = employee.copy(id = "e2", code = "NV002")
        val retiredBeforeMonth = employee.copy(id = "old", active = false, terminationDate = "2026-09-30")
        val retiredThisMonth = employee.copy(id = "retired", active = false, terminationDate = "2026-10-20")
        val lastYear = date.minusYears(1)
        val state = readyState(
            pair("08:00", "12:00") + pair("08:00", "10:00", employeeId = second.id) +
                pair("08:00", "12:00", day = lastYear) +
                pair("08:00", "12:00", employeeId = retiredBeforeMonth.id),
            listOf(schedule(), schedule(employeeId = second.id), schedule(lastYear),
                schedule(employeeId = retiredBeforeMonth.id))
        ).copy(employees = listOf(employee, second, retiredBeforeMonth, retiredThisMonth))
        val previews = requireNotNull(payrollHoursPreviews(state, month, now))

        assertEquals(setOf(employee.id, second.id, retiredThisMonth.id), previews.keys)
        assertEquals(4.0, previews.getValue(employee.id).totalHours, 0.001)
        assertEquals(2.0, previews.getValue(second.id).totalHours, 0.001)
        assertEquals(0.0, previews.getValue(retiredThisMonth.id).totalHours, 0.001)
        assertEquals(listOf(date), previews.getValue(employee.id).days.map { it.date })
    }
}
