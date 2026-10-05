package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeShiftSummary
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

private const val OVERTIME_SHIFT_BONUS = 50_000L
private const val LATE_PENALTY = 100_000L
private const val TOP_THREE_BONUS = 500_000L

data class KpiBonusBreakdown(
    val overtimeShiftCount: Int = 0,
    val overtimeHours: Double = 0.0,
    val lateCount: Int = 0,
    val top3Rank: Int? = null,
    val top3Bonus: Long = 0,
    val overtimeBonus: Long = 0,
    val latePenalty: Long = 0,
    val totalBonus: Long = 0
)

fun calculateMonthlyKpiBonuses(
    employees: List<Employee>,
    month: YearMonth,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    overtimeRequests: List<OvertimeRequest>,
    adjustments: List<AttendanceAdjustment>,
    zoneId: ZoneId,
    leaveRequests: List<LeaveRequest> = emptyList()
): Map<String, KpiBonusBreakdown> {
    val base = employees.associate { employee ->
        val summaries = employeeMonthSummaries(
            employee.id,
            month.atDay(1),
            attendance,
            schedules,
            shifts,
            emptySet(),
            zoneId,
            adjustments,
            overtimeRequests = overtimeRequests,
            leaveRequests = leaveRequests
        )
        val completedOvertime = summaries.flatMap { it.shiftSummaries }
            .filter { it.shiftId == SUPPLEMENTARY_SHIFT_ID }
        val lateCount = summaries.count { summary ->
            // Lateness depends on the adjusted check-in, even when checkout is missing.
            summary.lateMinutes > 0
        }
        employee.id to KpiBonusBreakdown(
            overtimeShiftCount = completedOvertime.size,
            overtimeHours = completedOvertime.sumOf(EmployeeShiftSummary::overtimeHours),
            lateCount = lateCount
        )
    }

    val ranks = employees
        .filter { base.getValue(it.id).lateCount == 0 }
        .sortedWith(
            compareByDescending<Employee> { base.getValue(it.id).overtimeShiftCount }
                .thenByDescending { base.getValue(it.id).overtimeHours }
                .thenBy { it.code.ifBlank { it.id } }
                .thenBy { it.id }
        )
        .take(3)
        .mapIndexed { index, employee -> employee.id to index + 1 }
        .toMap()

    return employees.associate { employee ->
        val values = base.getValue(employee.id)
        val rank = ranks[employee.id]
        val top3Bonus = if (rank == null) 0L else TOP_THREE_BONUS
        val overtimeBonus = values.overtimeShiftCount * OVERTIME_SHIFT_BONUS
        val latePenalty = values.lateCount * LATE_PENALTY
        employee.id to values.copy(
            top3Rank = rank,
            top3Bonus = top3Bonus,
            overtimeBonus = overtimeBonus,
            latePenalty = latePenalty,
            totalBonus = (top3Bonus + overtimeBonus - latePenalty).coerceAtLeast(0)
        )
    }
}

fun payrollHoursForMonth(
    employeeId: String,
    month: YearMonth,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    overtimeRequests: List<OvertimeRequest>,
    adjustments: List<AttendanceAdjustment>,
    zoneId: ZoneId,
    leaveRequests: List<LeaveRequest> = emptyList()
): Double {
    val hours = employeeMonthSummaries(
        employeeId, month.atDay(1), attendance, schedules, shifts, emptySet(), zoneId,
        adjustments, overtimeRequests = overtimeRequests, leaveRequests = leaveRequests
    ).sumOf { daily -> daily.workedHours + daily.overtimeHours }
    return (hours * 100).roundToLong() / 100.0
}
