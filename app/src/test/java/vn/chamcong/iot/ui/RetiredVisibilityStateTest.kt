package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest

class RetiredVisibilityStateTest {
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
