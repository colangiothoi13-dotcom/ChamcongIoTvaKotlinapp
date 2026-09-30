package vn.chamcong.iot.ui

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.domain.attendanceReportRows
import vn.chamcong.iot.model.*
import java.time.*
import java.util.Date

class FinalReviewStateTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.of(2026, 9, 30)
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant()
    private val shift = WorkShift(id = "night", name = "Night", startTime = "22:00", endTime = "06:00", breakStartTime = "02:00", breakEndTime = "02:30", effectiveFrom = date.toString())
    private val rows = listOf(
        Attendance(id = "in", employeeId = "e1", type = "CHECK_IN", timestamp = Timestamp(Date.from(at("2026-09-30T22:20")))),
        Attendance(id = "out", employeeId = "e1", type = "CHECK_OUT", timestamp = Timestamp(Date.from(at("2026-10-01T06:00"))))
    )
    private val state get() = MainUiState(
        employees = listOf(Employee(id = "e1", fullName = "An")), attendance = rows,
        selectedWeekStart = LocalDate.of(2026, 9, 14), shifts = listOf(shift),
        schedules = listOf(WorkSchedule(employeeId = "e1", date = date.toString(), shiftId = shift.id))
    )

    @Test fun monthPayrollAndReportUseOvernightScheduleOutsideSelectedWeek() {
        for (week in listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 12))) {
            val current = state.copy(selectedWeekStart = week)
            assertEquals(7.17, workedHoursForMonth(current.attendance, "e1", YearMonth.of(2026, 9), zone, current.schedules, current.shifts), 0.001)
            assertEquals(0.0, workedHoursForMonth(current.attendance, "e1", YearMonth.of(2026, 10), zone, current.schedules, current.shifts), 0.001)
            val report = attendanceReportRows(ReportFilter(startDate = date, endDate = date), current.employees, current.attendance,
                current.schedules, current.shifts, emptyList(), zone).single()
            assertEquals(7.17, report.workedHours, 0.001)
            assertEquals("06:00", report.checkOut)
            assertEquals(PresenceStatus.LEFT, current.copy(selectedPresenceDate = date).presenceRecords.single().status)
        }
    }

    @Test fun normalServerStatusIsLateInDashboardAndHistoryAndAdjustmentRecomputesBoth() {
        val current = state.copy(selectedWeekStart = LocalDate.of(2026, 9, 28), attendanceStatusFilter = "LATE", attendanceTypeFilter = "CHECK_IN")
        assertEquals(1, current.dashboard.lateEmployees)
        assertEquals(listOf("in"), current.visibleAttendance.map { it.id })
        val correction = AttendanceAdjustment(employeeId = "e1", employeeName = "An", scheduleDate = date.toString(),
            checkInAt = at("2026-09-30T22:00"), reason = "Clock correction", actorId = "admin", actorName = "Admin", createdAt = at("2026-10-01T09:00"))
        val corrected = current.copy(attendanceAdjustments = listOf(correction))
        assertEquals(0, corrected.dashboard.lateEmployees)
        assertTrue(corrected.visibleAttendance.isEmpty())
        assertEquals("NORMAL", rows.first().status)
    }

    @Test fun adjustmentOnlyPairUpdatesDashboardPresence() {
        val current = state.copy(attendance = emptyList(), selectedWeekStart = LocalDate.of(2026, 9, 28))
        val correction = AttendanceAdjustment(employeeId = "e1", employeeName = "An", scheduleDate = date.toString(),
            checkInAt = at("2026-09-30T22:20"), reason = "Missing scan", actorId = "admin", actorName = "Admin", createdAt = at("2026-10-01T09:00"))
        val corrected = current.copy(attendanceAdjustments = listOf(correction))
        assertEquals(1, corrected.dashboard.checkedEmployees)
        assertEquals(1, corrected.dashboard.lateEmployees)
        assertEquals(1, corrected.dashboard.unresolvedPresenceEmployees)
        assertEquals(0, corrected.copy(attendanceAdjustments = listOf(correction.copy(checkOutAt = at("2026-10-01T06:00")))).dashboard.unresolvedPresenceEmployees)
    }

    @Test fun shiftAndScheduleEmissionsRecomputeLateWithoutNewScans() {
        val current = state.copy(selectedWeekStart = LocalDate.of(2026, 9, 28), attendanceStatusFilter = "LATE")
        assertEquals(0, current.copy(schedules = emptyList()).dashboard.lateEmployees)
        assertEquals(1, current.dashboard.lateEmployees)
        val relaxed = current.copy(shifts = listOf(shift.copy(lateGraceMinutes = 20)))
        assertEquals(0, relaxed.dashboard.lateEmployees)
        assertTrue(relaxed.visibleAttendance.isEmpty())
    }

    @Test fun rejectedAndMalformedRowsCannotInflateDashboard() {
        val invalid = listOf(
            rows.first().copy(type = "DUPLICATE", resolutionStatus = "DUPLICATE", status = "ABNORMAL"),
            rows.first().copy(status = "UNKNOWN"),
            rows.first().copy(verified = false),
            rows.first().copy(type = "UNSCHEDULED", resolutionStatus = "UNSCHEDULED", status = "ABNORMAL")
        )
        val current = state.copy(attendance = invalid, selectedWeekStart = LocalDate.of(2026, 9, 28), attendanceStatusFilter = "LATE")
        assertEquals(0, current.dashboard.checkedEmployees)
        assertEquals(0, current.dashboard.lateEmployees)
        assertEquals(0, current.dashboard.weeklyAttendance.sumOf { it.count })
        assertTrue(current.visibleAttendance.isEmpty())
    }

    @Test fun overnightHistoryPreservesOrderingAndLabelsEffectiveCheckIn() {
        val ordered = state.copy(attendance = rows.reversed()).visibleAttendance
        assertEquals(listOf("out", "in"), ordered.map { it.id })
        assertEquals(listOf("NORMAL", "LATE"), ordered.map { it.status })
        assertEquals(listOf("2026-09-30", "2026-09-30"), ordered.map { it.scheduleDate })
        assertEquals(listOf(null, null), rows.map { it.scheduleDate })
    }
}
