package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.EmployeeShiftSummary
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkTimeSummary
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

private fun Double.toSeconds(): Long = (this * 3600.0).roundToLong()

fun canAccessEmployee(provider: String, profile: UserProfile?): Boolean =
    provider != "anonymous" &&
        profile?.role == "EMPLOYEE" &&
        profile.active &&
        !profile.employeeId.isNullOrBlank()

fun canUseEmployeeShell(profile: UserProfile?, employeeId: String?): Boolean =
    canAccessEmployee("password", profile) && profile?.employeeId == employeeId && !employeeId.isNullOrBlank()

fun approvedLeaveShiftIdsForDate(
    employeeId: String,
    date: LocalDate,
    schedule: WorkSchedule?,
    shifts: List<WorkShift>,
    requests: List<LeaveRequest>
): Set<String> {
    if (schedule == null || schedule.employeeId != employeeId) return emptySet()
    val scheduledIds = scheduledShifts(schedule, shifts.associateBy(WorkShift::id)).map(WorkShift::id).toSet()
    if (scheduledIds.isEmpty()) return emptySet()
    return requests.asSequence()
        .filter { request ->
            request.employeeId == employeeId && request.type == RequestType.LEAVE.name &&
                request.status == RequestStatus.APPROVED.name && runCatching {
                    date in LocalDate.parse(request.startDate)..LocalDate.parse(request.endDate)
                }.getOrDefault(false)
        }
        .flatMap { request ->
            val scopedIds = request.leaveShiftsByDate?.get(date.toString())
                ?: if (request.leaveShiftsByDate == null) scheduledIds.toList() else emptyList()
            scopedIds.asSequence().filter(scheduledIds::contains)
        }
        .toSet()
}

fun employeeApprovedLeaveShifts(
    employeeId: String,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    requests: List<LeaveRequest>
): Map<LocalDate, Set<String>> = schedules.asSequence()
    .filter { it.employeeId == employeeId }
    .mapNotNull { schedule ->
        val date = runCatching { LocalDate.parse(schedule.date) }.getOrNull() ?: return@mapNotNull null
        val leaveShiftIds = approvedLeaveShiftIdsForDate(employeeId, date, schedule, shifts, requests)
        date.takeIf { leaveShiftIds.isNotEmpty() }?.let { it to leaveShiftIds }
    }
    .toMap()

fun employeeDaySummary(
    employeeId: String,
    date: LocalDate,
    attendance: List<Attendance>,
    schedule: WorkSchedule?,
    shift: WorkShift?,
    approvedLeave: Boolean,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now(),
    approvedLeaveShiftIds: Set<String> = emptySet()
): EmployeeDaySummary {
    val dayRows = attendance
        .asSequence()
        .filter { it.employeeId == employeeId && belongsToScheduleDate(it, date, shift, zoneId) }
        .sortedBy { it.timestamp.toDate().time }
        .toList()
    val adjustment = latestAdjustment(adjustments, employeeId, date)
    val rawPair = resolveAttendancePair(dayRows, date, shift, zoneId = zoneId)
    val pair = resolveAttendancePair(dayRows, date, shift, adjustments, zoneId)
    // Explicit employee lookup also covers adjustments with no raw scans.
    val checkIn = adjustment?.checkInAt ?: pair.checkIn
    val checkOut = adjustment?.checkOutAt ?: pair.checkOut
    val calculated = if (checkIn != null && checkOut != null) {
        calculateWorkTime(checkIn, checkOut, shift, schedule?.overtimeHours ?: 0, zoneId, date)
    } else WorkTimeSummary()
    // A missing checkout must not hide lateness from KPI/dashboard consumers.
    // There is no worked interval to calculate yet, but the check-in can still
    // be compared with the shift start and grace policy.
    val effectiveLateMinutes = if (checkIn != null && checkOut == null) {
        attendanceLateMinutes(checkIn, date, shift, zoneId)
    } else calculated.lateMinutes
    val shiftIsOnLeave = approvedLeave || (shift?.id != null && shift.id in approvedLeaveShiftIds)
    val status = when {
        shiftIsOnLeave -> EmployeeAttendanceStatus.LEAVE
        dayRows.any(::isAbnormalAttendance) -> EmployeeAttendanceStatus.ABNORMAL
        checkIn != null && checkOut != null && !checkOut.isAfter(checkIn) -> EmployeeAttendanceStatus.ABNORMAL
        checkIn == null -> EmployeeAttendanceStatus.MISSING_CHECK_IN
        isMissingCheckOut(pair.copy(checkIn = checkIn, checkOut = checkOut), date, shift, now, zoneId) -> EmployeeAttendanceStatus.MISSING_CHECK_OUT
        checkOut == null -> EmployeeAttendanceStatus.PRESENT
        effectiveLateMinutes > 0 && calculated.earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.ABNORMAL
        effectiveLateMinutes > 0 -> EmployeeAttendanceStatus.LATE
        calculated.earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.EARLY_LEAVE
        else -> EmployeeAttendanceStatus.ON_TIME
    }
    val workedHours = if (shiftIsOnLeave) 0.0 else (adjustment?.workedHoursOverride ?: schedule?.workedHoursOverride ?: calculated.workedHours)
    val overtimeHours = if (shiftIsOnLeave) 0.0 else calculated.overtimeHours
    val workedSeconds = when {
        shiftIsOnLeave -> 0L
        adjustment?.workedHoursOverride != null -> adjustment.workedHoursOverride.toSeconds()
        schedule?.workedHoursOverride != null -> schedule.workedHoursOverride.toSeconds()
        calculated.workedSeconds > 0L || calculated.workedHours == 0.0 -> calculated.workedSeconds
        else -> calculated.workedHours.toSeconds()
    }
    val overtimeSeconds = when {
        shiftIsOnLeave -> 0L
        calculated.overtimeSeconds > 0L || calculated.overtimeHours == 0.0 -> calculated.overtimeSeconds
        else -> calculated.overtimeHours.toSeconds()
    }
    val shiftSummary = shift?.let {
        EmployeeShiftSummary(
            shiftId = it.id,
            shiftName = it.name,
            shiftStartTime = it.startTime,
            shiftEndTime = it.endTime,
            rawCheckInAt = rawPair.checkIn,
            rawCheckOutAt = rawPair.checkOut,
            paidCheckInAt = calculated.paidCheckInAt,
            paidCheckOutAt = calculated.paidCheckOutAt,
            workedSeconds = workedSeconds,
            overtimeSeconds = overtimeSeconds,
            workedHours = workedHours,
            overtimeHours = overtimeHours,
            lateMinutes = if (shiftIsOnLeave) 0 else effectiveLateMinutes,
            earlyLeaveMinutes = if (shiftIsOnLeave) 0 else calculated.earlyLeaveMinutes,
            status = status
        )
    }
    return EmployeeDaySummary(
        date = date,
        shiftName = schedule?.shiftName.orEmpty(),
        shiftStartTime = shift?.startTime.orEmpty(),
        shiftEndTime = shift?.endTime.orEmpty(),
        checkIn = checkIn,
        checkOut = checkOut,
        workedHours = workedHours,
        overtimeHours = overtimeHours,
        workedSeconds = workedSeconds,
        overtimeSeconds = overtimeSeconds,
        lateMinutes = if (shiftIsOnLeave) 0 else effectiveLateMinutes,
        earlyLeaveMinutes = if (shiftIsOnLeave) 0 else calculated.earlyLeaveMinutes,
        status = status,
        shiftSummaries = listOfNotNull(shiftSummary)
    )
}

/** Summarizes a scheduled day across every selected shift, falling back to legacy shiftId. */
fun employeeDaySummaryForSchedule(
    employeeId: String,
    date: LocalDate,
    attendance: List<Attendance>,
    schedule: WorkSchedule?,
    shifts: List<WorkShift>,
    approvedLeave: Boolean,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now(),
    approvedLeaveShiftIds: Set<String> = emptySet()
): EmployeeDaySummary {
    if (schedule == null) {
        return employeeDaySummary(employeeId, date, attendance, null, null, approvedLeave, zoneId, adjustments, now)
    }
    val shiftsById = shifts.associateBy(WorkShift::id)
    val selectedShifts = scheduledShifts(schedule, shiftsById)
    if (selectedShifts.size <= 1) {
        return employeeDaySummary(
            employeeId, date, attendance, schedule, selectedShifts.firstOrNull(), approvedLeave, zoneId, adjustments, now,
            approvedLeaveShiftIds
        )
    }

    val assignedAttendance = assignAttendanceScheduleDates(attendance, listOf(schedule), shifts, zoneId)
    val adjustment = latestAdjustment(adjustments, employeeId, date)
    val adjustmentTime = adjustment?.checkInAt ?: adjustment?.checkOutAt
    val adjustmentShiftId = adjustmentTime?.let { instant ->
        nearestScheduledShift(
            date,
            selectedShifts,
            instant,
            if (adjustment?.checkInAt != null) AttendanceType.CHECK_IN.name else AttendanceType.CHECK_OUT.name,
            zoneId
        )?.id
    }
    val summaries = selectedShifts.mapIndexed { index, shift ->
        val perShiftSchedule = schedule.copy(
            shiftId = shift.id,
            shiftIds = listOf(shift.id),
            shiftName = shift.name,
            overtimeHours = if (index == 0) schedule.overtimeHours else 0,
            workedHoursOverride = null
        )
        val shiftAdjustments = if (shift.id == adjustmentShiftId) listOfNotNull(adjustment) else emptyList()
        employeeDaySummary(
            employeeId = employeeId,
            date = date,
            attendance = assignedAttendance,
            schedule = perShiftSchedule,
            shift = shift,
            approvedLeave = approvedLeave,
            zoneId = zoneId,
            adjustments = shiftAdjustments,
            now = now,
            approvedLeaveShiftIds = approvedLeaveShiftIds
        )
    }
    val checkIn = adjustment?.checkInAt ?: summaries.mapNotNull(EmployeeDaySummary::checkIn).minOrNull()
    val checkOut = adjustment?.checkOutAt ?: summaries.mapNotNull(EmployeeDaySummary::checkOut).maxOrNull()
    val anyShiftOnLeave = approvedLeave || approvedLeaveShiftIds.any { id -> selectedShifts.any { it.id == id } }
    val workedHoursOverride = if (anyShiftOnLeave) null else adjustment?.workedHoursOverride ?: schedule.workedHoursOverride
    val workedHours = workedHoursOverride ?: summaries.sumOf(EmployeeDaySummary::workedHours)
    val workedSeconds = workedHoursOverride?.toSeconds() ?: summaries.sumOf(EmployeeDaySummary::workedSeconds)
    val overtimeSeconds = summaries.sumOf(EmployeeDaySummary::overtimeSeconds)
    val lateMinutes = summaries.sumOf(EmployeeDaySummary::lateMinutes)
    val earlyLeaveMinutes = summaries.sumOf(EmployeeDaySummary::earlyLeaveMinutes)
    val workingSummaries = summaries.filterNot { it.status == EmployeeAttendanceStatus.LEAVE }
    val status = when {
        workingSummaries.isEmpty() -> EmployeeAttendanceStatus.LEAVE
        workingSummaries.any { it.status == EmployeeAttendanceStatus.ABNORMAL } -> EmployeeAttendanceStatus.ABNORMAL
        workingSummaries.any { it.status == EmployeeAttendanceStatus.MISSING_CHECK_OUT } -> EmployeeAttendanceStatus.MISSING_CHECK_OUT
        workingSummaries.any { it.status == EmployeeAttendanceStatus.MISSING_CHECK_IN } -> EmployeeAttendanceStatus.MISSING_CHECK_IN
        workingSummaries.any { it.status == EmployeeAttendanceStatus.PRESENT } -> EmployeeAttendanceStatus.PRESENT
        lateMinutes > 0 && earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.ABNORMAL
        lateMinutes > 0 -> EmployeeAttendanceStatus.LATE
        earlyLeaveMinutes > 0 -> EmployeeAttendanceStatus.EARLY_LEAVE
        else -> EmployeeAttendanceStatus.ON_TIME
    }
    return EmployeeDaySummary(
        date = date,
        shiftName = selectedShifts.joinToString(" / ", transform = WorkShift::name),
        shiftStartTime = selectedShifts.first().startTime,
        shiftEndTime = selectedShifts.last().endTime,
        checkIn = checkIn,
        checkOut = checkOut,
        workedHours = workedHours,
        overtimeHours = summaries.sumOf(EmployeeDaySummary::overtimeHours),
        workedSeconds = workedSeconds,
        overtimeSeconds = overtimeSeconds,
        lateMinutes = lateMinutes,
        earlyLeaveMinutes = earlyLeaveMinutes,
        status = status,
        shiftSummaries = summaries.flatMap(EmployeeDaySummary::shiftSummaries)
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
    overtimeRequests: List<OvertimeRequest> = emptyList(),
    now: Instant = Instant.now(),
    approvedLeaveShiftsByDate: Map<LocalDate, Set<String>> = emptyMap(),
    leaveRequests: List<LeaveRequest> = emptyList()
): List<EmployeeDaySummary> {
    val firstDay = month.withDayOfMonth(1)
    val assignedAttendance = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    val scheduleByDate = schedules.filter { it.employeeId == employeeId }.associateBy { it.date }
    val requestedLeaveShifts = employeeApprovedLeaveShifts(employeeId, schedules, shifts, leaveRequests)
    val legacyLeavePeriods = leaveRequests.mapNotNull { request ->
        if (request.employeeId != employeeId || request.type != RequestType.LEAVE.name ||
            request.status != RequestStatus.APPROVED.name || request.leaveShiftsByDate != null
        ) return@mapNotNull null
        runCatching { LocalDate.parse(request.startDate)..LocalDate.parse(request.endDate) }.getOrNull()
    }
    return (0 until firstDay.lengthOfMonth()).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        val schedule = scheduleByDate[date.toString()]
        employeeDaySummaryWithRequests(
            employeeId = employeeId,
            date = date,
            attendance = assignedAttendance,
            schedule = schedule,
            shifts = shifts,
            approvedLeave = legacyLeavePeriods.any { date in it } || (date in approvedLeaveDates &&
                schedule?.let { scheduledShifts(it, shifts.associateBy(WorkShift::id)).isNotEmpty() } == true),
            zoneId = zoneId,
            adjustments = adjustments,
            now = now,
            approvedLeaveShiftIds = approvedLeaveShiftsByDate[date].orEmpty() + requestedLeaveShifts[date].orEmpty(),
            overtimeRequests = overtimeRequests
        )
    }
}

/** Shared timesheet/report/payroll path: leave affects main shifts, not an independent approved overtime pair. */
fun employeeDaySummaryWithRequests(
    employeeId: String,
    date: LocalDate,
    attendance: List<Attendance>,
    schedule: WorkSchedule?,
    shifts: List<WorkShift>,
    approvedLeave: Boolean,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now(),
    approvedLeaveShiftIds: Set<String> = emptySet(),
    overtimeRequests: List<OvertimeRequest> = emptyList()
): EmployeeDaySummary {
    val mainShiftIds = schedule?.let(::scheduledShiftIds).orEmpty().filter { it != SUPPLEMENTARY_SHIFT_ID }
    val mainSchedule = schedule?.takeIf { mainShiftIds.isNotEmpty() }?.copy(
        shiftId = mainShiftIds.first(), shiftIds = mainShiftIds
    )
    // Supplementary punches must never become an unscheduled regular pair or
    // borrow a missing main checkout, even if no approved request is present.
    val main = employeeDaySummaryForSchedule(
        employeeId, date, attendance.filter { it.shiftId != SUPPLEMENTARY_SHIFT_ID },
        mainSchedule, shifts, approvedLeave, zoneId, adjustments, now, approvedLeaveShiftIds
    )
    val hasWorkedHoursOverride = latestAdjustment(adjustments, employeeId, date)?.workedHoursOverride != null ||
        mainSchedule?.workedHoursOverride != null
    val shiftsById = shifts.associateBy(WorkShift::id)
    val normalizedMainShifts = main.shiftSummaries.map { summary ->
        val shift = shiftsById[summary.shiftId]
        val standardSplitShift = shift?.let {
            (it.category == "MORNING" && it.startTime == "08:00" && it.endTime == "12:00") ||
                (it.category == "EVENING" && it.startTime == "13:00" && it.endTime == "17:00")
        } == true
        // calculateWorkTime keeps the legacy continuous custom-shift result.
        // Shared consumer summaries expose two disjoint portions instead.
        val overlap = if (!hasWorkedHoursOverride && shift?.countsOvertime == true && !standardSplitShift) {
            summary.overtimeSeconds.coerceAtMost(summary.workedSeconds)
        } else 0L
        summary.copy(
            workedSeconds = summary.workedSeconds - overlap,
            workedHours = if (overlap > 0L) roundHours(summary.workedSeconds - overlap) else summary.workedHours
        )
    }
    val overlappingSeconds = main.shiftSummaries.sumOf(EmployeeShiftSummary::workedSeconds) -
        normalizedMainShifts.sumOf(EmployeeShiftSummary::workedSeconds)
    val daily = main.copy(
        workedSeconds = (main.workedSeconds - overlappingSeconds).coerceAtLeast(0L),
        workedHours = if (overlappingSeconds > 0L) roundHours((main.workedSeconds - overlappingSeconds).coerceAtLeast(0L)) else main.workedHours,
        shiftSummaries = normalizedMainShifts
    )
    val overtimeShiftSummaries = overtimeRequests.mapNotNull { request ->
        val overtime = approvedOvertimeSummary(employeeId, date, attendance, request, zoneId)
            ?.takeIf { it.overtimeSeconds > 0L } ?: return@mapNotNull null
        EmployeeShiftSummary(
            shiftId = SUPPLEMENTARY_SHIFT_ID,
            shiftName = "Tăng ca",
            shiftStartTime = request.startTime,
            shiftEndTime = request.endTime,
            rawCheckInAt = overtime.rawCheckInAt,
            rawCheckOutAt = overtime.rawCheckOutAt,
            paidCheckInAt = overtime.paidCheckInAt,
            paidCheckOutAt = overtime.paidCheckOutAt,
            // The supplementary interval is exposed only as overtime, so
            // consumers can add regular and overtime without counting twice.
            overtimeSeconds = overtime.overtimeSeconds,
            overtimeHours = overtime.overtimeHours,
            status = EmployeeAttendanceStatus.ON_TIME
        )
    }.distinctBy { it.shiftId }
    val overtimeOnly = mainSchedule == null && daily.checkIn == null && overtimeShiftSummaries.isNotEmpty()
    return daily.copy(
        shiftName = if (overtimeOnly) overtimeShiftSummaries.joinToString(" / ") { it.shiftName } else daily.shiftName,
        shiftStartTime = if (overtimeOnly) overtimeShiftSummaries.first().shiftStartTime else daily.shiftStartTime,
        shiftEndTime = if (overtimeOnly) overtimeShiftSummaries.last().shiftEndTime else daily.shiftEndTime,
        checkIn = if (overtimeOnly) overtimeShiftSummaries.first().rawCheckInAt else daily.checkIn,
        checkOut = if (overtimeOnly) overtimeShiftSummaries.last().rawCheckOutAt else daily.checkOut,
        status = if (overtimeOnly && !approvedLeave) EmployeeAttendanceStatus.ON_TIME else daily.status,
        overtimeHours = daily.overtimeHours + overtimeShiftSummaries.sumOf(EmployeeShiftSummary::overtimeHours),
        overtimeSeconds = daily.overtimeSeconds + overtimeShiftSummaries.sumOf(EmployeeShiftSummary::overtimeSeconds),
        shiftSummaries = daily.shiftSummaries + overtimeShiftSummaries
    )
}

fun employeeRequestDraft(
    employee: Employee,
    type: RequestType,
    startDate: String,
    endDate: String,
    reason: String,
    proposedCheckIn: String? = null,
    proposedCheckOut: String? = null,
    requestedShiftId: String? = null,
    requestedShiftName: String? = null,
    leaveShiftsByDate: Map<String, List<String>>? = null
): LeaveRequest {
    val request = LeaveRequest(
        employeeId = employee.id,
        employeeName = employee.fullName,
        department = employee.department,
        type = type.name,
        startDate = startDate,
        endDate = endDate,
        reason = reason,
        proposedCheckIn = proposedCheckIn?.trim()?.ifBlank { null },
        proposedCheckOut = proposedCheckOut?.trim()?.ifBlank { null },
        requestedShiftId = requestedShiftId?.trim()?.ifBlank { null },
        requestedShiftName = requestedShiftName?.trim()?.ifBlank { null },
        leaveShiftsByDate = leaveShiftsByDate,
        status = RequestStatus.PENDING.name,
        reviewerId = null,
        reviewerName = null,
        reviewedAt = null,
        reviewNote = null
    )
    validateRequest(request)
    return request
}
