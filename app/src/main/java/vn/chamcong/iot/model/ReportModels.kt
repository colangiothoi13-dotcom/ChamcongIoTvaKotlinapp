package vn.chamcong.iot.model

import java.time.LocalDate

data class ReportFilter(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val employeeId: String? = null,
    val department: String? = null
)

enum class ReportType {
    ATTENDANCE,
    WORK_SUMMARY,
    LATE_EARLY,
    LEAVE,
    OVERTIME,
    DEVICE_ACTIVITY
}

data class AttendanceReportRow(
    val date: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val checkIn: String = "",
    val checkOut: String = "",
    val status: String = "",
    val workedHours: Double = 0.0,
    val overtimeHours: Double = 0.0
)

data class DeviceActivityRow(
    val deviceId: String = "",
    val status: String = "",
    val lastHeartbeat: String = "",
    val firmwareVersion: String = "",
    val fingerprintCount: Int? = null,
    val capacity: Int? = null,
    val failedCommandCount: Int = 0
)
