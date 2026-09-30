package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class ReportRulesTest {
    @Test
    fun legacyOvernightCheckoutDoesNotCreateAnExtraCalendarDayReport() {
        val date = LocalDate.parse("2026-09-30")
        val shift = WorkShift(id = "s", name = "Night", startTime = "22:00", endTime = "06:00", effectiveFrom = "2026-01-01")
        val schedules = listOf(WorkSchedule(employeeId = "e1", shiftId = "s", date = date.toString()))
        val events = listOf(attendance("e1", "CHECK_IN", "2026-09-30T15:00:00Z"), attendance("e1", "CHECK_OUT", "2026-09-30T23:00:00Z"))
        val rows = attendanceReportRows(ReportFilter(date, date.plusDays(1)), listOf(Employee(id = "e1")), events, schedules, listOf(shift), emptyList(), zone)
        assertEquals(listOf("2026-09-30"), rows.map { it.date })
        assertEquals(8.0, rows.single().workedHours, 0.001)
        assertEquals(8.0, vn.chamcong.iot.model.workedHoursForMonth(events, "e1", java.time.YearMonth.of(2026, 9), zone, schedules, listOf(shift)), 0.001)
    }

    @Test
    fun approvedLeaveIsScopedToItsEmployeeAndWinsOverAbnormalAttendance() {
        val date = LocalDate.parse("2026-09-30")
        val leave = vn.chamcong.iot.model.LeaveRequest(employeeId = "e1", type = "LEAVE", status = "APPROVED", startDate = date.toString(), endDate = date.toString())
        val rows = attendanceReportRows(ReportFilter(date, date), listOf(Employee(id = "e1"), Employee(id = "e2")),
            listOf(attendance("e1", "SCAN", "2026-09-30T01:00:00Z")),
            listOf(WorkSchedule(employeeId = "e2", date = date.toString())), emptyList(), listOf(leave), zone)
        assertEquals("LEAVE", rows.first { it.employeeId == "e1" }.status)
        assertEquals("MISSING_CHECK_IN", rows.first { it.employeeId == "e2" }.status)
    }

    @Test
    fun overnightReportHasOnlyStartDateAndAcceptedHours() {
        val date = LocalDate.parse("2026-09-30")
        val events = listOf(
            attendance("e1", "CHECK_IN", "2026-09-30T15:00:00Z"),
            attendance("e1", "CHECK_OUT", "2026-09-30T23:00:00Z"),
            attendance("e1", "CHECK_OUT", "2026-10-01T00:00:00Z").copy(resolutionStatus = "DUPLICATE")
        ).map { it.copy(scheduleDate = date.toString()) }
        val rows = attendanceReportRows(ReportFilter(date, date.plusDays(1)), listOf(Employee(id = "e1")), events, emptyList(), emptyList(), emptyList(), zone)
        assertEquals(listOf("2026-09-30"), rows.map { it.date })
        assertEquals(8.0, rows.single().workedHours, 0.001)
        assertEquals("06:00", rows.single().checkOut)
    }

    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReportRangeWhenEndPrecedesStart() {
        validateReportFilter(ReportFilter(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun filtersByEmployeeAndDepartmentTogether() {
        val employees = listOf(
            Employee(id = "e1", fullName = "An", department = "Kinh doanh"),
            Employee(id = "e2", fullName = "Bình", department = "Kinh doanh"),
            Employee(id = "e3", fullName = "Chi", department = "Kỹ thuật")
        )

        val result = filterEmployees(
            employees,
            ReportFilter(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), employeeId = "e2", department = "Kinh doanh")
        )

        assertEquals(listOf("e2"), result.map { it.id })
    }

    @Test
    fun buildsDailyAttendanceRowWithWorkedAndOvertimeHours() {
        val shift = WorkShift(
            name = "Ca sáng",
            category = ShiftCategory.MORNING.name,
            startTime = "08:00",
            endTime = "17:00",
            breakStartTime = "12:00",
            breakEndTime = "13:00",
            countsOvertime = true,
            effectiveFrom = "2026-09-14"
        )
        val rows = attendanceReportRows(
            filter = ReportFilter(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)),
            employees = listOf(Employee(id = "e1", fullName = "An", department = "Kinh doanh")),
            attendance = listOf(
                attendance("e1", AttendanceType.CHECK_IN.name, "2026-09-14T01:00:00Z"),
                attendance("e1", AttendanceType.CHECK_OUT.name, "2026-09-14T11:00:00Z")
            ),
            schedules = listOf(WorkSchedule(employeeId = "e1", shiftId = "s1", date = "2026-09-14", overtimeHours = 1)),
            shifts = listOf(shift.copy(id = "s1")),
            approvedRequests = emptyList(),
            zoneId = zone
        )

        assertEquals(1, rows.size)
        assertEquals("e1", rows.single().employeeId)
        assertEquals(9.0, rows.single().workedHours, 0.01)
        assertEquals(1.0, rows.single().overtimeHours, 0.01)
        assertTrue(rows.single().checkIn.isNotBlank())
        assertTrue(rows.single().checkOut.isNotBlank())
    }

    private fun attendance(employeeId: String, type: String, instant: String) = Attendance(
        employeeId = employeeId,
        type = type,
        timestamp = Timestamp(Date.from(Instant.parse(instant)))
    )
}
