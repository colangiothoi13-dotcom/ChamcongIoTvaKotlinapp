package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendancePair
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.PresenceRecord
import vn.chamcong.iot.model.PresenceStatus
import vn.chamcong.iot.model.RequestStatus
import java.time.Duration
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

    val adjustment = latestAdjustment(adjustments, employee.id, date)
    if (adjustment?.checkOutAt != null) return PresenceRecord(employee, PresenceStatus.LEFT)
    if (adjustment?.checkInAt != null) {
        val missingCheckout = if (shift != null) {
            isMissingCheckOut(
                pair = AttendancePair(scheduleDate = date, checkIn = adjustment.checkInAt),
                scheduleDate = date, shift = shift, now = now, zoneId = zoneId
            )
        } else {
            // Preserve the legacy fallback only when no schedule/shift context is available.
            date.isBefore(now.atZone(zoneId).toLocalDate()) ||
                Duration.between(adjustment.checkInAt, now).toHours() > 12
        }
        val hasRawCheckout = rows.any {
            it.employeeId == employee.id && it.resolutionStatus == "ACCEPTED" &&
                it.type == AttendanceType.CHECK_OUT.name &&
                (it.scheduleDate == date.toString() || (it.scheduleDate == null && it.localDate(zoneId) == date)) &&
                it.timestamp.toDate().toInstant().isAfter(adjustment.checkInAt)
        }
        return PresenceRecord(employee, when {
            hasRawCheckout -> PresenceStatus.LEFT
            missingCheckout -> PresenceStatus.MISSING_CHECK_OUT
            else -> PresenceStatus.PRESENT
        })
    }

    val latest = rows.asSequence()
        .filter { it.employeeId == employee.id && it.localDate(zoneId) == date }
        .maxWithOrNull(compareBy<Attendance> { it.timestamp.seconds }.thenBy { it.timestamp.nanoseconds })
        ?: return PresenceRecord(employee, PresenceStatus.NOT_CHECKED_IN)

    if (!latest.verified || latest.type !in AttendanceType.entries.map { it.name }) {
        return PresenceRecord(employee, PresenceStatus.ABNORMAL, latest)
    }
    if (latest.type == AttendanceType.CHECK_OUT.name) {
        return PresenceRecord(employee, PresenceStatus.LEFT, latest)
    }

    val today = now.atZone(zoneId).toLocalDate()
    val hoursSinceCheckIn = Duration.between(latest.timestamp.toDate().toInstant(), now).toHours()
    val missingCheckout = date.isBefore(today) || hoursSinceCheckIn > 12
    return PresenceRecord(
        employee = employee,
        status = if (missingCheckout) PresenceStatus.MISSING_CHECK_OUT else PresenceStatus.PRESENT,
        latestAttendance = latest
    )
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

private fun Attendance.localDate(zoneId: ZoneId): LocalDate = timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()

private fun isDateInRange(date: LocalDate, start: String, end: String): Boolean = runCatching {
    val startDate = LocalDate.parse(start)
    val endDate = LocalDate.parse(end)
    date in startDate..endDate
}.getOrDefault(false)
