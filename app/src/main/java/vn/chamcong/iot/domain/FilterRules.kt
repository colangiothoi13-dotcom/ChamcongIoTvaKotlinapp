package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
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
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
): List<Attendance> {
    val normalizedStatus = status?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    val normalizedType = type?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    val assigned = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    val schedulesByKey = schedules.associateBy { it.employeeId to it.date }
    val shiftsById = shifts.associateBy { it.id }
    val rowsByKey = assigned.groupBy { it.employeeId to (it.scheduleDate ?: it.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()) }
    val effectiveStatusByKey = rowsByKey.mapValues { (key, rows) ->
        val date = runCatching { LocalDate.parse(key.second) }.getOrNull()
        val shift = schedulesByKey[key]?.let { shiftsById[it.shiftId] }
        if (date == null || shift == null) null else {
            val pair = resolveAttendancePair(rows, date, shift, adjustments, zoneId)
            val late = attendanceLateMinutes(pair.checkIn, date, shift, zoneId) > 0
            val early = pair.checkOut?.isBefore(shiftWindow(date, shift, zoneId).end.minusSeconds(shift.earlyLeaveAllowedMinutes * 60L)) == true
            (if (late) "LATE" else "ON_TIME") to (if (early) "EARLY_LEAVE" else "NORMAL")
        }
    }
    // Preserve source order, including equal rows; computed copies never mutate persisted events.
    return assigned.map { row ->
        val date = row.scheduleDate ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()
        val statuses = effectiveStatusByKey[row.employeeId to date]
        if (statuses == null || !isAcceptedAttendance(row)) row
        else row.copy(status = if (row.type == "CHECK_IN") statuses.first else statuses.second)
    }.filter { row ->
        (normalizedStatus == null || row.status.lowercase(Locale.ROOT) == normalizedStatus) &&
            (normalizedType == null || row.type.lowercase(Locale.ROOT) == normalizedType)
    }
}
