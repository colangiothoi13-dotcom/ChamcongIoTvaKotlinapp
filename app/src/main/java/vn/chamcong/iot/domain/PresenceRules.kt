package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.PresenceRecord
import vn.chamcong.iot.model.PresenceStatus
import vn.chamcong.iot.model.RequestStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun classifyPresence(
    employee: Employee,
    rows: List<Attendance>,
    approvedRequests: List<LeaveRequest>,
    date: LocalDate,
    zoneId: ZoneId,
    now: Instant = Instant.now(),
    adjustments: List<AttendanceAdjustment> = emptyList(),
    shift: WorkShift? = null
): PresenceRecord {
    val dateIsCoveredByLeave = approvedRequests.any { request ->
        request.employeeId == employee.id &&
            request.type == "LEAVE" &&
            request.status == RequestStatus.APPROVED.name &&
            isDateInRange(date, request.startDate, request.endDate)
    }
    if (dateIsCoveredByLeave) return PresenceRecord(employee, PresenceStatus.ON_LEAVE)

    val dayRows = rows.filter {
        it.employeeId == employee.id && belongsToScheduleDate(it, date, shift, zoneId)
    }
    val adjustment = latestAdjustment(adjustments, employee.id, date)
    val rawPair = resolveAttendancePair(dayRows, date, shift, adjustments, zoneId)
    val pair = rawPair.copy(
        checkIn = adjustment?.checkInAt ?: rawPair.checkIn,
        checkOut = adjustment?.checkOutAt ?: rawPair.checkOut,
        adjustment = adjustment
    )
    val latest = dayRows.filter { it.resolutionStatus == "ACCEPTED" }
        .maxByOrNull { it.timestamp.toDate().toInstant() }
    val abnormal = dayRows.any {
        !it.verified || it.type !in AttendanceType.entries.map { type -> type.name } ||
            it.resolutionStatus in listOf("PENDING", "UNSCHEDULED", "OUT_OF_ORDER")
    }
    val status = when {
        abnormal -> PresenceStatus.ABNORMAL
        pair.checkIn != null && pair.checkOut != null && !pair.checkOut.isAfter(pair.checkIn) -> PresenceStatus.ABNORMAL
        pair.checkOut != null -> PresenceStatus.LEFT
        pair.checkIn == null -> PresenceStatus.NOT_CHECKED_IN
        isMissingCheckOut(pair, date, shift, now, zoneId) -> PresenceStatus.MISSING_CHECK_OUT
        else -> PresenceStatus.PRESENT
    }
    return PresenceRecord(employee, status, latest)
}

fun classifyPresenceForEmployees(
    employees: List<Employee>,
    attendance: List<Attendance>,
    requests: List<LeaveRequest>,
    date: LocalDate,
    zoneId: ZoneId,
    now: Instant = Instant.now(),
    adjustments: List<AttendanceAdjustment> = emptyList(),
    schedules: List<WorkSchedule> = emptyList(),
    shifts: List<WorkShift> = emptyList()
): List<PresenceRecord> = employees
    .filter(Employee::active)
    .map { employee ->
        val schedule = schedules.firstOrNull { it.employeeId == employee.id && it.date == date.toString() }
        val shift = schedule?.let { selected -> shifts.firstOrNull { it.id == selected.shiftId } }
        classifyPresence(employee, attendance, requests, date, zoneId, now, adjustments, shift)
    }

private fun isDateInRange(date: LocalDate, start: String, end: String): Boolean = runCatching {
    val startDate = LocalDate.parse(start)
    val endDate = LocalDate.parse(end)
    date in startDate..endDate
}.getOrDefault(false)
