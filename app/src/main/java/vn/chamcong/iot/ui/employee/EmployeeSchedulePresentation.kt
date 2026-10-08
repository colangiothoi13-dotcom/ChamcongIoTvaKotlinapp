package vn.chamcong.iot.ui.employee

import java.time.LocalDate
import java.time.YearMonth
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift

/** Monday-first calendar rows, with empty cells outside the selected month. */
internal fun employeeScheduleMonthDates(month: YearMonth): List<LocalDate?> {
    val firstDate = month.atDay(1)
    val leadingCells = firstDate.dayOfWeek.value - 1
    val occupiedCells = leadingCells + month.lengthOfMonth()
    val totalCells = ((occupiedCells + 6) / 7) * 7
    return List(totalCells) { index ->
        val day = index - leadingCells + 1
        if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }
}

internal fun employeeScheduleWeekDates(anchor: LocalDate): List<LocalDate> {
    val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
    return (0L..6L).map(monday::plusDays)
}

/** The shiftIds list is authoritative; shiftId supports older single-shift records. */
internal fun employeeScheduleShiftIds(schedule: WorkSchedule): List<String> =
    schedule.shiftIds.filter(String::isNotBlank).distinct()
        .ifEmpty { listOf(schedule.shiftId).filter(String::isNotBlank) }

/** Preserve registered legacy IDs while allowing only one active shift per main category. */
internal fun employeeRegistrationShiftIds(
    ids: List<String>,
    shiftsById: Map<String, WorkShift>
): List<String> = ids.distinct().mapNotNull { id ->
    shiftsById[id]?.takeIf { shift ->
        id.isNotBlank() && shift.active &&
            shift.category in setOf(ShiftCategory.MORNING.name, ShiftCategory.EVENING.name)
    }?.let { id to it }
}.distinctBy { it.second.category }
    .sortedBy { it.second.startTime }
    .map { it.first }

internal fun employeeToggleRegistrationShift(
    ids: List<String>,
    shift: WorkShift,
    shiftsById: Map<String, WorkShift>
): List<String> {
    val eligibleIds = employeeRegistrationShiftIds(ids, shiftsById)
    return if (eligibleIds.any { shiftsById[it]?.category == shift.category }) {
        eligibleIds.filterNot { shiftsById[it]?.category == shift.category }
    } else {
        employeeRegistrationShiftIds(eligibleIds + shift.id, shiftsById)
    }
}
