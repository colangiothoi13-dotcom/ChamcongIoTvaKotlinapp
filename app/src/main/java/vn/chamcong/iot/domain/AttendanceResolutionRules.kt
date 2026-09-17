package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendancePair
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkSchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ShiftWindow(
    val scheduleDate: LocalDate,
    val start: Instant,
    val end: Instant
)

fun shiftWindow(scheduleDate: LocalDate, shift: WorkShift, zoneId: ZoneId): ShiftWindow {
    val startTime = LocalTime.parse(shift.startTime)
    val endTime = LocalTime.parse(shift.endTime)
    val start = scheduleDate.atTime(startTime).atZone(zoneId).toInstant()
    val endDate = scheduleDate.plusDays(if (endTime.isAfter(startTime)) 0 else 1)
    val end = endDate.atTime(endTime).atZone(zoneId).toInstant()
    return ShiftWindow(scheduleDate, start, end)
}

fun resolveAttendancePair(
    rows: List<Attendance>,
    scheduleDate: LocalDate,
    shift: WorkShift?,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    zoneId: ZoneId
): AttendancePair {
    val acceptedRows = rows
        .asSequence()
        .filter { it.verified && it.resolutionStatus == AttendanceResolutionStatus.ACCEPTED.name }
        .filter { it.type == AttendanceType.CHECK_IN.name || it.type == AttendanceType.CHECK_OUT.name }
        .filter { row -> belongsToScheduleDate(row, scheduleDate, shift, zoneId) }
        .sortedBy { it.timestamp.toDate().toInstant() }
        .toList()

    val employeeId = acceptedRows.firstOrNull()?.employeeId ?: rows.firstOrNull()?.employeeId.orEmpty()
    val adjustment = latestAdjustment(adjustments, employeeId, scheduleDate)
    // A corrected check-in can complete an otherwise orphaned accepted checkout.
    var checkIn: Instant? = adjustment?.checkInAt
    var checkOut: Instant? = null
    acceptedRows.forEach { row ->
        val eventAt = row.timestamp.toDate().toInstant()
        val openCheckIn = checkIn
        when (row.type) {
            AttendanceType.CHECK_IN.name -> if (checkIn == null) checkIn = eventAt
            AttendanceType.CHECK_OUT.name -> if (checkOut == null && openCheckIn != null && eventAt.isAfter(openCheckIn)) {
                checkOut = eventAt
            }
        }
    }

    return AttendancePair(
        scheduleDate = scheduleDate,
        checkIn = adjustment?.checkInAt ?: checkIn,
        checkOut = adjustment?.checkOutAt ?: checkOut,
        adjustment = adjustment
    )
}

fun isMissingCheckOut(
    pair: AttendancePair,
    scheduleDate: LocalDate,
    shift: WorkShift?,
    now: Instant,
    zoneId: ZoneId
): Boolean {
    if (pair.checkIn == null || pair.checkOut != null) return false
    if (shift == null) return now.atZone(zoneId).toLocalDate().isAfter(scheduleDate)

    val deadline = shiftWindow(scheduleDate, shift, zoneId)
        .end
        .plusSeconds(shift.missingCheckOutGraceMinutes * 60L)
    return now.isAfter(deadline)
}

fun latestAdjustment(
    adjustments: List<AttendanceAdjustment>,
    employeeId: String,
    scheduleDate: LocalDate
): AttendanceAdjustment? = adjustments
    .asSequence()
    .filter { it.employeeId == employeeId && it.scheduleDate == scheduleDate.toString() }
    .filter { runCatching { validateAttendanceAdjustment(it) }.isSuccess }
    .maxByOrNull { it.createdAt }

/** Assign legacy scans once using all supplied schedules; never change persisted raw rows.
 * Explicit dates win. In overlapping inclusive windows, typed scans use the nearest
 * start (check-in) or end (checkout), then chronological start and stable identifiers.
 */
internal fun assignAttendanceScheduleDates(
    rows: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    zoneId: ZoneId
): List<Attendance> {
    val shiftsById = shifts.associateBy { it.id }
    val candidates = schedules.mapNotNull { schedule ->
        val shift = shiftsById[schedule.shiftId] ?: return@mapNotNull null
        val window = runCatching { shiftWindow(LocalDate.parse(schedule.date), shift, zoneId) }.getOrNull()
            ?: return@mapNotNull null
        Triple(schedule, shift, window)
    }.groupBy { it.first.employeeId }
    return rows.map { row ->
        if (row.scheduleDate != null) return@map row
        val eventAt = row.timestamp.toDate().toInstant()
        val selected = candidates[row.employeeId].orEmpty()
            .filter { (_, shift, window) -> belongsToScheduleDate(row, window.scheduleDate, shift, zoneId) }
            .minWithOrNull(compareBy<Triple<WorkSchedule, WorkShift, ShiftWindow>> {
                val boundary = if (row.type == AttendanceType.CHECK_OUT.name) it.third.end else it.third.start
                Duration.between(eventAt, boundary).abs()
            }.thenBy { it.third.start }.thenBy { it.first.date }.thenBy { it.first.shiftId }.thenBy { it.first.id })
        selected?.let { row.copy(scheduleDate = it.first.date) } ?: row
    }
}

internal fun belongsToScheduleDate(
    row: Attendance,
    scheduleDate: LocalDate,
    shift: WorkShift?,
    zoneId: ZoneId
): Boolean {
    row.scheduleDate?.let { return it == scheduleDate.toString() }
    val eventAt = row.timestamp.toDate().toInstant()
    if (shift == null) return eventAt.atZone(zoneId).toLocalDate() == scheduleDate

    val window = shiftWindow(scheduleDate, shift, zoneId)
    val opensAt = window.start.minusSeconds(shift.allowEarlyMinutes * 60L)
    val closesAt = window.end.plusSeconds(shift.missingCheckOutGraceMinutes * 60L)
    return !eventAt.isBefore(opensAt) && !eventAt.isAfter(closesAt)
}
