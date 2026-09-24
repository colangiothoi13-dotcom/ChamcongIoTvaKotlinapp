package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DailyAttendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.DailyDashboardSummary
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.LeaveRequest
import java.time.LocalDate
import java.time.Instant
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
            val schedule = schedulesByKey[employeeId to date.toString()]
            val selectedShifts = schedule?.let { scheduledShifts(it, shiftsById) }.orEmpty()
            if (selectedShifts.size > 1) {
                val adjustment = latestAdjustment(adjustments, employeeId, date)
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
                val pairs = selectedShifts.map { shift ->
                    val shiftAdjustments = if (shift.id == adjustmentShiftId) listOfNotNull(adjustment) else emptyList()
                    shift to resolveAttendancePair(rows, date, shift, shiftAdjustments, zoneId)
                }
                val observedPairs = pairs.filter { (_, pair) -> pair.checkIn != null || pair.checkOut != null }
                if (rows.isEmpty() && observedPairs.isEmpty()) continue
                checkedIds += employeeId
                if (pairs.any { (shift, pair) -> attendanceLateMinutes(pair.checkIn, date, shift, zoneId) > 0 }) {
                    lateIds += employeeId
                }
                val latestObserved = observedPairs.maxByOrNull { (shift, _) -> shiftWindow(date, shift, zoneId).start }
                val latestObservedPair = latestObserved?.second
                if (latestObservedPair != null && latestObservedPair.checkIn != null && latestObservedPair.checkOut == null) {
                    unresolvedIds += employeeId
                } else {
                    unresolvedIds -= employeeId
                }
                continue
            }
            val shift = selectedShifts.firstOrNull()
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

fun summarizeDailyDashboard(
    employees: List<Employee>,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    requests: List<LeaveRequest>,
    adjustments: List<AttendanceAdjustment>,
    date: LocalDate,
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
    now: Instant = Instant.now()
): DailyDashboardSummary {
    val activeEmployees = employees.filter { it.active && it.id.isNotBlank() }
    val assignedAttendance = assignAttendanceScheduleDates(
        attendance.filter(::isAcceptedAttendance), schedules, shifts, zoneId
    )
    val schedulesByEmployee = schedules
        .filter { it.date == date.toString() }
        .groupBy(WorkSchedule::employeeId)
    val shiftsById = shifts.associateBy(WorkShift::id)
    var checkedIn = 0
    var notCheckedIn = 0
    var present = 0
    var onLeave = 0
    var missingCheckOut = 0
    val lateEmployees = mutableListOf<Employee>()

    activeEmployees.forEach { employee ->
        val schedule = schedulesByEmployee[employee.id].orEmpty().firstOrNull()
        val selectedShifts = schedule?.let { scheduledShifts(it, shiftsById) }.orEmpty()
        val leaveShiftIds = schedule?.let {
            approvedLeaveShiftIdsForDate(employee.id, date, it, shifts, requests)
        }.orEmpty()
        val daily = employeeDaySummaryForSchedule(
            employeeId = employee.id,
            date = date,
            attendance = assignedAttendance,
            schedule = schedule,
            shifts = shifts,
            approvedLeave = false,
            zoneId = zoneId,
            adjustments = adjustments,
            now = now,
            approvedLeaveShiftIds = leaveShiftIds
        )
        val shiftSummaries = daily.shiftSummaries
        val hasCheckIn = daily.checkIn != null
        val hasWorkingShift = selectedShifts.any { it.id !in leaveShiftIds }
        if (hasCheckIn) checkedIn++
        if (!hasCheckIn && hasWorkingShift) notCheckedIn++
        if (shiftSummaries.any { it.lateMinutes > 0 }) lateEmployees += employee
        if (shiftSummaries.any { it.status == EmployeeAttendanceStatus.PRESENT }) present++
        if (shiftSummaries.any { it.status == EmployeeAttendanceStatus.LEAVE }) onLeave++
        if (shiftSummaries.any { it.status == EmployeeAttendanceStatus.MISSING_CHECK_OUT }) missingCheckOut++
    }

    return DailyDashboardSummary(
        date = date,
        activeEmployees = activeEmployees.size,
        checkedInEmployees = checkedIn,
        notCheckedInEmployees = notCheckedIn,
        lateEmployees = lateEmployees.distinctBy(Employee::id).sortedBy { it.fullName },
        presentEmployees = present,
        onLeaveEmployees = onLeave,
        missingCheckOutEmployees = missingCheckOut
    )
}
