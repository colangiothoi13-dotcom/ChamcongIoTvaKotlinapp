package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.AttendancePair
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.AttendanceStatus
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkSchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

data class ShiftWindow(
    val scheduleDate: LocalDate,
    val start: Instant,
    val end: Instant
)

/** Legacy omitted fields retain the model defaults; explicit malformed fields are rejected. */
fun isAcceptedAttendance(row: Attendance): Boolean =
    row.verified && row.resolutionStatus == AttendanceResolutionStatus.ACCEPTED.name &&
        row.type in AttendanceType.entries.map { it.name } &&
        row.status in AttendanceStatus.entries.map { it.name }

fun isAbnormalAttendance(row: Attendance): Boolean {
    if (!row.verified) return true
    // Both the server's rejection type and older typed duplicate rows are supported.
    if (row.resolutionStatus == AttendanceResolutionStatus.DUPLICATE.name &&
        row.type in listOf("DUPLICATE", "CHECK_IN", "CHECK_OUT")) return false
    return !isAcceptedAttendance(row)
}

fun validateAttendanceClassificationOverride(override: AttendanceClassificationOverride) {
    require(override.attendanceId.isNotBlank()) { "Thiếu mã lượt chấm cần sửa" }
    require(override.employeeId.isNotBlank()) { "Thiếu mã nhân viên" }
    require(override.employeeName.isNotBlank()) { "Thiếu tên nhân viên" }
    require(runCatching { LocalDate.parse(override.scheduleDate) }.isSuccess) { "Ngày ca không hợp lệ" }
    require(override.sourceTimestamp != Instant.EPOCH) { "Thiếu thời điểm lượt chấm gốc" }
    require(override.sourceType.isNotBlank() && override.sourceStatus.isNotBlank()) { "Thiếu phân loại gốc" }
    require(override.sourceResolutionStatus.isNotBlank()) { "Thiếu trạng thái xử lý gốc" }
    require(override.correctedType in AttendanceType.entries.map { it.name }) { "Phân loại vào/ra không hợp lệ" }
    require(override.correctedStatus in AttendanceStatus.entries.map { it.name }) { "Trạng thái công không hợp lệ" }
    require(override.reason.isNotBlank() && override.reason.length <= 500) { "Lý do bắt buộc và tối đa 500 ký tự" }
    require(override.actorId.isNotBlank() && override.actorName.isNotBlank()) { "Thiếu thông tin Admin thực hiện" }
    require(override.previousType != override.correctedType || override.previousStatus != override.correctedStatus) {
        "Phân loại mới phải khác phân loại hiện tại"
    }
}

fun latestAttendanceClassificationOverride(
    overrides: List<AttendanceClassificationOverride>,
    attendanceId: String
): AttendanceClassificationOverride? = overrides
    .asSequence()
    .filter { it.attendanceId == attendanceId }
    .filter { runCatching { validateAttendanceClassificationOverride(it) }.isSuccess }
    .maxByOrNull { it.createdAt }

/** Applies append-only corrections to in-memory rows for calculations; persisted scans are untouched. */
fun applyAttendanceClassificationOverrides(
    rows: List<Attendance>,
    overrides: List<AttendanceClassificationOverride>
): List<Attendance> {
    if (rows.isEmpty() || overrides.isEmpty()) return rows
    val latestByScan = overrides.asSequence()
        .filter { it.attendanceId.isNotBlank() }
        .filter { runCatching { validateAttendanceClassificationOverride(it) }.isSuccess }
        .groupBy(AttendanceClassificationOverride::attendanceId)
        .mapValues { (_, history) -> history.maxByOrNull(AttendanceClassificationOverride::createdAt)!! }
    return rows.map { row ->
        val correction = latestByScan[row.id] ?: return@map row
        val scanTimestamp = row.timestamp.toDate().toInstant()
        if (row.id.isBlank() || scanTimestamp != correction.sourceTimestamp) row
        else row.copy(type = correction.correctedType, status = correction.correctedStatus)
    }
}

/**
 * Spark fallback for off-schedule reviews. Cloud Functions normally writes the
 * final review status and annotates the immutable attendance row. On Spark,
 * the client cannot update either document, so a pending REJECT decision is
 * overlaid in memory for the attendance list and review controls.
 */
fun applyOffScheduleReviewDecisions(
    rows: List<Attendance>,
    reviews: List<OffScheduleAttendanceReview>,
    zoneId: ZoneId
): List<Attendance> {
    if (rows.isEmpty() || reviews.isEmpty()) return rows
    val rejectedReviews = reviews.filter { review ->
        review.decision == "REJECT" && review.status in setOf("PENDING", "REJECTED")
    }
    if (rejectedReviews.isEmpty()) return rows

    return rows.map { row ->
        if (row.type != "UNSCHEDULED" &&
            row.resolutionStatus != AttendanceResolutionStatus.UNSCHEDULED.name) {
            return@map row
        }
        if (row.offScheduleReviewStatus in setOf("APPROVED", "REJECTED")) return@map row
        val scheduleDate = row.scheduleDate
            ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()
        val sameEmployeeDayReviews = rejectedReviews.filter { review ->
            review.scheduleDate == scheduleDate &&
                (review.employeeId == row.employeeId ||
                    (review.employeeName.isNotBlank() && review.employeeName == row.employeeName))
        }
        val matchingReview = sameEmployeeDayReviews.firstOrNull { review ->
            row.shiftId.isNullOrBlank() || row.shiftId == review.shiftId
        } ?: sameEmployeeDayReviews.firstOrNull() ?: return@map row

        row.copy(
            offScheduleReviewStatus = "REJECTED",
            offScheduleReviewerId = matchingReview.reviewerId,
            offScheduleReviewerName = matchingReview.reviewerName
        )
    }
}

fun attendanceLateMinutes(checkIn: Instant?, date: LocalDate, shift: WorkShift?, zoneId: ZoneId): Int {
    if (checkIn == null || shift == null) return 0
    val deadline = shiftWindow(date, shift, zoneId).start.plusSeconds(shift.lateGraceMinutes * 60L)
    return Duration.between(deadline, checkIn).toMinutes().coerceAtLeast(0).toInt()
}

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
        .filter(::isAcceptedAttendance)
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

    val checkoutGraceSeconds = effectiveCheckoutGraceSeconds(shift)
    val deadline = shiftWindow(scheduleDate, shift, zoneId).end.plusSeconds(checkoutGraceSeconds)
    return if (hasStrictCheckoutWindow(shift)) !now.isBefore(deadline) else now.isAfter(deadline)
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

/** Resolves the explicit multi-shift list, while retaining legacy shiftId-only schedules. */
internal fun scheduledShiftIds(schedule: WorkSchedule): List<String> {
    val selected = schedule.shiftIds.map { it.trim() }.filter(String::isNotBlank).distinct()
    val legacyId = schedule.shiftId.trim().takeIf(String::isNotBlank)
    if (selected.isEmpty()) return listOfNotNull(legacyId)
    if (legacyId == null) return selected
    return listOf(legacyId) + selected.filterNot { it == legacyId }
}

internal fun scheduledShifts(
    schedule: WorkSchedule,
    shiftsById: Map<String, WorkShift>
): List<WorkShift> {
    val seenCategories = mutableSetOf<String>()
    return scheduledShiftIds(schedule).mapNotNull(shiftsById::get)
        .sortedWith(compareBy<WorkShift> { it.startTime }.thenBy { it.id })
        .filter { seenCategories.add(it.category) }
}

internal fun shiftForAttendance(
    row: Attendance,
    schedule: WorkSchedule,
    shiftsById: Map<String, WorkShift>,
    zoneId: ZoneId
): WorkShift? {
    val shifts = scheduledShifts(schedule, shiftsById)
    if (row.shiftId != null) return shifts.firstOrNull { it.id == row.shiftId }
    if (shifts.size <= 1) return shifts.firstOrNull()
    val date = runCatching { LocalDate.parse(schedule.date) }.getOrNull() ?: return null
    val eventAt = row.timestamp.toDate().toInstant()
    return shifts.minByOrNull { shift ->
        val window = runCatching { shiftWindow(date, shift, zoneId) }.getOrNull()
        if (window == null) Duration.ofSeconds(Long.MAX_VALUE)
        else distanceToShiftBoundary(eventAt, row.type, window)
    }
}

internal fun nearestScheduledShift(
    date: LocalDate,
    shifts: List<WorkShift>,
    eventAt: Instant,
    type: String,
    zoneId: ZoneId
): WorkShift? = shifts.minByOrNull { shift ->
    val window = runCatching { shiftWindow(date, shift, zoneId) }.getOrNull()
    if (window == null) Duration.ofSeconds(Long.MAX_VALUE)
    else distanceToShiftBoundary(eventAt, type, window)
}

private fun distanceToShiftBoundary(eventAt: Instant, type: String, window: ShiftWindow): Duration {
    val boundary = if (type == AttendanceType.CHECK_OUT.name) window.end else window.start
    return Duration.between(eventAt, boundary).abs()
}

/** Assign legacy scans once using all supplied schedules; never change persisted raw rows.
 * Explicit dates win. Untagged events on multi-shift days get an in-memory shift identity
 * from the nearest check-in start or checkout end; overlapping windows use stable ties.
 */
internal fun assignAttendanceScheduleDates(
    rows: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    zoneId: ZoneId
): List<Attendance> {
    val shiftsById = shifts.associateBy { it.id }
    val candidates = schedules.flatMap { schedule ->
        val date = runCatching { LocalDate.parse(schedule.date) }.getOrNull() ?: return@flatMap emptyList()
        scheduledShifts(schedule, shiftsById).mapNotNull { shift ->
            val window = runCatching { shiftWindow(date, shift, zoneId) }.getOrNull() ?: return@mapNotNull null
            Triple(schedule, shift, window)
        }
    }.groupBy { it.first.employeeId }
    return rows.map { row ->
        val eventAt = row.timestamp.toDate().toInstant()
        val sameDateCandidates = candidates[row.employeeId].orEmpty()
            .filter { candidate ->
                (row.scheduleDate == null || candidate.first.date == row.scheduleDate) &&
                    (row.shiftId == null || candidate.second.id == row.shiftId)
            }
        val selected = if (row.scheduleDate != null) {
            sameDateCandidates.minWithOrNull(compareBy<Triple<WorkSchedule, WorkShift, ShiftWindow>> {
                distanceToShiftBoundary(eventAt, row.type, it.third)
            }.thenBy { it.third.start }.thenBy { it.first.date }.thenBy { it.second.id }.thenBy { it.first.id })
        } else {
            sameDateCandidates
                .filter { (_, shift, window) -> belongsToScheduleDate(row, window.scheduleDate, shift, zoneId) }
                .minWithOrNull(compareBy<Triple<WorkSchedule, WorkShift, ShiftWindow>> {
                    distanceToShiftBoundary(eventAt, row.type, it.third)
                }.thenBy { it.third.start }.thenBy { it.first.date }.thenBy { it.second.id }.thenBy { it.first.id })
        }
        if (selected == null) row else {
            val multiShift = scheduledShifts(selected.first, shiftsById).size > 1
            if (row.scheduleDate != null && !multiShift) row
            else row.copy(
                scheduleDate = row.scheduleDate ?: selected.first.date,
                shiftId = row.shiftId ?: selected.second.id.takeIf { multiShift }
            )
        }
    }
}

internal fun belongsToScheduleDate(
    row: Attendance,
    scheduleDate: LocalDate,
    shift: WorkShift?,
    zoneId: ZoneId
): Boolean {
    // Explicit shift identity wins even when the event carries the same schedule date.
    // Untagged legacy rows keep the existing date/window fallback.
    if (shift != null && row.shiftId != null && row.shiftId != shift.id) return false
    row.scheduleDate?.let { return it == scheduleDate.toString() }
    val eventAt = row.timestamp.toDate().toInstant()
    if (shift == null) return eventAt.atZone(zoneId).toLocalDate() == scheduleDate

    val window = shiftWindow(scheduleDate, shift, zoneId)
    val earlyMinutes = if (isStandardMorning(shift)) maxOf(120, shift.allowEarlyMinutes) else shift.allowEarlyMinutes
    val opensAt = window.start.minusSeconds(earlyMinutes * 60L)
    val checkoutGraceSeconds = effectiveCheckoutGraceSeconds(shift)
    val closesAt = window.end.plusSeconds(checkoutGraceSeconds)
    val insideClose = !eventAt.isAfter(closesAt)
    return !eventAt.isBefore(opensAt) && insideClose
}

private fun isStandardMorning(shift: WorkShift): Boolean =
    shift.category == "MORNING" && shift.startTime == "08:00" && shift.endTime == "12:00"

private fun isStandardAfternoon(shift: WorkShift): Boolean =
    shift.category == "EVENING" && shift.startTime == "13:00" && shift.endTime == "17:00"

private fun hasStrictCheckoutWindow(shift: WorkShift): Boolean =
    shift.id == SUPPLEMENTARY_SHIFT_ID || isStandardMorning(shift) || isStandardAfternoon(shift)

private fun effectiveCheckoutGraceSeconds(shift: WorkShift): Long = when {
    shift.id == SUPPLEMENTARY_SHIFT_ID -> 120L * 60L
    isStandardMorning(shift) || isStandardAfternoon(shift) -> 30L * 60L
    else -> shift.missingCheckOutGraceMinutes * 60L
}

private data class SparkScheduleCandidate(
    val schedule: WorkSchedule,
    val shift: WorkShift,
    val window: ShiftWindow
)

private data class SparkSessionKey(
    val employeeId: String,
    val scheduleDate: LocalDate,
    val shiftId: String
)

private data class SparkSession(
    var lastAcceptedAt: Instant? = null,
    var openCheckInAt: Instant? = null,
    var closed: Boolean = false
)

/**
 * Resolves Spark raw scans locally without writing back to Firestore.
 *
 * Spark rules intentionally allow the device to create only SCAN/PENDING
 * documents. The Android app can still provide the same attendance view as
 * the optional Functions resolver by deriving an effective copy in memory.
 */
fun resolveSparkPendingAttendance(
    rows: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    zoneId: ZoneId
): List<Attendance> {
    if (rows.isEmpty() || schedules.isEmpty() || shifts.isEmpty()) return rows

    val shiftsById = shifts.associateBy(WorkShift::id)
    val candidates = schedules.flatMap { schedule ->
        val date = runCatching { LocalDate.parse(schedule.date) }.getOrNull() ?: return@flatMap emptyList()
        val scheduled = scheduledShifts(schedule, shiftsById)
        scheduled.map { shift -> SparkScheduleCandidate(schedule, shift, shiftWindow(date, shift, zoneId)) }
    }
    if (candidates.isEmpty()) return rows

    val sessions = mutableMapOf<SparkSessionKey, SparkSession>()
    val resolved = rows.toMutableList()
    rows.indices
        .sortedWith(compareBy<Int> { rows[it].timestamp.toDate().time }.thenBy { rows[it].id })
        .forEach { index ->
            val row = rows[index]
            val eventAt = row.timestamp.toDate().toInstant()
            val pendingScan = row.type == "SCAN" &&
                row.resolutionStatus == AttendanceResolutionStatus.PENDING.name
            val employeeCandidates = candidates.filter { candidate ->
                candidate.schedule.employeeId == row.employeeId &&
                    (row.scheduleDate == null || row.scheduleDate == candidate.schedule.date) &&
                    (row.shiftId == null || row.shiftId == candidate.shift.id)
            }
            if (employeeCandidates.isEmpty()) return@forEach

            val selected = if (pendingScan) {
                employeeCandidates
                    .filter { candidate -> eventAt >= candidate.window.start.minusSeconds(effectiveOpenEarlySeconds(candidate.shift)) &&
                        eventAt <= candidate.window.end.plusSeconds(effectiveCheckoutGraceSeconds(candidate.shift)) }
                    .minWithOrNull(compareBy<SparkScheduleCandidate> {
                        minOf(abs(eventAt.epochSecond - it.window.start.epochSecond),
                            abs(eventAt.epochSecond - it.window.end.epochSecond))
                    }.thenBy { it.window.start }.thenBy { it.shift.id })
            } else {
                employeeCandidates.minWithOrNull(compareBy<SparkScheduleCandidate> {
                    distanceToShiftBoundary(eventAt, row.type, it.window)
                }.thenBy { it.window.start }.thenBy { it.shift.id })
            } ?: return@forEach

            val date = selected.window.scheduleDate
            val key = SparkSessionKey(row.employeeId, date, selected.shift.id)
            val session = sessions.getOrPut(key) { SparkSession() }

            if (!pendingScan) {
                if (isAcceptedAttendance(row)) {
                    when (row.type) {
                        AttendanceType.CHECK_IN.name -> {
                            session.lastAcceptedAt = eventAt
                            session.openCheckInAt = eventAt
                            session.closed = false
                        }
                        AttendanceType.CHECK_OUT.name -> {
                            session.lastAcceptedAt = eventAt
                            session.openCheckInAt = null
                            session.closed = true
                        }
                    }
                }
                return@forEach
            }

            val duplicate = session.lastAcceptedAt?.let {
                abs(eventAt.epochSecond - it.epochSecond) <= DUPLICATE_WINDOW_SECONDS
            } == true
            val outOfOrder = session.lastAcceptedAt?.let { eventAt.isBefore(it) } == true
            val type = if (session.openCheckInAt != null || !eventAt.isBefore(selected.window.end)) {
                AttendanceType.CHECK_OUT.name
            } else {
                AttendanceType.CHECK_IN.name
            }

            resolved[index] = when {
                duplicate -> row.copy(
                    type = "DUPLICATE",
                    status = "ABNORMAL",
                    resolutionStatus = AttendanceResolutionStatus.DUPLICATE.name,
                    scheduleDate = selected.schedule.date,
                    shiftId = selected.shift.id,
                    syncStatus = "SYNCED"
                )
                outOfOrder -> row.copy(
                    type = "OUT_OF_ORDER",
                    status = "ABNORMAL",
                    resolutionStatus = AttendanceResolutionStatus.OUT_OF_ORDER.name,
                    scheduleDate = selected.schedule.date,
                    shiftId = selected.shift.id,
                    syncStatus = "SYNCED"
                )
                session.closed -> row.copy(
                    type = "UNSCHEDULED",
                    status = "ABNORMAL",
                    resolutionStatus = AttendanceResolutionStatus.UNSCHEDULED.name,
                    scheduleDate = selected.schedule.date,
                    shiftId = selected.shift.id,
                    syncStatus = "SYNCED"
                )
                else -> {
                    val status = when {
                        type == AttendanceType.CHECK_IN.name && eventAt.isAfter(
                            selected.window.start.plusSeconds(selected.shift.lateGraceMinutes * 60L)
                        ) -> "LATE"
                        type == AttendanceType.CHECK_OUT.name && eventAt.isBefore(
                            selected.window.end.minusSeconds(selected.shift.earlyLeaveAllowedMinutes * 60L)
                        ) -> "EARLY_LEAVE"
                        else -> "NORMAL"
                    }
                    if (type == AttendanceType.CHECK_IN.name) {
                        session.openCheckInAt = eventAt
                    } else {
                        session.openCheckInAt = null
                        session.closed = true
                    }
                    session.lastAcceptedAt = eventAt
                    row.copy(
                        type = type,
                        status = status,
                        resolutionStatus = AttendanceResolutionStatus.ACCEPTED.name,
                        scheduleDate = selected.schedule.date,
                        shiftId = selected.shift.id,
                        syncStatus = "SYNCED"
                    )
                }
            }
        }
    return resolved
}

private const val DUPLICATE_WINDOW_SECONDS = 180L

private fun effectiveOpenEarlySeconds(shift: WorkShift): Long {
    val earlyMinutes = if (isStandardMorning(shift)) maxOf(120, shift.allowEarlyMinutes) else shift.allowEarlyMinutes
    return earlyMinutes * 60L
}
