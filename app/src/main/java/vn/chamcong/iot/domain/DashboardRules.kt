package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DailyAttendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate
import java.time.ZoneId

fun summarizeDashboard(
    employees: List<Employee>,
    attendance: List<Attendance>,
    weekStart: LocalDate,
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
    schedules: List<WorkSchedule> = emptyList(),
    shifts: List<WorkShift> = emptyList(),
    adjustments: List<AttendanceAdjustment> = emptyList()
): DashboardSummary {
    val monday = mondayOfWeek(weekStart)
    val dates = weekDates(monday)
    val activeIds = employees.asSequence()
        .filter(Employee::active)
        .map(Employee::id)
        .filter(String::isNotBlank)
        .toSet()
    val assigned = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    val weekRows = assigned.filter { it.localDate(zoneId) in dates && isAcceptedAttendance(it) }
    val rowsByKey = weekRows.groupBy { it.employeeId to it.localDate(zoneId) }
    val schedulesByKey = schedules.associateBy { it.employeeId to it.date }
    val shiftsById = shifts.associateBy { it.id }
    val checkedIds = mutableSetOf<String>()
    val lateIds = mutableSetOf<String>()
    val unresolvedIds = mutableSetOf<String>()
    for (employeeId in activeIds) {
        for (date in dates) {
            val rows = rowsByKey[employeeId to date].orEmpty()
            val shift = schedulesByKey[employeeId to date.toString()]?.let { shiftsById[it.shiftId] }
            val adjustment = latestAdjustment(adjustments, employeeId, date)
            val rawPair = resolveAttendancePair(rows, date, shift, adjustments, zoneId)
            val pair = rawPair.copy(checkIn = adjustment?.checkInAt ?: rawPair.checkIn, checkOut = adjustment?.checkOutAt ?: rawPair.checkOut)
            if (rows.isEmpty() && pair.checkIn == null && pair.checkOut == null) continue
            checkedIds += employeeId
            if (attendanceLateMinutes(pair.checkIn, date, shift, zoneId) > 0 ||
                (shift == null && adjustment?.checkInAt == null && rows.any { it.type == "CHECK_IN" && it.status == "LATE" })) lateIds += employeeId
            // Presence follows the most recent day with effective attendance in the selected week.
            if (pair.checkIn != null && pair.checkOut == null) unresolvedIds += employeeId else unresolvedIds -= employeeId
        }
    }
    val weeklyAttendance = dates.map { date ->
        DailyAttendance(date = date, count = weekRows.count { it.localDate(zoneId) == date })
    }

    return DashboardSummary(
        weekStart = monday,
        activeEmployees = activeIds.size,
        checkedEmployees = checkedIds.size,
        lateEmployees = lateIds.size,
        unmarkedEmployees = (activeIds.size - checkedIds.size).coerceAtLeast(0),
        unresolvedPresenceEmployees = unresolvedIds.size,
        weeklyAttendance = weeklyAttendance
    )
}

private fun Attendance.localDate(zoneId: ZoneId): LocalDate =
    scheduleDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()
