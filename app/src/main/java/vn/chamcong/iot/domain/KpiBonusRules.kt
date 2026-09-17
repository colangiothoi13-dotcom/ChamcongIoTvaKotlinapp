package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.workedHoursForMonth
import java.time.LocalDate
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
    zoneId: ZoneId
): Map<String, KpiBonusBreakdown> {
    val mainAttendance = attendance.filter { it.shiftId != SUPPLEMENTARY_SHIFT_ID }
    val mainSchedules = schedules.filter { it.shiftId != SUPPLEMENTARY_SHIFT_ID }
    val shiftsById = shifts.associateBy { it.id }
    val base = employees.associate { employee ->
        val schedulesByDate = mainSchedules.filter { it.employeeId == employee.id }.associateBy { it.date }
        val completedDates = completedOvertimeDates(
            employee.id, month, attendance, overtimeRequests, zoneId
        )
        val lateCount = employeeMonthSummaries(
            employee.id,
            month.atDay(1),
            mainAttendance,
            mainSchedules,
            shifts,
            emptySet(),
            zoneId,
            adjustments
        ).count { summary ->
            val shift = schedulesByDate[summary.date.toString()]?.let { shiftsById[it.shiftId] }
            // Lateness depends on the adjusted check-in, even when checkout is missing.
            attendanceLateMinutes(summary.checkIn, summary.date, shift, zoneId) > 0
        }
        employee.id to KpiBonusBreakdown(
            overtimeShiftCount = completedDates.size,
            overtimeHours = completedDates.size * SUPPLEMENTARY_HOURS,
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
    zoneId: ZoneId
): Double {
    if (overtimeRequests.isEmpty()) {
        return workedHoursForMonth(attendance, employeeId, month, zoneId, schedules, shifts, adjustments)
    }
    val regularHours = workedHoursForMonth(
        attendance.filter { it.shiftId != SUPPLEMENTARY_SHIFT_ID },
        employeeId,
        month,
        zoneId,
        schedules.filter { it.shiftId != SUPPLEMENTARY_SHIFT_ID },
        shifts,
        adjustments
    )
    val overtimeHours = completedOvertimeDates(
        employeeId, month, attendance, overtimeRequests, zoneId
    ).size * SUPPLEMENTARY_HOURS
    return ((regularHours + overtimeHours) * 100).roundToLong() / 100.0
}

private fun completedOvertimeDates(
    employeeId: String,
    month: YearMonth,
    attendance: List<Attendance>,
    overtimeRequests: List<OvertimeRequest>,
    zoneId: ZoneId
): Set<LocalDate> {
    val supplementaryShift = WorkShift(
        id = SUPPLEMENTARY_SHIFT_ID,
        name = "Supplementary",
        category = ShiftCategory.SUPPLEMENTARY.name,
        startTime = SUPPLEMENTARY_START_TIME,
        endTime = SUPPLEMENTARY_END_TIME,
        countsOvertime = true
    )
    return overtimeRequests.asSequence()
        .filter { it.employeeId == employeeId && it.status == OvertimeRequestStatus.APPROVED.name }
        .filter { runCatching { validateOvertimeRequest(it) }.isSuccess }
        .mapNotNull { request ->
            val date = runCatching { LocalDate.parse(request.workDate) }.getOrNull() ?: return@mapNotNull null
            if (YearMonth.from(date) != month) return@mapNotNull null
            val rows = attendance.filter {
                it.employeeId == employeeId &&
                    it.shiftId == SUPPLEMENTARY_SHIFT_ID &&
                    (it.scheduleDate == null || it.scheduleDate == request.workDate)
            }
            val pair = resolveAttendancePair(rows, date, supplementaryShift, zoneId = zoneId)
            val checkIn = pair.checkIn ?: return@mapNotNull null
            val checkOut = pair.checkOut ?: return@mapNotNull null
            val window = shiftWindow(date, supplementaryShift, zoneId)
            date.takeIf {
                !checkIn.isBefore(window.start) &&
                    !checkOut.isAfter(window.end) &&
                    checkOut.isAfter(checkIn)
            }
        }
        .toSet()
}
