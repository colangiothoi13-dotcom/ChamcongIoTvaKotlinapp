package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate

enum class AttendanceResolutionStatus { PENDING, ACCEPTED, DUPLICATE, UNSCHEDULED, OUT_OF_ORDER }

data class AttendanceAdjustment(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val scheduleDate: String = "",
    val checkInAt: Timestamp? = null,
    val checkOutAt: Timestamp? = null,
    val workedHoursOverride: Double? = null,
    val reason: String = "",
    val actorId: String = "",
    val actorName: String = "",
    val createdAt: Timestamp = Timestamp.now()
)

data class AttendancePair(
    val scheduleDate: LocalDate,
    val checkIn: Instant? = null,
    val checkOut: Instant? = null,
    val adjustment: AttendanceAdjustment? = null
)
