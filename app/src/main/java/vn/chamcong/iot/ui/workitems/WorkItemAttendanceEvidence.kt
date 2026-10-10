package vn.chamcong.iot.ui.workitems

import vn.chamcong.iot.domain.assignAttendanceScheduleDates
import vn.chamcong.iot.domain.belongsToScheduleDate
import vn.chamcong.iot.domain.isAcceptedAttendance
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate

/** Uses attendance's canonical date/shift mapping without turning a scan into completion. */
internal fun workItemAttendanceEvidence(
    item: WorkItem,
    rows: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>
): List<Attendance> {
    val scheduleId = item.relatedScheduleId ?: return emptyList()
    val shiftId = item.relatedShiftId ?: return emptyList()
    val schedule = schedules.firstOrNull { it.id == scheduleId && it.employeeId == item.assigneeId }
    val date = (item.relatedScheduleDate ?: schedule?.date)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    } ?: return emptyList()
    val shift = shifts.firstOrNull { it.id == shiftId }
    val linkedShiftRegistered = schedule?.let { shiftId in it.shiftIds.ifEmpty { listOf(it.shiftId) } } == true
    // Multi-shift legacy rows must first be assigned once across all schedules. Single-shift
    // legacy rows deliberately keep null shiftId, then use the domain's date/window fallback.
    return assignAttendanceScheduleDates(rows, schedules, shifts, workZone).filter { row ->
        row.employeeId == item.assigneeId && isAcceptedAttendance(row) && when {
            shift == null -> row.shiftId == shiftId && row.scheduleDate == date.toString()
            row.shiftId == null && !linkedShiftRegistered -> false
            else -> belongsToScheduleDate(row, date, shift, workZone)
        }
    }.distinctBy { it.id.ifBlank { "${it.timestamp.seconds}|${it.timestamp.nanoseconds}|${it.type}" } }
        .sortedBy { it.timestamp }
}
