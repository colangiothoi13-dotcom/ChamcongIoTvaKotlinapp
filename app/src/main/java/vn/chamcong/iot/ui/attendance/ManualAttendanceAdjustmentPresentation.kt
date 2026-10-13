package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import java.time.LocalDate
import java.util.Date
import vn.chamcong.iot.domain.approvedLeaveShiftIdsForDate
import vn.chamcong.iot.domain.assignAttendanceScheduleDates
import vn.chamcong.iot.domain.employeeDaySummaryForSchedule
import vn.chamcong.iot.domain.latestAdjustment
import vn.chamcong.iot.ui.MainUiState

/** A day total is useful for review, but the first and last scans of different shifts are not one pair. */
internal fun manualAdjustmentTarget(employeeId: String, employeeName: String, date: LocalDate, state: MainUiState): AttendanceAdjustmentTarget {
    val schedule = state.effectiveSchedules.firstOrNull { it.employeeId == employeeId && it.date == date.toString() }
    val selectedIds = schedule?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty().filter(String::isNotBlank).distinct()
    val multipleShifts = selectedIds.size > 1
    val assigned = assignAttendanceScheduleDates(state.historicalAttendanceForSummaries, state.effectiveSchedules, state.calculationShifts, attendanceZone)
    val leaveIds = approvedLeaveShiftIdsForDate(employeeId, date, schedule, state.calculationShifts, state.calculationLeaveRequests)
    val summary = employeeDaySummaryForSchedule(employeeId, date, assigned, schedule, state.calculationShifts, false, attendanceZone,
        adjustments = state.calculationAdjustments, approvedLeaveShiftIds = leaveIds)
    val previous = latestAdjustment(state.calculationAdjustments, employeeId, date)
    // Carry only explicitly saved endpoints on an ambiguous day; never bridge the lunch gap.
    val checkIn = if (multipleShifts) previous?.checkInAt else summary.checkIn
    val checkOut = if (multipleShifts) previous?.checkOutAt else summary.checkOut
    return AttendanceAdjustmentTarget(employeeId, employeeName, date.toString(),
        checkIn?.let { Timestamp(Date.from(it)) }, checkOut?.let { Timestamp(Date.from(it)) }, summary.workedHours,
        previous?.workedHoursOverride)
}
