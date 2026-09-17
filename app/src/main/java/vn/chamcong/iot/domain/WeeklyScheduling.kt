package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ShiftTemplate(val key: String, val name: String, val category: String, val startTime: String?, val endTime: String?) {
    fun resolve(start: String = "", end: String = ""): WorkShift {
        val from = startTime ?: start.trim()
        val to = endTime ?: end.trim()
        require(listOf(from, to).all { it.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]")) }) { "Nhập giờ hợp lệ theo HH:mm" }
        require(LocalTime.parse(from) != LocalTime.parse(to)) { "Giờ bắt đầu và kết thúc phải khác nhau" }
        return WorkShift(id = "weekly_v1_${key}_${from.replace(":", "")}_${to.replace(":", "")}",
            name = name, category = category, startTime = from, endTime = to,
            countsOvertime = category == "SUPPLEMENTARY", effectiveFrom = "1970-01-01")
    }
}

fun defaultShiftTemplates(): List<ShiftTemplate> = listOf(
    ShiftTemplate("morning", "Ca sáng", "MORNING", "08:00", "12:00"),
    ShiftTemplate("afternoon", "Ca chiều", "EVENING", "13:00", "17:00"),
    ShiftTemplate("supplemental", "Ca bổ sung/tăng ca", "SUPPLEMENTARY", null, null)
)

data class WeeklyScheduleStatus(val missingEmployeeIds: List<String>, val overdue: Boolean)

/** Coverage means at least one assignment per active employee, not seven mandatory workdays. */
fun weeklyScheduleStatus(week: LocalDate, employees: List<Employee>, schedules: List<WorkSchedule>, now: Instant): WeeklyScheduleStatus {
    val dates = weekDates(week).map { it.toString() }.toSet()
    val covered = schedules.filter { it.date in dates && it.shiftId.isNotBlank() }.map { it.employeeId }.toSet()
    val missing = employees.filter { it.active && it.id !in covered }.map { it.id }.distinct()
    val deadline = mondayOfWeek(week).minusDays(1).atTime(17, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    return WeeklyScheduleStatus(missing, missing.isNotEmpty() && !now.isBefore(deadline))
}

fun weeklyAssignmentPayload(employees: List<Employee>, employeeIds: Set<String>, week: LocalDate,
    dates: Set<LocalDate>, shift: WorkShift, actorId: String): List<WorkSchedule> {
    require(employeeIds.isNotEmpty()) { "Chọn ít nhất một nhân viên" }
    require(dates.isNotEmpty() && dates.all { it in weekDates(week) }) { "Chọn ngày trong tuần đang xem" }
    val selected = employees.filter { it.active && it.id in employeeIds }.distinctBy { it.id }
    require(selected.map { it.id }.toSet() == employeeIds && employeeIds.none { it.isBlank() }) { "Nhân viên đã thay đổi; vui lòng chọn lại" }
    require(shift.id.isNotBlank()) { "Chưa chọn ca" }
    validateShift(shift)
    return selected.flatMap { employee -> dates.sorted().map { date ->
        WorkSchedule(id = "${employee.id}_$date", employeeId = employee.id, employeeName = employee.fullName,
            department = employee.department, shiftId = shift.id, shiftName = shift.name,
            date = date.toString(), assignedBy = actorId)
    } }
}
