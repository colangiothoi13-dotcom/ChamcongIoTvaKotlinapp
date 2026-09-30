package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

private fun attendance(
    employeeId: String,
    type: String,
    status: String,
    time: LocalDateTime = LocalDateTime.of(2026, 9, 13, 8, 0)
) = Attendance(
    employeeId = employeeId,
    type = type,
    status = status,
    timestamp = Timestamp(Date.from(time.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()))
)

class DashboardRulesTest {
    @Test
    fun summaryCountsActiveCheckedLateAndUnmarkedEmployees() {
        val weekStart = LocalDate.of(2026, 9, 7)
        val today = LocalDate.of(2026, 9, 13)
        val employees = listOf(
            Employee(id = "a", fullName = "An", active = true),
            Employee(id = "b", fullName = "Bình", active = true),
            Employee(id = "c", fullName = "Chi", active = false)
        )
        val attendance = listOf(
            attendance("a", "CHECK_IN", "LATE", today.atTime(8, 20)),
            attendance("a", "CHECK_OUT", "NORMAL", today.atTime(17, 0))
        )

        val result = summarizeDashboard(employees, attendance, weekStart)

        assertEquals(2, result.activeEmployees)
        assertEquals(1, result.checkedEmployees)
        assertEquals(1, result.lateEmployees)
        assertEquals(1, result.unmarkedEmployees)
        assertEquals(0, result.unresolvedPresenceEmployees)
    }

    @Test
    fun summaryMarksLatestCheckInWithoutCheckOutAsUnresolvedPresence() {
        val weekStart = LocalDate.of(2026, 9, 7)
        val today = LocalDate.of(2026, 9, 13)
        val result = summarizeDashboard(
            listOf(Employee(id = "a", active = true)),
            listOf(attendance("a", "CHECK_IN", "NORMAL", today.atTime(8, 0))),
            weekStart
        )

        assertEquals(1, result.unresolvedPresenceEmployees)
    }

    @Test
    fun weeklyChartCountsOnlyTheSelectedMondayThroughSunday() {
        val result = summarizeDashboard(
            employees = listOf(Employee(id = "a", active = true)),
            attendance = listOf(
                attendance("a", "CHECK_IN", "NORMAL", LocalDate.of(2026, 9, 7).atTime(8, 0)),
                attendance("a", "CHECK_OUT", "NORMAL", LocalDate.of(2026, 9, 13).atTime(17, 0)),
                attendance("a", "CHECK_IN", "NORMAL", LocalDate.of(2026, 9, 6).atTime(8, 0))
            ),
            weekStart = LocalDate.of(2026, 9, 7)
        )

        assertEquals(LocalDate.of(2026, 9, 7), result.weekStart)
        assertEquals(7, result.weeklyAttendance.size)
        assertEquals(2, result.weeklyAttendance.sumOf { it.count })
    }
}
