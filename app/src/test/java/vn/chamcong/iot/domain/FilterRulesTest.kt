package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee

class FilterRulesTest {
    @Test
    fun employeeFilterMatchesNameCodeAndDepartmentWithoutCaseSensitivity() {
        val employees = listOf(
            Employee(id = "1", code = "NV0001", fullName = "Nguyễn An", department = "Kỹ thuật", active = true),
            Employee(id = "2", code = "NV0002", fullName = "Trần Bình", department = "Kinh doanh", active = true),
            Employee(id = "3", code = "NV0003", fullName = "Lê Chi", department = "Kỹ thuật", active = false)
        )

        val result = filterEmployees(employees, query = "nv0001", department = "Kỹ thuật", includeRetired = false)

        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun attendanceFilterCanSelectLateCheckIns() {
        val rows = listOf(
            Attendance(employeeId = "1", type = "CHECK_IN", status = "LATE"),
            Attendance(employeeId = "2", type = "CHECK_OUT", status = "NORMAL")
        )

        assertEquals(listOf("1"), filterAttendance(rows, status = "LATE", type = "CHECK_IN").map { it.employeeId })
    }
}
