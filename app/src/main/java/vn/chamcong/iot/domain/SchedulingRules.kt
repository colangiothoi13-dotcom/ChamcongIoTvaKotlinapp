package vn.chamcong.iot.domain

import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkTimeSummary
import vn.chamcong.iot.model.WeeklyWorkSummary
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.OvertimeRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.roundToLong

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun mondayOfWeek(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun weekDates(weekStart: LocalDate): List<LocalDate> {
    val monday = mondayOfWeek(weekStart)
    return (0L..5L).map(monday::plusDays)
}

fun validateOvertimeHours(hours: Int) {
    require(hours in 0..4) { "Số giờ tăng ca phải từ 0 đến 4" }
}

fun validateWorkedHoursOverride(hours: Double?) {
    if (hours != null) require(hours.isFinite() && hours in 0.0..24.0) { "Giờ điều chỉnh phải từ 0 đến 24 giờ" }
}

fun validateAttendanceAdjustment(adjustment: AttendanceAdjustment) {
    require(adjustment.employeeId.isNotBlank()) { "Mã nhân viên không được để trống" }
    require(adjustment.employeeName.isNotBlank()) { "Tên nhân viên không được để trống" }
    require(adjustment.scheduleDate.isNotBlank()) { "Ngày ca không được để trống" }
    runCatching { LocalDate.parse(adjustment.scheduleDate, dateFormatter) }
        .getOrElse { throw IllegalArgumentException("Ngày ca phải có dạng ISO yyyy-MM-dd") }
    require(adjustment.reason.isNotBlank()) { "Lý do không được để trống" }
    require(adjustment.actorId.isNotBlank()) { "Mã người thực hiện không được để trống" }
    require(adjustment.actorName.isNotBlank()) { "Tên người thực hiện không được để trống" }
    require(
        adjustment.checkInAt != null || adjustment.checkOutAt != null || adjustment.workedHoursOverride != null
    ) { "Điều chỉnh phải có ít nhất một giá trị" }
    if (adjustment.checkInAt != null && adjustment.checkOutAt != null) {
        require(adjustment.checkOutAt.isAfter(adjustment.checkInAt)) {
            "Giờ ra phải sau giờ vào"
        }
    }
    validateWorkedHoursOverride(adjustment.workedHoursOverride)
}

/** Only main shifts may be assigned; supplementary shifts remain readable for legacy data. */
fun canAssignScheduleShift(shift: WorkShift): Boolean =
    shift.id != SUPPLEMENTARY_SHIFT_ID && shift.category != ShiftCategory.SUPPLEMENTARY.name

fun validateScheduleShift(shift: WorkShift) {
    require(canAssignScheduleShift(shift)) { "Ca bổ sung 18:00–22:00 phải được nhân viên gửi đơn, không phân trước" }
    validateShift(shift)
}

fun validateShift(shift: WorkShift, allowLegacyOverFourHours: Boolean = false) {
    require(shift.name.isNotBlank()) { "Tên ca không được để trống" }
    require(shift.category in ShiftCategory.entries.map { it.name }) { "Loại ca chỉ gồm ca sáng, ca chiều hoặc ca bổ sung" }
    val startTime = parseTime(shift.startTime, "Giờ bắt đầu")
    val endTime = parseTime(shift.endTime, "Giờ kết thúc")
    require(startTime.isBefore(endTime)) { "Giờ kết thúc phải sau giờ bắt đầu; ca không được qua ngày" }
    require(shift.allowEarlyMinutes >= 0) { "Thời gian cho phép chấm sớm không hợp lệ" }
    require(shift.lateGraceMinutes >= 0) { "Số phút cho phép đi trễ không hợp lệ" }
    require(shift.earlyLeaveAllowedMinutes >= 0) { "Quy định về sớm không hợp lệ" }
    require(shift.missingCheckOutGraceMinutes >= 0) { "Thời gian chờ thiếu chấm ra không hợp lệ" }
    require(shift.effectiveFrom.isNotBlank()) { "Ngày áp dụng không được để trống" }
    LocalDate.parse(shift.effectiveFrom, dateFormatter)
    shift.effectiveTo?.takeIf(String::isNotBlank)?.let { end ->
        require(!LocalDate.parse(end, dateFormatter).isBefore(LocalDate.parse(shift.effectiveFrom, dateFormatter))) {
            "Ngày kết thúc áp dụng phải từ ngày bắt đầu trở đi"
        }
    }
    require(allowLegacyOverFourHours || Duration.between(startTime, endTime) <= Duration.ofHours(4)) {
        "Thời lượng ca không được vượt quá 4 giờ"
    }
    val hasBreakStart = shift.breakStartTime != null
    val hasBreakEnd = shift.breakEndTime != null
    require(hasBreakStart == hasBreakEnd) { "Phải nhập đủ giờ bắt đầu và kết thúc nghỉ" }
    if (hasBreakStart) {
        parseTime(shift.breakStartTime!!, "Giờ bắt đầu nghỉ")
        parseTime(shift.breakEndTime!!, "Giờ kết thúc nghỉ")
    }
}

fun calculateWorkTime(
    checkIn: Instant?,
    checkOut: Instant?,
    shift: WorkShift?,
    overtimeHours: Int,
    zoneId: ZoneId,
    scheduleDate: LocalDate? = null
): WorkTimeSummary {
    validateOvertimeHours(overtimeHours)
    if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
        return WorkTimeSummary(rawCheckInAt = checkIn, rawCheckOutAt = checkOut)
    }

    val localIn = checkIn.atZone(zoneId).toLocalDateTime()
    val localOut = checkOut.atZone(zoneId).toLocalDateTime()
    val rawSeconds = Duration.between(checkIn, checkOut).seconds
    if (shift == null) return WorkTimeSummary(
        rawCheckInAt = checkIn,
        rawCheckOutAt = checkOut,
        paidCheckInAt = checkIn,
        paidCheckOutAt = checkOut,
        workedSeconds = rawSeconds,
        workedHours = roundHours(rawSeconds),
        dayWorked = true
    )

    // Keep persisted overlong shifts readable in reports; the default validation used when
    // creating or assigning a shift still enforces the four-hour maximum.
    validateShift(shift, allowLegacyOverFourHours = true)
    val shiftDate = scheduleDate ?: localIn.toLocalDate()
    val shiftStart = shiftDate.atTime(parseTime(shift.startTime, "Giờ bắt đầu"))
    val shiftEnd = endOnShiftDate(shiftDate, shift)
    val paidGrace = Duration.ofMinutes(10).plusSeconds(59)
    val boundedIn = maxOf(localIn, shiftStart)
    val paidIn = if (
        !localIn.isBefore(shiftStart) && Duration.between(shiftStart, localIn) <= paidGrace
    ) shiftStart else boundedIn
    val boundedOut = minOf(localOut, shiftEnd)
    val paidOut = if (
        !localOut.isAfter(shiftEnd) && Duration.between(localOut, shiftEnd) <= paidGrace
    ) shiftEnd else boundedOut
    val paidSeconds = Duration.between(paidIn, paidOut).seconds.coerceAtLeast(0)
    val breakSeconds = breakOverlapSeconds(paidIn, paidOut, shift, shiftDate)
    val workedSeconds = (paidSeconds - breakSeconds).coerceAtLeast(0)
    val lateMinutes = Duration.between(shiftStart.plusMinutes(shift.lateGraceMinutes.toLong()), localIn)
        .toMinutes().coerceAtLeast(0).toInt()
    val earlyLeaveMinutes = Duration.between(localOut, shiftEnd.minusMinutes(shift.earlyLeaveAllowedMinutes.toLong()))
        .toMinutes().coerceAtLeast(0).toInt()
    val actualOvertimeSeconds = if (shift.countsOvertime && localOut.isAfter(shiftEnd)) {
        Duration.between(shiftEnd, localOut).seconds
    } else 0L
    val selectedOvertimeSeconds = overtimeHours * 60L * 60L
    val isOvertimeShift = shift.countsOvertime || shift.category == ShiftCategory.SUPPLEMENTARY.name
    val countedOvertimeSeconds = if (isOvertimeShift) {
        workedSeconds
    } else {
        minOf(actualOvertimeSeconds, selectedOvertimeSeconds)
    }
    val regularWorkedSeconds = if (isOvertimeShift) 0L else workedSeconds
    val paidCheckInAt = if (localIn.isAfter(shiftStart.plus(paidGrace))) {
        checkIn
    } else {
        paidIn.atZone(zoneId).toInstant()
    }
    val paidCheckOutAt = if (localOut.isBefore(shiftEnd.minus(paidGrace))) {
        checkOut
    } else {
        paidOut.atZone(zoneId).toInstant()
    }

    return WorkTimeSummary(
        rawCheckInAt = checkIn,
        rawCheckOutAt = checkOut,
        paidCheckInAt = paidCheckInAt,
        paidCheckOutAt = paidCheckOutAt,
        workedSeconds = regularWorkedSeconds,
        overtimeSeconds = countedOvertimeSeconds,
        workedHours = roundHours(regularWorkedSeconds),
        overtimeHours = roundHours(countedOvertimeSeconds),
        lateMinutes = lateMinutes,
        earlyLeaveMinutes = earlyLeaveMinutes,
        dayWorked = workedSeconds > 0
    )
}

fun copyScheduleToNextWeek(
    source: List<WorkSchedule>,
    existingTargetIds: Set<String>
): List<WorkSchedule> = source.filter { schedule ->
    LocalDate.parse(schedule.date, dateFormatter).dayOfWeek != DayOfWeek.SUNDAY
}.mapNotNull { schedule ->
    val targetDate = LocalDate.parse(schedule.date, dateFormatter).plusWeeks(1).toString()
    val targetId = "${schedule.employeeId}_$targetDate"
    if (targetId in existingTargetIds) null else schedule.copy(
        id = targetId,
        date = targetDate,
        source = "COPY_WEEK"
    )
}

fun summarizeWeeklyWork(
    employees: List<Employee>,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: Map<String, WorkShift>,
    approvedRequests: List<LeaveRequest>,
    weekStart: LocalDate,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    overtimeRequests: List<OvertimeRequest> = emptyList()
): WeeklyWorkSummary {
    val monday = mondayOfWeek(weekStart)
    val dates = weekDates(monday)
    val activeEmployees = employees.filter(Employee::active)
    val scheduleByKey = schedules.associateBy { "${it.employeeId}_${it.date}" }
    val attendanceByEmployee = assignAttendanceScheduleDates(attendance, schedules, shifts.values.toList(), zoneId)
        .groupBy(Attendance::employeeId)
    var totalWorkedSeconds = 0L
    var totalOvertimeSeconds = 0L
    var lateCount = 0
    var earlyLeaveCount = 0
    var workdays = 0
    var unauthorizedAbsenceDays = 0
    val dailyHours = dates.associateWith { 0.0 }.toMutableMap()
    val legacyApprovedLeaveKeys = approvedRequests
        .filter { it.status == RequestStatus.APPROVED.name && it.type == "LEAVE" && it.leaveShiftsByDate == null && activeEmployees.any { employee -> employee.id == it.employeeId } }
        .flatMap { request ->
            val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
            if (start == null || end == null) emptyList() else dates.filter { it in start..end }
                .map { date -> "${request.employeeId}_$date" }
        }.toSet()
    val approvedLeaveKeys = activeEmployees.flatMap { employee ->
        dates.mapNotNull { date ->
            val key = "${employee.id}_$date"
            val schedule = scheduleByKey[key] ?: return@mapNotNull null
            key.takeIf { approvedLeaveShiftIdsForDate(employee.id, date, schedule, shifts.values.toList(), approvedRequests).isNotEmpty() }
        }
    }.toSet()

    activeEmployees.forEach { employee ->
        dates.forEach { date ->
            val key = "${employee.id}_$date"
            val schedule = scheduleByKey[key]
            val approvedOvertimeSeconds = overtimeRequests.sumOf { request ->
                approvedOvertimeSummary(employee.id, date, attendance, request, zoneId)?.overtimeSeconds ?: 0L
            }
            totalOvertimeSeconds += approvedOvertimeSeconds
            val adjustment = latestAdjustment(adjustments, employee.id, date)
            val scheduleShifts = schedule?.let { scheduledShifts(it, shifts) }.orEmpty()
            val leaveShiftIds = approvedLeaveShiftIdsForDate(employee.id, date, schedule, shifts.values.toList(), approvedRequests)
            val fullDayLegacyLeave = key in legacyApprovedLeaveKeys
            if (schedule != null && scheduleShifts.size > 1) {
                val daily = employeeDaySummaryForSchedule(
                    employeeId = employee.id,
                    date = date,
                    attendance = attendanceByEmployee[employee.id].orEmpty(),
                    schedule = schedule,
                    shifts = scheduleShifts,
                    approvedLeave = fullDayLegacyLeave,
                    zoneId = zoneId,
                    adjustments = adjustments,
                    approvedLeaveShiftIds = leaveShiftIds
                )
                val workedHoursOverride = if (fullDayLegacyLeave || leaveShiftIds.isNotEmpty()) null
                    else adjustment?.workedHoursOverride ?: schedule.workedHoursOverride
                if (workedHoursOverride != null) {
                    val overrideSeconds = (workedHoursOverride * 3600).roundToLong()
                    if (overrideSeconds > 0) {
                        workdays++
                        totalWorkedSeconds += overrideSeconds
                        dailyHours[date] = (dailyHours[date] ?: 0.0) + roundHours(overrideSeconds)
                    }
                    return@forEach
                }
                if (daily.status != vn.chamcong.iot.model.EmployeeAttendanceStatus.LEAVE &&
                    (daily.checkIn == null || daily.checkOut == null ||
                        daily.status == vn.chamcong.iot.model.EmployeeAttendanceStatus.MISSING_CHECK_IN ||
                        daily.status == vn.chamcong.iot.model.EmployeeAttendanceStatus.MISSING_CHECK_OUT)
                ) {
                    unauthorizedAbsenceDays++
                }
                val workedSeconds = daily.workedSeconds
                totalOvertimeSeconds += daily.overtimeSeconds
                if (daily.lateMinutes > 0) lateCount++
                if (daily.earlyLeaveMinutes > 0) earlyLeaveCount++
                if (workedSeconds > 0) {
                    workdays++
                    totalWorkedSeconds += workedSeconds
                    dailyHours[date] = (dailyHours[date] ?: 0.0) + roundHours(workedSeconds)
                }
                return@forEach
            }
            if (schedule != null && (fullDayLegacyLeave || scheduleShifts.firstOrNull()?.id?.let(leaveShiftIds::contains) == true)) {
                return@forEach
            }
            val rawPair = resolveAttendancePair(
                rows = attendanceByEmployee[employee.id].orEmpty(),
                scheduleDate = date,
                shift = scheduleShifts.firstOrNull(),
                adjustments = adjustments,
                zoneId = zoneId
            )
            // An employee can have an adjustment before their first raw scan exists.
            val pair = rawPair.copy(
                checkIn = adjustment?.checkInAt ?: rawPair.checkIn,
                checkOut = adjustment?.checkOutAt ?: rawPair.checkOut,
                adjustment = adjustment
            )
            val workedHoursOverride = adjustment?.workedHoursOverride ?: schedule?.workedHoursOverride
            validateWorkedHoursOverride(workedHoursOverride)
            if (workedHoursOverride != null) {
                val overrideSeconds = (workedHoursOverride * 3600).roundToLong()
                if (overrideSeconds > 0) {
                    workdays++
                    totalWorkedSeconds += overrideSeconds
                    dailyHours[date] = (dailyHours[date] ?: 0.0) + roundHours(overrideSeconds)
                }
                return@forEach
            }
            if (pair.checkIn == null || pair.checkOut == null) {
                if (schedule != null) unauthorizedAbsenceDays++
                return@forEach
            }
            var dayWorkedSeconds = 0L
            val summary = calculateWorkTime(
                checkIn = pair.checkIn,
                checkOut = pair.checkOut,
                shift = scheduleShifts.firstOrNull(),
                overtimeHours = schedule?.overtimeHours ?: 0,
                zoneId = zoneId,
                scheduleDate = pair.scheduleDate
            )
            dayWorkedSeconds += summary.workedSeconds
            totalOvertimeSeconds += summary.overtimeSeconds
            if (summary.lateMinutes > 0) lateCount++
            if (summary.earlyLeaveMinutes > 0) earlyLeaveCount++
            if (dayWorkedSeconds > 0) {
                workdays++
                totalWorkedSeconds += dayWorkedSeconds
                dailyHours[date] = (dailyHours[date] ?: 0.0) + roundHours(dayWorkedSeconds)
            }
        }
    }

    return WeeklyWorkSummary(
        weekStart = monday,
        totalWorkedHours = roundHours(totalWorkedSeconds),
        totalOvertimeHours = roundHours(totalOvertimeSeconds),
        workdays = workdays,
        lateCount = lateCount,
        earlyLeaveCount = earlyLeaveCount,
        approvedLeaveDays = approvedLeaveKeys.size,
        unauthorizedAbsenceDays = unauthorizedAbsenceDays,
        dailyWorkedHours = dailyHours
    )
}

private fun parseTime(value: String, label: String): LocalTime = runCatching {
    LocalTime.parse(value, timeFormatter)
}.getOrElse { throw IllegalArgumentException("$label phải có dạng HH:mm") }

private fun endOnShiftDate(date: LocalDate, shift: WorkShift): LocalDateTime {
    val start = parseTime(shift.startTime, "Giờ bắt đầu")
    val end = parseTime(shift.endTime, "Giờ kết thúc")
    return date.plusDays(if (end.isAfter(start)) 0 else 1).atTime(end)
}

private fun breakOverlapSeconds(
    localIn: LocalDateTime,
    localOut: LocalDateTime,
    shift: WorkShift,
    shiftDate: LocalDate
): Long {
    val breakStartText = shift.breakStartTime ?: return 0L
    val breakEndText = shift.breakEndTime ?: return 0L
    val breakStartTime = parseTime(breakStartText, "Giờ bắt đầu nghỉ")
    val breakEndTime = parseTime(breakEndText, "Giờ kết thúc nghỉ")
    val shiftStartTime = parseTime(shift.startTime, "Giờ bắt đầu")
    val shiftStart = shiftDate.atTime(shiftStartTime)
    val shiftEnd = endOnShiftDate(shiftDate, shift)
    val breakDate = if (shiftEnd.toLocalDate().isAfter(shiftDate) && breakStartTime.isBefore(shiftStartTime)) shiftDate.plusDays(1) else shiftDate
    val breakStart = breakDate.atTime(breakStartTime)
    val breakEnd = breakDate.plusDays(if (breakEndTime.isAfter(breakStartTime)) 0 else 1).atTime(breakEndTime)
    val overlapStart = maxOf(localIn, breakStart, shiftStart)
    val overlapEnd = minOf(localOut, breakEnd, shiftEnd)
    return if (overlapEnd.isAfter(overlapStart)) Duration.between(overlapStart, overlapEnd).seconds else 0L
}

private fun roundHours(seconds: Long): Double = (seconds / 3600.0 * 100).roundToLong() / 100.0
