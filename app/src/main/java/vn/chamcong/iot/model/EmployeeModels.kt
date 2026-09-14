package vn.chamcong.iot.model

import java.time.Instant
import java.time.LocalDate

enum class EmployeeAttendanceStatus {
    ON_TIME,
    LATE,
    EARLY_LEAVE,
    ABNORMAL,
    MISSING_CHECK_IN,
    MISSING_CHECK_OUT,
    LEAVE
}

data class EmployeeDaySummary(
    val date: LocalDate,
    val shiftName: String = "",
    val shiftStartTime: String = "",
    val shiftEndTime: String = "",
    val checkIn: Instant? = null,
    val checkOut: Instant? = null,
    val workedHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val lateMinutes: Int = 0,
    val earlyLeaveMinutes: Int = 0,
    val status: EmployeeAttendanceStatus = EmployeeAttendanceStatus.MISSING_CHECK_IN
)
