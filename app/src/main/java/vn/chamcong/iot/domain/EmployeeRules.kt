package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkTimeSummary
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

fun canAccessEmployee(provider: String, profile: UserProfile?): Boolean =
    provider != "anonymous" &&
        profile?.role == "EMPLOYEE" &&
        profile.active &&
        !profile.employeeId.isNullOrBlank()

fun canUseEmployeeShell(profile: UserProfile?, employeeId: String?): Boolean =
    canAccessEmployee("password", profile) && profile?.employeeId == employeeId && !employeeId.isNullOrBlank()

fun employeeDaySummary(
    employeeId: String,
    date: LocalDate,
    attendance: List<Attendance>,
    schedule: WorkSchedule?,
    shift: WorkShift?,
    approvedLeave: Boolean,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now()
): EmployeeDaySummary {
    val dayRows = attendance
        .asSequence()
        .filter { it.employeeId == employeeId && belongsToScheduleDate(it, date, shift, zoneId) }
        .sortedBy { it.timestamp.toDate().time }
        .toList()
    val adjustment = latestAdjustment(adjustments, employeeId, date)
    val pair = resolveAttendancePair(dayRows, date, shift, adjustments, zoneId)
    // Explicit employee lookup also covers adjustments with no raw scans.
    val checkIn = adjustment?.checkInAt ?: pair.checkIn
    val checkOut = adjustment?.checkOutAt ?: pair.checkOut
    val calculated = if (checkIn != null && checkOut != null) {
        calculateWorkTime(checkIn, checkOut, shift, schedule?.overtimeHours ?: 0, zoneId, date)
    } else WorkTimeSummary()
    val status = when {
        approvedLeave -> EmployeeAttendanceStatus.LEAVE
        dayRows.any(::isAbnormalAttendance) -> EmployeeAttendanceStatus.ABNORMAL
        checkIn != null && checkOut != null && !checkOut.isAfter(checkIn) -> EmployeeAttendanceStatus.ABNORMAL
        checkIn == null -> EmployeeAttendanceStatus.MISSING_CHECK_IN
        isMissingCheckOut(pair.copy(checkIn = checkIn, checkOut = checkOut), date, shift, now, zoneId) -> EmployeeAttendanceStatus.MISSING_CHECK_OUT
        checkOut == null -> EmployeeAttendanceStatus.PRESENT
        calculated.lateMinutes > 0 && calculated.earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.ABNORMAL
        calculated.lateMinutes > 0 -> EmployeeAttendanceStatus.LATE
        calculated.earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.EARLY_LEAVE
        else -> EmployeeAttendanceStatus.ON_TIME
    }
    return EmployeeDaySummary(
        date = date,
        shiftName = schedule?.shiftName.orEmpty(),
        shiftStartTime = shift?.startTime.orEmpty(),
        shiftEndTime = shift?.endTime.orEmpty(),
        checkIn = checkIn,
        checkOut = checkOut,
        workedHours = adjustment?.workedHoursOverride ?: schedule?.workedHoursOverride ?: calculated.workedHours,
        overtimeHours = calculated.overtimeHours,
        lateMinutes = calculated.lateMinutes,
        earlyLeaveMinutes = calculated.earlyLeaveMinutes,
        status = status
    )
}

fun employeeMonthSummaries(
    employeeId: String,
    month: LocalDate,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    approvedLeaveDates: Set<LocalDate>,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now()
): List<EmployeeDaySummary> {
    val firstDay = month.withDayOfMonth(1)
    val assignedAttendance = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    val scheduleByDate = schedules.filter { it.employeeId == employeeId }.associateBy { it.date }
    val shiftById = shifts.associateBy { it.id }
    return (0 until firstDay.lengthOfMonth()).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        val schedule = scheduleByDate[date.toString()]
        employeeDaySummary(
            employeeId = employeeId,
            date = date,
            attendance = assignedAttendance,
            schedule = schedule,
            shift = schedule?.let { shiftById[it.shiftId] },
            approvedLeave = date in approvedLeaveDates,
            zoneId = zoneId,
            adjustments = adjustments,
            now = now
        )
    }
}

fun employeeRequestDraft(
    employee: Employee,
    type: RequestType,
    startDate: String,
    endDate: String,
    reason: String
): LeaveRequest {
    val request = LeaveRequest(
        employeeId = employee.id,
        employeeName = employee.fullName,
        department = employee.department,
        type = type.name,
        startDate = startDate,
        endDate = endDate,
        reason = reason,
        status = RequestStatus.PENDING.name,
        reviewerId = null,
        reviewerName = null,
        reviewedAt = null,
        reviewNote = null
    )
    validateRequest(request)
    return request
}
