package vn.chamcong.iot.domain

import org.junit.Test
import vn.chamcong.iot.model.AttendanceAdjustment
import java.time.Instant

class AttendanceAdjustmentRulesTest {
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
