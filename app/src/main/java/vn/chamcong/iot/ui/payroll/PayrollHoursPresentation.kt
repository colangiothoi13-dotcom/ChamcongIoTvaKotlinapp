package vn.chamcong.iot.ui.payroll

import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import vn.chamcong.iot.domain.employeeMonthSummaries
import vn.chamcong.iot.domain.latestAdjustment
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.ui.MainUiState
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

internal data class PayrollHoursPreview(
    val regularHours: Double,
    val overtimeHours: Double,
    val totalHours: Double,
    val missingCheckOutDays: Int,
    val days: List<EmployeeDaySummary>,
    val bonus: KpiBonusBreakdown = KpiBonusBreakdown()
)

private val payrollZone = ZoneId.of("Asia/Ho_Chi_Minh")
private fun Double.roundPayrollHours() = (this * 100).roundToLong() / 100.0

/** Unloaded or differently scoped data must never be presented as zero payable hours. */
internal fun payrollHoursPreviews(
    state: MainUiState,
    month: YearMonth,
    now: Instant = Instant.now(),
    allowPreviewData: Boolean = false
): Map<String, PayrollHoursPreview>? {
    if (!allowPreviewData && !state.hasCompleteCalculationRange(month.atDay(1), month.atEndOfMonth())) return null
    // Every amount and warning uses the same immutable state snapshot as the visible month.
    val employees = state.historicalEmployees(month)
    val attendance = state.historicalAttendanceForSummaries
    val schedules = state.effectiveSchedules
    val shifts = state.calculationShifts
    val overtime = state.calculationOvertimeRequests
    val adjustments = state.calculationAdjustments
    val leave = state.calculationLeaveRequests
    val bonuses = calculateMonthlyKpiBonuses(employees, month, attendance, schedules, shifts,
        overtime, adjustments, payrollZone, leave)
    val today = now.atZone(payrollZone).toLocalDate()
    return employees.associate { employee ->
        val summaries = employeeMonthSummaries(employee.id, month.atDay(1), attendance,
            schedules, shifts, emptySet(), payrollZone, adjustments, overtimeRequests = overtime,
            now = now, leaveRequests = leave)
        val regularHours = summaries.sumOf { it.workedHours }.roundPayrollHours()
        val overtimeHours = summaries.sumOf { it.overtimeHours }.roundPayrollHours()
        // Keep the exact existing payrollHoursForMonth aggregation and rounding policy.
        val totalHours = summaries.sumOf { it.workedHours + it.overtimeHours }.roundPayrollHours()
        val missingCheckOutDays = summaries.count { day ->
            val adjustedHours = latestAdjustment(adjustments, employee.id, day.date)?.workedHoursOverride
                ?: schedules.firstOrNull { it.employeeId == employee.id && it.date == day.date.toString() }?.workedHoursOverride
            !day.date.isAfter(today) && day.status != EmployeeAttendanceStatus.LEAVE && adjustedHours == null &&
                if (day.shiftSummaries.isNotEmpty()) {
                    day.shiftSummaries.any { shift ->
                        shift.status != EmployeeAttendanceStatus.LEAVE && shift.rawCheckInAt != null &&
                            shift.rawCheckOutAt == null && shift.paidCheckOutAt == null
                    }
                } else day.checkIn != null && day.checkOut == null
        }
        val daysWithEvidence = summaries.filter { day ->
            day.checkIn != null || day.checkOut != null || day.workedHours > 0 || day.overtimeHours > 0 ||
                day.shiftSummaries.any { it.rawCheckInAt != null || it.rawCheckOutAt != null }
        }
        employee.id to PayrollHoursPreview(regularHours, overtimeHours, totalHours,
            missingCheckOutDays, daysWithEvidence, bonuses[employee.id] ?: KpiBonusBreakdown())
    }
}
