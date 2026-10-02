package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import java.time.YearMonth

class RetiredVisibilityStateTest {
    @Test fun formerEmployeeRemainsInItsHistoricalMonth() {
        val retired = Employee(id = "retired", active = false, terminationDate = "2020-01-10")
        val state = MainUiState(employees = listOf(retired), attendance = listOf(
            Attendance(id = "old-scan", employeeId = retired.id)))

        assertEquals(listOf(retired.id), state.historicalEmployees(YearMonth.of(2020, 1)).map { it.id })
        assertEquals(listOf("old-scan"), state.historicalAttendanceForSummaries.map { it.id })
        assertEquals(listOf(retired.id), state.copy(attendanceDatePreset = "SINGLE",
            attendanceDateFilter = "2020-01-05").attendanceFilterEmployees.map { it.id })
        assertTrue(state.historicalEmployees(YearMonth.of(2020, 2)).isEmpty())
    }

    @Test fun formerEmployeeIsAbsentFromOperationalLists() {
        val retired = Employee(id = "retired", fullName = "Former employee", active = false,
            terminationDate = "2020-01-10")
        val active = Employee(id = "active", fullName = "Current employee")
        val state = MainUiState(
            employees = listOf(retired, active),
            attendance = listOf(
                Attendance(id = "old-scan", employeeId = retired.id),
                Attendance(id = "current-scan", employeeId = active.id)
            ),
            leaveRequests = listOf(LeaveRequest(employeeId = retired.id), LeaveRequest(employeeId = active.id))
        )
        assertEquals(listOf(active.id), state.operationalEmployees.map { it.id })
        assertEquals(listOf("current-scan"), state.visibleAttendance.map { it.id })
        assertEquals(listOf(active.id), state.visibleLeaveRequests.map { it.employeeId })
        assertTrue(state.visibleEmployees.none { it.id == retired.id })
    }
}
