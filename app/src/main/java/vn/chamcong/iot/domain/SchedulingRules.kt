package vn.chamcong.iot.domain

import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkTimeSummary
import vn.chamcong.iot.model.WeeklyWorkSummary
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
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
    return (0L..6L).map(monday::plusDays)
}

fun validateOvertimeHours(hours: Int) {
    require(hours in 0..3) { "Số giờ tăng ca phải là 0, 1, 2 hoặc 3" }
}

fun validateWorkedHoursOverride(hours: Double?) {
    if (hours != null) require(hours.isFinite() && hours in 0.0..24.0) { "Giờ điều chỉnh phải từ 0 đến 24 giờ" }
}

fun validateShift(shift: WorkShift) {
    require(shift.name.isNotBlank()) { "Tên ca không được để trống" }
    require(shift.category in ShiftCategory.entries.map { it.name }) { "Loại ca chỉ gồm ca sáng, ca tối hoặc ca bổ sung" }
    parseTime(shift.startTime, "Giờ bắt đầu")
    parseTime(shift.endTime, "Giờ kết thúc")
    require(shift.allowEarlyMinutes >= 0) { "Thời gian cho phép chấm sớm không hợp lệ" }
    require(shift.lateGraceMinutes >= 0) { "Số phút cho phép đi trễ không hợp lệ" }
    require(shift.earlyLeaveAllowedMinutes >= 0) { "Quy định về sớm không hợp lệ" }
    require(shift.effectiveFrom.isNotBlank()) { "Ngày áp dụng không được để trống" }
    LocalDate.parse(shift.effectiveFrom, dateFormatter)
    shift.effectiveTo?.takeIf(String::isNotBlank)?.let { end ->
        require(!LocalDate.parse(end, dateFormatter).isBefore(LocalDate.parse(shift.effectiveFrom, dateFormatter))) {
            "Ngày kết thúc áp dụng phải từ ngày bắt đầu trở đi"
        }
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
    zoneId: ZoneId
): WorkTimeSummary {
    validateOvertimeHours(overtimeHours)
    if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) return WorkTimeSummary()

    val localIn = checkIn.atZone(zoneId).toLocalDateTime()
    val localOut = checkOut.atZone(zoneId).toLocalDateTime()
    val rawSeconds = Duration.between(checkIn, checkOut).seconds
    if (shift == null) return WorkTimeSummary(workedHours = roundHours(rawSeconds), dayWorked = true)

    validateShift(shift)
    val shiftStart = localIn.toLocalDate().atTime(parseTime(shift.startTime, "Giờ bắt đầu"))
    val shiftEnd = endOnShiftDate(localIn.toLocalDate(), shift)
    val breakSeconds = breakOverlapSeconds(localIn, localOut, shift, localIn.toLocalDate())
    val workedSeconds = (rawSeconds - breakSeconds).coerceAtLeast(0)
    val lateMinutes = Duration.between(shiftStart.plusMinutes(shift.lateGraceMinutes.toLong()), localIn)
        .toMinutes().coerceAtLeast(0).toInt()
    val earlyLeaveMinutes = Duration.between(localOut, shiftEnd.minusMinutes(shift.earlyLeaveAllowedMinutes.toLong()))
        .toMinutes().coerceAtLeast(0).toInt()
    val actualOvertimeSeconds = if (shift.countsOvertime && localOut.isAfter(shiftEnd)) {
        Duration.between(shiftEnd, localOut).seconds
    } else 0L
    val selectedOvertimeSeconds = overtimeHours * 60L * 60L
    val countedOvertimeSeconds = minOf(actualOvertimeSeconds, selectedOvertimeSeconds)

    return WorkTimeSummary(
        workedHours = roundHours(workedSeconds),
        overtimeHours = roundHours(countedOvertimeSeconds),
        lateMinutes = lateMinutes,
        earlyLeaveMinutes = earlyLeaveMinutes,
        dayWorked = workedSeconds > 0
    )
}

fun copyScheduleToNextWeek(
    source: List<WorkSchedule>,
    existingTargetIds: Set<String>
): List<WorkSchedule> = source.mapNotNull { schedule ->
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
    zoneId: ZoneId
): WeeklyWorkSummary {
    val monday = mondayOfWeek(weekStart)
    val dates = weekDates(monday)
    val activeEmployees = employees.filter(Employee::active)
    val scheduleByKey = schedules.associateBy { "${it.employeeId}_${it.date}" }
    val attendanceByKey = attendance.groupBy { "${it.employeeId}_${it.localDate(zoneId)}" }
    var totalWorkedSeconds = 0L
    var totalOvertimeSeconds = 0L
    var lateCount = 0
    var earlyLeaveCount = 0
    var workdays = 0
    var unauthorizedAbsenceDays = 0
    val dailyHours = dates.associateWith { 0.0 }.toMutableMap()
    val approvedLeaveKeys = approvedRequests
        .filter { it.status == RequestStatus.APPROVED.name && it.type == "LEAVE" }
        .flatMap { request ->
            val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
            if (start == null || end == null) emptyList() else dates
                .filter { it in start..end }
                .flatMap { date -> activeEmployees.map { "${it.id}_$date" } }
        }
        .toSet()

    activeEmployees.forEach { employee ->
        dates.forEach { date ->
            val key = "${employee.id}_$date"
            val schedule = scheduleByKey[key]
            val rows = attendanceByKey[key].orEmpty().sortedBy { it.timestamp.toDate().time }
            val pairs = attendancePairs(rows)
            validateWorkedHoursOverride(schedule?.workedHoursOverride)
            if (schedule?.workedHoursOverride != null) {
                val overrideSeconds = (schedule.workedHoursOverride * 3600).roundToLong()
                if (overrideSeconds > 0) {
                    workdays++
                    totalWorkedSeconds += overrideSeconds
                    dailyHours[date] = (dailyHours[date] ?: 0.0) + roundHours(overrideSeconds)
                }
                return@forEach
            }
            if (pairs.isEmpty()) {
                if (schedule != null && key !in approvedLeaveKeys) unauthorizedAbsenceDays++
                return@forEach
            }
            var dayWorkedSeconds = 0L
            pairs.forEach { (checkIn, checkOut) ->
                val summary = calculateWorkTime(
                    checkIn = checkIn,
                    checkOut = checkOut,
                    shift = schedule?.let { shifts[it.shiftId] },
                    overtimeHours = schedule?.overtimeHours ?: 0,
                    zoneId = zoneId
                )
                dayWorkedSeconds += (summary.workedHours * 3600).roundToLong()
                totalOvertimeSeconds += (summary.overtimeHours * 3600).roundToLong()
                if (summary.lateMinutes > 0) lateCount++
                if (summary.earlyLeaveMinutes > 0) earlyLeaveCount++
            }
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

private fun attendancePairs(rows: List<Attendance>): List<Pair<Instant, Instant>> {
    var checkIn: Instant? = null
    val pairs = mutableListOf<Pair<Instant, Instant>>()
    rows.forEach { row ->
        val instant = row.timestamp.toDate().toInstant()
        when (row.type) {
            AttendanceType.CHECK_IN.name -> if (checkIn == null) checkIn = instant
            AttendanceType.CHECK_OUT.name -> {
                val start = checkIn
                if (start != null && instant.isAfter(start)) {
                    pairs += start to instant
                    checkIn = null
                }
            }
        }
    }
    return pairs
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
    val breakStart = shiftDate.atTime(breakStartTime)
    val breakEnd = shiftDate.plusDays(if (breakEndTime.isAfter(breakStartTime)) 0 else 1).atTime(breakEndTime)
    val overlapStart = maxOf(localIn, breakStart)
    val overlapEnd = minOf(localOut, breakEnd)
    return if (overlapEnd.isAfter(overlapStart)) Duration.between(overlapStart, overlapEnd).seconds else 0L
}

private fun roundHours(seconds: Long): Double = (seconds / 3600.0 * 100).roundToLong() / 100.0

private fun Attendance.localDate(zoneId: ZoneId): LocalDate = timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()
