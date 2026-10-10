package vn.chamcong.iot.ui.payroll

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.chamcong.iot.data.CalculationData
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.MainUiState
import java.time.Instant
import java.util.Date

class PayrollRecalculationPresentationTest {
    private val employee = Employee(id = "e1", code = "NV001", fullName = "An", baseSalary = 26_000)
    private val previous = Payroll(employeeId = employee.id, employeeName = employee.fullName,
        month = "2026-10", hoursWorked = 0.0, hourlyRate = 0, baseSalary = 0,
        bonus = 0, deduction = 25_000)
    private val morning = WorkShift(id = "morning", name = "Ca sáng", category = "MORNING",
        startTime = "08:00", endTime = "12:00")
    private val afternoon = WorkShift(id = "afternoon", name = "Ca chiều", category = "EVENING",
        startTime = "13:00", endTime = "17:00")

    private fun completeState(): MainUiState {
        val scans = listOf(
            "CHECK_IN" to "2026-10-09T00:53:36Z",
            "CHECK_OUT" to "2026-10-09T00:59:32Z"
        ).mapIndexed { index, (type, time) ->
            Attendance(id = "scan-$index", employeeId = employee.id, employeeName = employee.fullName,
                type = type, status = "NORMAL", resolutionStatus = "ACCEPTED", verified = true,
                shiftId = morning.id, scheduleDate = "2026-10-09",
                timestamp = Timestamp(Date.from(Instant.parse(time))))
        }
        val adjustment = AttendanceAdjustment(id = "manual-correction", employeeId = employee.id,
            employeeName = employee.fullName, scheduleDate = "2026-10-09",
            checkInAt = Instant.parse("2026-10-09T01:00:00Z"),
            checkOutAt = Instant.parse("2026-10-09T05:00:00Z"), reason = "Admin xác nhận ca sáng",
            actorId = "admin", actorName = "Admin", createdAt = Instant.parse("2026-10-09T06:00:00Z"))
        return MainUiState(employees = listOf(employee), payroll = listOf(previous), attendance = scans,
            attendanceAdjustments = listOf(adjustment), shifts = listOf(morning, afternoon),
            schedules = listOf(WorkSchedule(id = "e1_2026-10-09", employeeId = employee.id,
                date = "2026-10-09", shiftId = morning.id, shiftIds = listOf(morning.id, afternoon.id))),
            historicalCalculationData = CalculationData(),
            attendanceHistoryQueryKey = "2026-10-01|2026-10-31|")
    }

    private fun assertRejected(state: MainUiState = completeState(), snapshot: Payroll = previous,
                               hours: Double = 4.0, rate: Long = 26_000, message: String) {
        try {
            validatePayrollRecalculationSnapshot(state, snapshot, hours, rate)
            fail("Expected rejection containing '$message'")
        } catch (error: IllegalArgumentException) {
            assertTrue("Unexpected rejection: ${error.message}", error.message.orEmpty().contains(message))
        }
    }

    @Test
    fun acceptsCorrectedHoursAndCurrentRateWhileLeavingOldAmountsUntouched() {
        val state = completeState()

        validatePayrollRecalculationSnapshot(state, previous, 4.0, 26_000)

        assertEquals(previous, state.payroll.single())
        assertEquals(0.0, state.payroll.single().hoursWorked, 0.001)
        assertEquals(0L, state.payroll.single().hourlyRate)
        assertEquals(25_000L, state.payroll.single().deduction)
    }

    @Test
    fun rejectsHoursThatDifferFromTheCurrentCalculationIncludingNonFiniteValues() {
        listOf(0.0, 3.99, 4.000001, Double.NaN, Double.POSITIVE_INFINITY).forEach { hours ->
            assertRejected(hours = hours, message = "Giờ công đã thay đổi")
        }
    }

    @Test
    fun rejectsRateThatChangedAfterPreview() {
        assertRejected(rate = 0, message = "Đơn giá lương đã thay đổi")
        assertRejected(state = completeState().copy(employees = listOf(employee.copy(baseSalary = 30_000))),
            message = "Đơn giá lương đã thay đổi")
    }

    @Test
    fun checksFullPreviousPayrollContentRatherThanOnlyEmployeeAndMonth() {
        val changed = listOf(
            previous.copy(bonus = 10_000),
            previous.copy(deduction = 0),
            previous.copy(hoursWorked = 4.0),
            previous.copy(hourlyRate = 26_000),
            previous.copy(baseSalary = 104_000),
            previous.copy(employeeName = "Tên đã cập nhật")
        )
        changed.forEach { current ->
            assertRejected(state = completeState().copy(payroll = listOf(current)),
                message = "Phiếu lương đã thay đổi")
        }
        assertRejected(state = completeState().copy(payroll = emptyList()),
            message = "Phiếu lương đã thay đổi")
    }

    @Test
    fun rejectsUnloadedLoadingFailedTruncatedOrDifferentlyScopedMonthData() {
        val ready = completeState()
        val unavailable = listOf(
            ready.copy(historicalCalculationData = null),
            ready.copy(attendanceHistoryLoading = true),
            ready.copy(attendanceHistoryError = "Không tải được dữ liệu"),
            ready.copy(attendanceHistoryTruncated = true),
            ready.copy(attendanceHistoryQueryKey = "2026-09-01|2026-09-30|"),
            ready.copy(attendanceHistoryQueryKey = "2025-10-01|2025-10-31|"),
            ready.copy(attendanceHistoryQueryKey = "2026-10-01|2026-10-31|e1")
        )
        unavailable.forEach { state ->
            assertRejected(state = state, message = "Cần tải đầy đủ dữ liệu tháng")
        }
    }

    @Test
    fun rejectsMissingEmployeeAndEmployeeWhoRetiredBeforeThePayrollMonth() {
        assertRejected(state = completeState().copy(employees = emptyList()),
            message = "Nhân viên không thuộc danh sách")
        assertRejected(state = completeState().copy(employees = listOf(
            employee.copy(active = false, terminationDate = "2026-09-30"))),
            message = "Nhân viên không thuộc danh sách")
    }

    @Test
    fun employeeRetiredDuringThePayrollMonthRemainsEligibleForCorrection() {
        val state = completeState().copy(employees = listOf(
            employee.copy(active = false, terminationDate = "2026-10-20")))

        validatePayrollRecalculationSnapshot(state, previous, 4.0, 26_000)
    }

    @Test
    fun rejectsMalformedMonthWithVietnameseMessage() {
        val malformed = previous.copy(month = "10/2026")
        assertRejected(state = completeState().copy(payroll = listOf(malformed)), snapshot = malformed,
            message = "Tháng lương phải có dạng yyyy-MM")
    }
}
