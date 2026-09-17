package vn.chamcong.iot.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.PresenceStatus
import vn.chamcong.iot.model.ReportFilter
import java.time.LocalDate
import java.time.ZoneId
import vn.chamcong.iot.model.AttendanceAdjustment
import java.time.Instant

class AttendanceAdjustmentRulesTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNoEffectiveValue() {
        validateAttendanceAdjustment(validAdjustment())
    }

    @Test
    fun latestIsSelectedByCreationTimeWithinEmployeeAndDate() {
        val older = validAdjustment(workedHoursOverride = 4.0)
        val newer = older.copy(id = "new", createdAt = older.createdAt.plusSeconds(1))
        val otherEmployee = newer.copy(employeeId = "other", createdAt = newer.createdAt.plusSeconds(1))
        assertEquals(newer, latestAdjustment(listOf(newer, otherEmployee, older), "employee-1", LocalDate.parse("2026-09-17")))
    }

    @Test
    fun adjustmentOverlayDoesNotMutateLegacyAttendance() {
        val raw = Attendance(employeeId = "employee-1")
        val original = raw.copy()
        val correction = validAdjustment(workedHoursOverride = 8.0)
        resolveAttendancePair(listOf(raw), LocalDate.parse("2026-09-17"), null, listOf(correction), ZoneId.of("UTC"))
        assertEquals(original, raw)
        assertNull(raw.scheduleDate)
    }

    @Test
    fun hoursOnlyAdjustmentWithoutRawScansContributesToWeeklyTotal() {
        val summary = summarizeWeeklyWork(
            employees = listOf(Employee(id = "employee-1")), attendance = emptyList(),
            schedules = emptyList(), shifts = emptyMap(), approvedRequests = emptyList(),
            weekStart = LocalDate.parse("2026-09-17"), zoneId = ZoneId.of("UTC"),
            adjustments = listOf(validAdjustment(workedHoursOverride = 8.0))
        )
        assertEquals(8.0, summary.totalWorkedHours, 0.001)
    }

    @Test
    fun correctedTimesAndHoursReachPresenceReportAndEmployeeSummary() {
        val date = LocalDate.parse("2026-09-17")
        val zone = ZoneId.of("UTC")
        val employee = Employee(id = "employee-1")
        val adjustments = listOf(validAdjustment(
            checkInAt = timestamp("2026-09-17T08:00:00Z"),
            checkOutAt = timestamp("2026-09-17T16:00:00Z"), workedHoursOverride = 7.0
        ))
        assertEquals(PresenceStatus.LEFT, classifyPresence(
            employee, emptyList(), emptyList(), date, zone, adjustments = adjustments
        ).status)
        val report = attendanceReportRows(ReportFilter(date, date), listOf(employee),
            emptyList(), emptyList(), emptyList(), emptyList(), zone, adjustments).single()
        assertEquals("08:00", report.checkIn)
        assertEquals("16:00", report.checkOut)
        assertEquals(7.0, report.workedHours, 0.001)
        val day = employeeDaySummary(employee.id, date, emptyList(), null, null, false, zone, adjustments)
        assertEquals(adjustments.single().checkOutAt, day.checkOut)
        assertEquals(7.0, day.workedHours, 0.001)
        val weekly = summarizeWeeklyWork(listOf(employee), emptyList(), emptyList(), emptyMap(),
            emptyList(), date, zone, adjustments.map { it.copy(workedHoursOverride = null) })
        assertEquals(8.0, weekly.totalWorkedHours, 0.001)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankReason() {
        validateAttendanceAdjustment(validAdjustment(reason = "   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidScheduleDate() {
        validateAttendanceAdjustment(validAdjustment(scheduleDate = "2026-02-30"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCheckOutBeforeCheckIn() {
        validateAttendanceAdjustment(
            validAdjustment(
                checkInAt = timestamp("2026-09-17T09:00:00Z"),
                checkOutAt = timestamp("2026-09-17T08:59:00Z")
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWorkedHoursOutsideTheSupportedRange() {
        validateAttendanceAdjustment(validAdjustment(workedHoursOverride = 24.01))
    }

    @Test
    fun acceptsAnAdjustmentWithOneEditedValue() {
        validateAttendanceAdjustment(validAdjustment(workedHoursOverride = 8.0))
    }

    private fun validAdjustment(
        scheduleDate: String = "2026-09-17",
        checkInAt: Instant? = null,
        checkOutAt: Instant? = null,
        workedHoursOverride: Double? = null,
        reason: String = "Quên chấm công"
    ) = AttendanceAdjustment(
        id = "adjustment-1",
        employeeId = "employee-1",
        employeeName = "Nguyễn Văn A",
        scheduleDate = scheduleDate,
        checkInAt = checkInAt,
        checkOutAt = checkOutAt,
        workedHoursOverride = workedHoursOverride,
        reason = reason,
        actorId = "admin-1",
        actorName = "Admin",
        createdAt = timestamp("2026-09-17T10:00:00Z")
    )

    private fun timestamp(value: String) = Instant.parse(value)
}
