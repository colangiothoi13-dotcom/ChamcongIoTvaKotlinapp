package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class EmployeeRulesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val employee = Employee(id = "e1", code = "NV001", fullName = "Nguyễn Văn A", department = "Kinh doanh")
    private val shift = WorkShift(
        id = "s1",
        name = "Ca sáng",
        startTime = "08:00",
        endTime = "17:00",
        breakStartTime = "12:00",
        breakEndTime = "13:00",
        effectiveFrom = "2026-01-01"
    )

    @Test
    fun onlyActiveEmployeeWithLinkedProfileCanUseEmployeeShell() {
        val profile = UserProfile(role = "EMPLOYEE", active = true, employeeId = "e1")
        assertTrue(canAccessEmployee("password", profile))
        assertTrue(canUseEmployeeShell(profile, "e1"))
        assertFalse(canUseEmployeeShell(profile, "e2"))
        assertFalse(canAccessEmployee("password", UserProfile(role = "EMPLOYEE", active = true)))
        assertFalse(canAccessEmployee("password", UserProfile(role = "ADMIN", active = true, employeeId = "e1")))
        assertFalse(canAccessEmployee("anonymous", UserProfile(role = "EMPLOYEE", active = true, employeeId = "e1")))
    }

    @Test
    fun daySummaryCalculatesHoursAndLateStatusFromOwnAttendance() {
        val date = LocalDate.of(2026, 9, 14)
        val rows = listOf(
            attendance("CHECK_IN", "2026-09-14T01:18:00Z"),
            attendance("CHECK_OUT", "2026-09-14T10:00:00Z")
        )
        val summary = employeeDaySummary(
            employeeId = employee.id,
            date = date,
            attendance = rows,
            schedule = WorkSchedule(employeeId = employee.id, shiftId = shift.id, shiftName = shift.name, date = date.toString()),
            shift = shift,
            approvedLeave = false,
            zoneId = zone
        )

        assertEquals(EmployeeAttendanceStatus.LATE, summary.status)
        assertEquals(7.7, summary.workedHours, 0.01)
        assertEquals(18, summary.lateMinutes)
        assertEquals(0, summary.earlyLeaveMinutes)
    }

    @Test
    fun monthSummaryContainsEveryDateAndMarksApprovedLeave() {
        val month = LocalDate.of(2026, 9, 1)
        val summaries = employeeMonthSummaries(
            employeeId = employee.id,
            month = month,
            attendance = emptyList(),
            schedules = listOf(WorkSchedule(employeeId = employee.id, shiftId = shift.id, shiftName = shift.name, date = "2026-09-15")),
            shifts = listOf(shift),
            approvedLeaveDates = setOf(LocalDate.of(2026, 9, 15)),
            zoneId = zone
        )

        assertEquals(30, summaries.size)
        assertEquals(EmployeeAttendanceStatus.LEAVE, summaries[14].status)
        assertEquals(LocalDate.of(2026, 9, 30), summaries.last().date)
    }

    @Test
    fun employeeRequestDraftIsPendingAndHasNoReviewer() {
        val request = employeeRequestDraft(
            employee = employee,
            type = RequestType.ATTENDANCE_ADJUSTMENT,
            startDate = "2026-09-14",
            endDate = "2026-09-14",
            reason = "Quên chấm công vào"
        )

        assertEquals(employee.id, request.employeeId)
        assertEquals(RequestType.ATTENDANCE_ADJUSTMENT.name, request.type)
        assertEquals("PENDING", request.status)
        assertNull(request.reviewerId)
        assertNull(request.reviewerName)
    }

    private fun attendance(type: String, instant: String) = Attendance(
        employeeId = employee.id,
        type = type,
        timestamp = Timestamp(Date.from(Instant.parse(instant)))
    )
}
