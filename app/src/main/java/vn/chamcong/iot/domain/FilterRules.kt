package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.LeaveRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

fun filterEmployees(
    employees: List<Employee>,
    query: String,
    department: String?,
    includeRetired: Boolean
): List<Employee> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val normalizedDepartment = department?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    return employees.filter { employee ->
        (includeRetired || employee.active) &&
            (normalizedQuery.isBlank() || employee.code.lowercase(Locale.ROOT).contains(normalizedQuery) || employee.fullName.lowercase(Locale.ROOT).contains(normalizedQuery)) &&
            (normalizedDepartment == null || employee.department.lowercase(Locale.ROOT) == normalizedDepartment)
    }
}

fun filterAttendance(
    attendance: List<Attendance>,
    status: String?,
    type: String?,
    schedules: List<WorkSchedule> = emptyList(),
    shifts: List<WorkShift> = emptyList(),
    adjustments: List<AttendanceAdjustment> = emptyList(),
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
    leaveRequests: List<LeaveRequest> = emptyList()
): List<Attendance> {
    val normalizedStatus = status?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    val normalizedType = type?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    val assigned = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    val schedulesByKey = schedules.associateBy { it.employeeId to it.date }
    val shiftsById = shifts.associateBy { it.id }
    val rowsByKey = assigned.groupBy { it.employeeId to (it.scheduleDate ?: it.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()) }
    val effectiveStatusByKey = mutableMapOf<Triple<String, String, String>, Pair<String, String>>()
    rowsByKey.forEach { (key, rows) ->
        val date = runCatching { LocalDate.parse(key.second) }.getOrNull()
        val schedule = schedulesByKey[key]
        val selectedShifts = schedule?.let { scheduledShifts(it, shiftsById) }.orEmpty()
        if (date == null || selectedShifts.isEmpty()) return@forEach
        val leaveShiftIds = approvedLeaveShiftIdsForDate(key.first, date, schedule, shifts, leaveRequests)
        val adjustment = latestAdjustment(adjustments, key.first, date)
        val adjustmentInstant = adjustment?.checkInAt ?: adjustment?.checkOutAt
        val adjustmentShiftId = adjustmentInstant?.let { instant ->
            nearestScheduledShift(
                date,
                selectedShifts,
                instant,
                if (adjustment?.checkInAt != null) "CHECK_IN" else "CHECK_OUT",
                zoneId
            )?.id
        }
        selectedShifts.forEach { shift ->
            val shiftAdjustments = if (shift.id == adjustmentShiftId) listOfNotNull(adjustment) else emptyList()
            val pair = resolveAttendancePair(rows, date, shift, shiftAdjustments, zoneId)
            val late = shift.id !in leaveShiftIds && attendanceLateMinutes(pair.checkIn, date, shift, zoneId) > 0
            val early = shift.id !in leaveShiftIds && attendanceEarlyLeaveMinutes(pair.checkOut, date, shift, zoneId) > 0
            effectiveStatusByKey[Triple(key.first, key.second, shift.id)] =
                (if (late) "LATE" else "ON_TIME") to (if (early) "EARLY_LEAVE" else "NORMAL")
        }
    }
    // Preserve source order, including equal rows; computed copies never mutate persisted events.
    return assigned.map { row ->
        val date = row.scheduleDate ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()
        val key = row.employeeId to date
        val schedule = schedulesByKey[key]
        val shift = schedule?.let { shiftForAttendance(row, it, shiftsById, zoneId) }
        val statuses = shift?.let { effectiveStatusByKey[Triple(row.employeeId, date, it.id)] }
        if (statuses == null || !isAcceptedAttendance(row)) row
        else row.copy(status = if (row.type == "CHECK_IN") statuses.first else statuses.second)
    }.filter { row ->
        (normalizedStatus == null || attendanceStatusMatches(row.status, normalizedStatus)) &&
            (normalizedType == null || row.type.lowercase(Locale.ROOT) == normalizedType)
    }
}

private fun attendanceStatusMatches(actual: String, selected: String): Boolean = when (selected) {
    // Legacy rows use NORMAL for an on-time scan, while schedule-resolved
    // check-ins use ON_TIME. The UI exposes one "Đúng giờ" filter for both.
    "normal" -> actual.lowercase(Locale.ROOT) in setOf("normal", "on_time")
    else -> actual.lowercase(Locale.ROOT) == selected
}
