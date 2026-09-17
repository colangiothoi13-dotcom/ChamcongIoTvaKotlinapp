package vn.chamcong.iot.model

enum class ShiftCategory { MORNING, EVENING, SUPPLEMENTARY }

data class WorkShift(
    val id: String = "",
    val name: String = "",
    val category: String = ShiftCategory.MORNING.name,
    val startTime: String = "08:00",
    val endTime: String = "17:00",
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
    val shiftName: String = "",
    val date: String = "",
    val overtimeHours: Int = 0,
    val workedHoursOverride: Double? = null,
    val adjustmentNote: String = "",
    val assignedBy: String? = null,
    val source: String = "EMPLOYEE",
    val note: String = ""
)

data class WorkTimeSummary(
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
