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
    require(canAssignScheduleShift(shift)) { "Ca bổ sung 17:30–20:30 phải được nhân viên gửi đơn, không phân trước" }
    validateShift(shift)
}

fun validateShift(shift: WorkShift) {
    require(shift.name.isNotBlank()) { "Tên ca không được để trống" }
    require(shift.category in ShiftCategory.entries.map { it.name }) { "Loại ca chỉ gồm ca sáng, ca chiều hoặc ca bổ sung" }
    parseTime(shift.startTime, "Giờ bắt đầu")
    parseTime(shift.endTime, "Giờ kết thúc")
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
    if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) return WorkTimeSummary()

    val localIn = checkIn.atZone(zoneId).toLocalDateTime()
    val localOut = checkOut.atZone(zoneId).toLocalDateTime()
    val rawSeconds = Duration.between(checkIn, checkOut).seconds
    if (shift == null) return WorkTimeSummary(workedHours = roundHours(rawSeconds), dayWorked = true)

    validateShift(shift)
    val shiftDate = scheduleDate ?: localIn.toLocalDate()
    val shiftStart = shiftDate.atTime(parseTime(shift.startTime, "Giờ bắt đầu"))
    val shiftEnd = endOnShiftDate(shiftDate, shift)
    val breakSeconds = breakOverlapSeconds(localIn, localOut, shift, shiftDate)
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
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList()
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
    val approvedLeaveKeys = approvedRequests
        .filter { it.status == RequestStatus.APPROVED.name && it.type == "LEAVE" && activeEmployees.any { employee -> employee.id == it.employeeId } }
        .flatMap { request ->
            val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
            if (start == null || end == null) emptyList() else dates
                .filter { it in start..end }
                .map { date -> "${request.employeeId}_$date" }
        }
        .toSet()

    activeEmployees.forEach { employee ->
        dates.forEach { date ->
            val key = "${employee.id}_$date"
            val schedule = scheduleByKey[key]
            val adjustment = latestAdjustment(adjustments, employee.id, date)
            val rawPair = resolveAttendancePair(
                rows = attendanceByEmployee[employee.id].orEmpty(),
                scheduleDate = date,
                shift = schedule?.let { shifts[it.shiftId] },
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
                if (schedule != null && key !in approvedLeaveKeys) unauthorizedAbsenceDays++
                return@forEach
            }
            var dayWorkedSeconds = 0L
            val summary = calculateWorkTime(
                checkIn = pair.checkIn,
                checkOut = pair.checkOut,
                shift = schedule?.let { shifts[it.shiftId] },
                overtimeHours = schedule?.overtimeHours ?: 0,
                zoneId = zoneId,
                scheduleDate = pair.scheduleDate
            )
            dayWorkedSeconds += (summary.workedHours * 3600).roundToLong()
            totalOvertimeSeconds += (summary.overtimeHours * 3600).roundToLong()
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
