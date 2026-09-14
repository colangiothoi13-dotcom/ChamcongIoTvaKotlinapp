package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceType
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
    zoneId: ZoneId
): EmployeeDaySummary {
    val dayRows = attendance
        .asSequence()
        .filter { it.employeeId == employeeId && it.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate() == date }
        .sortedBy { it.timestamp.toDate().time }
        .toList()
    val checkIn = dayRows.firstOrNull { it.type == AttendanceType.CHECK_IN.name }
        ?.timestamp?.toDate()?.toInstant()
    val checkOut = dayRows.lastOrNull { row ->
        row.type == AttendanceType.CHECK_OUT.name && checkIn != null && row.timestamp.toDate().toInstant().isAfter(checkIn)
    }?.timestamp?.toDate()?.toInstant()
    val calculated = if (checkIn != null && checkOut != null) {
        calculateWorkTime(checkIn, checkOut, shift, schedule?.overtimeHours ?: 0, zoneId)
    } else WorkTimeSummary()
    val status = when {
        approvedLeave -> EmployeeAttendanceStatus.LEAVE
        checkIn == null -> EmployeeAttendanceStatus.MISSING_CHECK_IN
        checkOut == null -> EmployeeAttendanceStatus.MISSING_CHECK_OUT
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
        workedHours = schedule?.workedHoursOverride ?: calculated.workedHours,
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
    zoneId: ZoneId
): List<EmployeeDaySummary> {
    val firstDay = month.withDayOfMonth(1)
    val scheduleByDate = schedules.filter { it.employeeId == employeeId }.associateBy { it.date }
    val shiftById = shifts.associateBy { it.id }
    return (0 until firstDay.lengthOfMonth()).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        val schedule = scheduleByDate[date.toString()]
        employeeDaySummary(
            employeeId = employeeId,
            date = date,
            attendance = attendance,
            schedule = schedule,
            shift = schedule?.let { shiftById[it.shiftId] },
            approvedLeave = date in approvedLeaveDates,
            zoneId = zoneId
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
        reviewNote = null,
        createdAt = Timestamp.now()
    )
    validateRequest(request)
    return request
}
