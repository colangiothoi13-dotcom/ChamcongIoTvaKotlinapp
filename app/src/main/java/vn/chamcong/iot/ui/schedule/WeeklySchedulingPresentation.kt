package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.domain.employeeDaySummary
import vn.chamcong.iot.domain.shiftWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime

fun unavailableWeeklyEmployeeIds(employees: List<Employee>, selectedIds: Set<String>): Set<String> =
    selectedIds - employees.filter { it.active && it.id.isNotBlank() }.map { it.id }.toSet()

fun scheduleShiftLabel(shift: WorkShift): String {
    if (shift.category != ShiftCategory.SUPPLEMENTARY.name) return shift.name
    val overnight = runCatching { LocalTime.parse(shift.endTime).isBefore(LocalTime.parse(shift.startTime)) }.getOrDefault(false)
    return "${shift.name} • ${shift.startTime}–${shift.endTime}" + if (overnight) " (ngày hôm sau)" else ""
}

enum class ScheduleStatusTone { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

data class ScheduleShiftStatus(
    val label: String,
    val tone: ScheduleStatusTone
)

private fun formatShiftDuration(from: Instant, to: Instant): String {
    val totalMinutes = Duration.between(from, to).toMinutes().coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours giờ $minutes phút"
        hours > 0 -> "$hours giờ"
        else -> "$minutes phút"
    }
}

fun scheduleShiftStatus(
    employeeId: String,
    date: LocalDate,
    shift: WorkShift,
    attendance: List<Attendance>,
    zoneId: ZoneId,
    now: Instant
): ScheduleShiftStatus {
    val schedule = WorkSchedule(
        employeeId = employeeId,
        date = date.toString(),
        shiftId = shift.id,
        shiftIds = listOf(shift.id),
        shiftName = shift.name
    )
    val summary = employeeDaySummary(
        employeeId = employeeId,
        date = date,
        attendance = attendance,
        schedule = schedule,
        shift = shift,
        approvedLeave = false,
        zoneId = zoneId,
        now = now
    )
    val window = shiftWindow(date, shift, zoneId)
    val checkoutGraceMinutes = when {
        shift.category == "MORNING" && shift.startTime == "08:00" && shift.endTime == "12:00" -> 30
        shift.category == "EVENING" && shift.startTime == "13:00" && shift.endTime == "17:00" -> 30
        else -> shift.missingCheckOutGraceMinutes
    }
    val deadline = window.end.plusSeconds(checkoutGraceMinutes * 60L)
    val checkIn = summary.checkIn
    val checkOut = summary.checkOut
    if (checkIn != null && checkOut != null) {
        val details = buildList {
            add("Đã làm ${formatShiftDuration(checkIn, checkOut)}")
            if (summary.lateMinutes > 0) add("Đi trễ ${summary.lateMinutes} phút")
            if (summary.earlyLeaveMinutes > 0) add("Về sớm ${summary.earlyLeaveMinutes} phút")
            if (summary.overtimeHours > 0.0) add("Tăng ca ${"%.1f".format(java.util.Locale.US, summary.overtimeHours)} giờ")
        }
        return ScheduleShiftStatus(
            label = "Đã kết thúc ca" + details.takeIf { it.isNotEmpty() }?.joinToString(" • ", prefix = " • ").orEmpty(),
            tone = if (summary.status == EmployeeAttendanceStatus.ABNORMAL) ScheduleStatusTone.ERROR else ScheduleStatusTone.SUCCESS
        )
    }
    if (summary.checkIn != null) {
        return if (now.isAfter(deadline)) {
            ScheduleShiftStatus("Thiếu chấm ra • Chưa tính giờ", ScheduleStatusTone.ERROR)
        } else {
            ScheduleShiftStatus("Đang làm ca • Chờ chấm ra", ScheduleStatusTone.ACTIVE)
        }
    }
    if (now.isBefore(window.start)) return ScheduleShiftStatus("Sắp vào ca", ScheduleStatusTone.NEUTRAL)
    if (now.isAfter(deadline)) return ScheduleShiftStatus("Thiếu chấm vào", ScheduleStatusTone.ERROR)
    return ScheduleShiftStatus("Chưa chấm vào", ScheduleStatusTone.WARNING)
}
