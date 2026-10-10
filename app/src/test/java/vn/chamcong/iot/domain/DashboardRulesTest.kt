package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
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

    @Test
    fun weeklyChartCountsOneEmployeeOnceOnOctober9DespiteRepeatedInAndOutScans() {
        val date = LocalDate.of(2026, 10, 9)
        val result = summarizeDashboard(
            employees = listOf(Employee(id = "a", active = true)),
            attendance = listOf(
                attendance("a", "CHECK_IN", "NORMAL", date.atTime(8, 0)),
                attendance("a", "CHECK_IN", "NORMAL", date.atTime(8, 1)),
                attendance("a", "CHECK_OUT", "NORMAL", date.atTime(17, 0)),
                attendance("a", "CHECK_OUT", "NORMAL", date.atTime(17, 1))
            ),
            weekStart = date
        )

        assertEquals(1, result.weeklyAttendance.single { it.date == date }.count)
        assertEquals(1, result.weeklyAttendance.sumOf { it.count })
        assertEquals(1, result.checkedEmployees)
    }

    @Test
    fun weeklyChartKeepsDifferentEmployeeIdsWithTheSameNameSeparate() {
        val date = LocalDate.of(2026, 10, 9)
        val result = summarizeDashboard(
            employees = listOf(
                Employee(id = "a", fullName = "An", active = true),
                Employee(id = "b", fullName = "An", active = true)
            ),
            attendance = listOf("a", "b").flatMap { employeeId ->
                listOf(
                    attendance(employeeId, "CHECK_IN", "NORMAL", date.atTime(8, 0)).copy(employeeName = "An"),
                    attendance(employeeId, "CHECK_OUT", "NORMAL", date.atTime(17, 0)).copy(employeeName = "An")
                )
            },
            weekStart = date
        )

        assertEquals(2, result.weeklyAttendance.single { it.date == date }.count)
        assertEquals(2, result.checkedEmployees)
    }

    @Test
    fun weeklyChartCountsTheSameEmployeeSeparatelyForEachWorkday() {
        val firstDate = LocalDate.of(2026, 10, 8)
        val secondDate = firstDate.plusDays(1)
        val result = summarizeDashboard(
            employees = listOf(Employee(id = "a", active = true)),
            attendance = listOf(firstDate, secondDate).flatMap { date ->
                listOf(
                    attendance("a", "CHECK_IN", "NORMAL", date.atTime(8, 0)),
                    attendance("a", "CHECK_OUT", "NORMAL", date.atTime(17, 0))
                )
            },
            weekStart = firstDate
        )

        assertEquals(1, result.weeklyAttendance.single { it.date == firstDate }.count)
        assertEquals(1, result.weeklyAttendance.single { it.date == secondDate }.count)
        assertEquals(2, result.weeklyAttendance.sumOf { it.count })
        assertEquals(1, result.checkedEmployees)
    }

    @Test
    fun weeklyChartExcludesRejectedUnknownBlankAndRetiredEmployeesLikeTheSummaryChips() {
        val date = LocalDate.of(2026, 10, 9)
        val result = summarizeDashboard(
            employees = listOf(
                Employee(id = "a", active = true),
                Employee(id = "rejected", active = true),
                Employee(id = "unverified", active = true),
                Employee(id = "retired", active = false),
                Employee(id = "", active = true)
            ),
            attendance = listOf(
                attendance("a", "CHECK_IN", "NORMAL", date.atTime(8, 0)),
                attendance("rejected", "CHECK_IN", "NORMAL", date.atTime(8, 0))
                    .copy(resolutionStatus = AttendanceResolutionStatus.OVERTIME_REJECTED.name),
                attendance("unverified", "CHECK_IN", "NORMAL", date.atTime(8, 0)).copy(verified = false),
                attendance("retired", "CHECK_IN", "NORMAL", date.atTime(8, 0)),
                attendance("unknown", "CHECK_IN", "NORMAL", date.atTime(8, 0)),
                attendance("", "CHECK_IN", "NORMAL", date.atTime(8, 0))
            ),
            weekStart = date
        )

        assertEquals(1, result.weeklyAttendance.single { it.date == date }.count)
        assertEquals(1, result.weeklyAttendance.sumOf { it.count })
        assertEquals(3, result.activeEmployees)
        assertEquals(1, result.checkedEmployees)
        assertEquals(2, result.unmarkedEmployees)
    }

    @Test
    fun weeklyChartKeepsAnOvernightCheckOutOnTheShiftWorkday() {
        val date = LocalDate.of(2026, 10, 9)
        val shift = WorkShift(id = "night", startTime = "22:00", endTime = "06:00")
        val result = summarizeDashboard(
            employees = listOf(Employee(id = "a", active = true)),
            attendance = listOf(
                attendance("a", "CHECK_IN", "NORMAL", date.atTime(22, 0)),
                attendance("a", "CHECK_OUT", "NORMAL", date.plusDays(1).atTime(6, 0))
            ),
            weekStart = date,
            schedules = listOf(WorkSchedule(employeeId = "a", date = date.toString(), shiftId = shift.id)),
            shifts = listOf(shift)
        )

        assertEquals(1, result.weeklyAttendance.single { it.date == date }.count)
        assertEquals(0, result.weeklyAttendance.single { it.date == date.plusDays(1) }.count)
        assertEquals(1, result.checkedEmployees)
    }

    @Test
    fun weeklyChartCountsCorrectedAttendanceWithoutRawScansLikeTheSummaryChips() {
        val date = LocalDate.of(2026, 10, 9)
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val result = summarizeDashboard(
            employees = listOf(Employee(id = "a", active = true)),
            attendance = emptyList(),
            weekStart = date,
            adjustments = listOf(AttendanceAdjustment(
                employeeId = "a",
                employeeName = "An",
                scheduleDate = date.toString(),
                checkInAt = date.atTime(8, 0).atZone(zone).toInstant(),
                checkOutAt = date.atTime(17, 0).atZone(zone).toInstant(),
                reason = "Bổ sung công thiếu",
                actorId = "admin",
                actorName = "Admin"
            ))
        )

        assertEquals(1, result.weeklyAttendance.single { it.date == date }.count)
        assertEquals(1, result.weeklyAttendance.sumOf { it.count })
        assertEquals(1, result.checkedEmployees)
    }
}
