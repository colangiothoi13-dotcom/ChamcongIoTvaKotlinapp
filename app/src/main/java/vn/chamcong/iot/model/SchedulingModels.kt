package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class ShiftCategory { MORNING, EVENING, SUPPLEMENTARY }

enum class WeeklyScheduleRequestStatus { PENDING, NEEDS_REVISION, APPROVED }

data class WorkShift(
    val id: String = "",
    val name: String = "",
    val category: String = ShiftCategory.MORNING.name,
    val startTime: String = "08:00",
    val endTime: String = "12:00",
    val allowEarlyMinutes: Int = 0,
    val lateGraceMinutes: Int = 0,
    val earlyLeaveAllowedMinutes: Int = 0,
    val missingCheckOutGraceMinutes: Int = 60,
    val breakStartTime: String? = null,
    val breakEndTime: String? = null,
    val countsOvertime: Boolean = false,
    val effectiveFrom: String = "",
    val effectiveTo: String? = null,
    val active: Boolean = true
)

data class WorkSchedule(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val shiftId: String = "",
    /** All main shifts registered for the date; shiftId remains the legacy first-shift value. */
    val shiftIds: List<String> = emptyList(),
    val shiftName: String = "",
    val date: String = "",
    val overtimeHours: Int = 0,
    val workedHoursOverride: Double? = null,
    val adjustmentNote: String = "",
    val assignedBy: String? = null,
    val source: String = "EMPLOYEE",
    val note: String = ""
)

data class WeeklyScheduleRequest(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val weekStart: String = "",
    val shiftsByDate: Map<String, List<String>> = emptyMap(),
    val status: WeeklyScheduleRequestStatus = WeeklyScheduleRequestStatus.PENDING,
    val reason: String = "",
    val reviewNote: String? = null,
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val reviewedAt: Timestamp? = null
)

data class WorkTimeSummary(
    /** Original scan instants, before any schedule-boundary adjustments. */
    val rawCheckInAt: java.time.Instant? = null,
    val rawCheckOutAt: java.time.Instant? = null,
    /** Instants used for paid-time calculation after schedule-window clipping. */
    val paidCheckInAt: java.time.Instant? = null,
    val paidCheckOutAt: java.time.Instant? = null,
    /** Exact whole seconds credited to regular work and overtime, before hour rounding. */
    val workedSeconds: Long = 0L,
    val overtimeSeconds: Long = 0L,
    val workedHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val lateMinutes: Int = 0,
    val earlyLeaveMinutes: Int = 0,
    val dayWorked: Boolean = false
)

data class WeeklyWorkSummary(
    val weekStart: java.time.LocalDate,
    val totalWorkedHours: Double = 0.0,
    val totalOvertimeHours: Double = 0.0,
    val workdays: Int = 0,
    val lateCount: Int = 0,
    val earlyLeaveCount: Int = 0,
    val approvedLeaveDays: Int = 0,
    val unauthorizedAbsenceDays: Int = 0,
    val dailyWorkedHours: Map<java.time.LocalDate, Double> = emptyMap()
)
