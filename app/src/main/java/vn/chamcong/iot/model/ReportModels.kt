package vn.chamcong.iot.model

import java.time.LocalDate

data class ReportFilter(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val employeeId: String? = null,
    val department: String? = null
)

enum class ReportType {
    WORK_ITEMS,
    ATTENDANCE,
    WORK_SUMMARY,
    LATE_EARLY,
    LEAVE,
    OVERTIME,
    PAYROLL,
    DEVICE_ACTIVITY
}

data class WorkSummaryReportRow(
    val employeeId: String,
    val employeeCode: String = "",
    val employeeName: String,
    val department: String = "",
    val workdays: Int,
    val regularHours: Double,
    val overtimeHours: Double,
    val lateDays: Int,
    val earlyDays: Int,
    val leaveDays: Int,
    val missingDays: Int
)

data class PerformanceReportRow(
    val employeeId: String,
    val employeeCode: String,
    val employeeName: String,
    val metrics: WorkItemMetrics,
    val review: PerformanceReview? = null
)

data class AttendanceReportRow(
    val date: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val checkIn: String = "",
    val checkOut: String = "",
    val status: String = "",
    val workedHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val lateMinutes: Int = 0,
    val earlyLeaveMinutes: Int = 0,
    val approvedLeaveShiftCount: Int = 0
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
