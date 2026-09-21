package vn.chamcong.iot.model

import java.time.Instant
import java.time.LocalDate

enum class AttendanceResolutionStatus {
    PENDING,
    ACCEPTED,
    DUPLICATE,
    UNSCHEDULED,
    OUT_OF_ORDER,
    OVERTIME_PENDING,
    OVERTIME_REJECTED
}

data class AttendanceAdjustment(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val scheduleDate: String = "",
    val checkInAt: Instant? = null,
    val checkOutAt: Instant? = null,
    val workedHoursOverride: Double? = null,
    val reason: String = "",
    val actorId: String = "",
    val actorName: String = "",
    val createdAt: Instant = Instant.now()
)

/** Append-only correction to the effective classification of one immutable attendance scan. */
data class AttendanceClassificationOverride(
    val id: String = "",
    val attendanceId: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val scheduleDate: String = "",
    val sourceTimestamp: Instant = Instant.EPOCH,
    val sourceType: String = "",
    val sourceStatus: String = "",
    val sourceResolutionStatus: String = "",
    val previousType: String = "",
    val previousStatus: String = "",
    val correctedType: String = "",
    val correctedStatus: String = "",
    val reason: String = "",
    val actorId: String = "",
    val actorName: String = "",
    val createdAt: Instant = Instant.now()
)

data class AttendancePair(
    val scheduleDate: LocalDate,
    val checkIn: Instant? = null,
    val checkOut: Instant? = null,
    val adjustment: AttendanceAdjustment? = null
)
