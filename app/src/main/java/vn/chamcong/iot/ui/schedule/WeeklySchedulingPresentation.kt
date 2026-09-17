package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkShift
import java.time.LocalTime

fun unavailableWeeklyEmployeeIds(employees: List<Employee>, selectedIds: Set<String>): Set<String> =
    selectedIds - employees.filter { it.active && it.id.isNotBlank() }.map { it.id }.toSet()

fun scheduleShiftLabel(shift: WorkShift): String {
    if (shift.category != ShiftCategory.SUPPLEMENTARY.name) return shift.name
    val overnight = runCatching { LocalTime.parse(shift.endTime).isBefore(LocalTime.parse(shift.startTime)) }.getOrDefault(false)
    return "${shift.name} • ${shift.startTime}–${shift.endTime}" + if (overnight) " (ngày hôm sau)" else ""
}
