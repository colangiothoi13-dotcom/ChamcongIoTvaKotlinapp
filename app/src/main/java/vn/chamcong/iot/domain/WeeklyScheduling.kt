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
        require(LocalTime.parse(from).isBefore(LocalTime.parse(to))) {
            "Giờ kết thúc phải sau giờ bắt đầu; ca không được qua ngày"
        }
        return WorkShift(id = "weekly_v1_${key}_${from.replace(":", "")}_${to.replace(":", "")}",
            name = name, category = category, startTime = from, endTime = to,
            allowEarlyMinutes = if (category == "MORNING" && from == "08:00" && to == "12:00") 120 else 0,
            lateGraceMinutes = if (
                (category == "MORNING" && from == "08:00" && to == "12:00") ||
                (category == "EVENING" && from == "13:00" && to == "17:00")
            ) 10 else 0,
            missingCheckOutGraceMinutes = if (
                (category == "MORNING" && from == "08:00" && to == "12:00") ||
                (category == "EVENING" && from == "13:00" && to == "17:00")
            ) 30 else 60,
            countsOvertime = category == "SUPPLEMENTARY", effectiveFrom = "1970-01-01")
    }
}

fun defaultShiftTemplates(): List<ShiftTemplate> = listOf(
    ShiftTemplate("morning", "Ca sáng", "MORNING", "08:00", "12:00"),
    ShiftTemplate("afternoon", "Ca chiều", "EVENING", "13:00", "17:00")
)

/** Reuse the saved shift's settings while keeping the stable template ID tied to its time window. */
fun weeklyAssignmentShift(template: WorkShift, stored: WorkShift?): WorkShift {
    validateScheduleShift(template)
    if (stored == null) return template
    require(stored.id == template.id && stored.category == template.category &&
        stored.startTime == template.startTime && stored.endTime == template.endTime && stored.active) {
        "Ca mẫu đã đổi giờ, loại hoặc ngừng hoạt động. Vui lòng kiểm tra cấu hình ca."
    }
    validateScheduleShift(stored)
    return stored
}

/** Add or replace the selected category without discarding another shift already assigned that day. */
fun mergeWeeklyAssignment(
    incoming: WorkSchedule,
    existing: WorkSchedule?,
    selectedShift: WorkShift,
    shiftsById: Map<String, WorkShift>
): WorkSchedule = mergeScheduleAssignment(incoming, existing, listOf(selectedShift), shiftsById)

/** Shared by weekly and department assignments, including selecting both main shifts at once. */
fun mergeScheduleAssignment(
    incoming: WorkSchedule,
    existing: WorkSchedule?,
    selectedShifts: List<WorkShift>,
    shiftsById: Map<String, WorkShift>
): WorkSchedule {
    require(selectedShifts.size in 1..2 && selectedShifts.map { it.id }.distinct().size == selectedShifts.size &&
        selectedShifts.map { it.category }.distinct().size == selectedShifts.size) {
        "Mỗi ngày chỉ được có một ca sáng và một ca chiều"
    }
    selectedShifts.forEach { shift ->
        validateScheduleShift(shift)
        require(shift.active) { "Ca ${shift.name} không còn hoạt động" }
    }
    val selectedCategories = selectedShifts.map { it.category }.toSet()
    val previousIds = existing?.let(::scheduledShiftIds).orEmpty()
    val retained = previousIds.map { id ->
        shiftsById[id] ?: error("Ca đã phân trước đó không còn tồn tại: $id")
    }.filter { it.category !in selectedCategories }
    require(retained.all { it.active && canAssignScheduleShift(it) }) {
        "Một ca đã phân trước đó không còn hoạt động. Vui lòng kiểm tra lịch ngày ${incoming.date}."
    }
    val selected = (retained + selectedShifts).distinctBy { it.id }.sortedBy { it.startTime }
    require(selected.size in 1..2 && selected.map { it.category }.distinct().size == selected.size) {
        "Mỗi ngày chỉ được có một ca sáng và một ca chiều"
    }
    return incoming.copy(
        shiftId = selected.first().id,
        shiftIds = selected.map { it.id },
        shiftName = selected.joinToString(" + ") { it.name },
        overtimeHours = existing?.overtimeHours ?: incoming.overtimeHours,
        workedHoursOverride = if (existing != null) existing.workedHoursOverride else incoming.workedHoursOverride,
        adjustmentNote = existing?.adjustmentNote ?: incoming.adjustmentNote,
        note = existing?.note ?: incoming.note
    )
}

private val weeklyScheduleZone = ZoneId.of("Asia/Ho_Chi_Minh")

fun weeklyScheduleSubmissionDeadline(
    weekStart: LocalDate,
    zoneId: ZoneId = weeklyScheduleZone
): Instant = mondayOfWeek(weekStart)
    .minusDays(2)
    .atTime(12, 0)
    .atZone(zoneId)
    .toInstant()

fun isWeeklyScheduleSubmissionOpen(
    weekStart: LocalDate,
    now: Instant = Instant.now(),
    zoneId: ZoneId = weeklyScheduleZone
): Boolean = now.isBefore(weeklyScheduleSubmissionDeadline(weekStart, zoneId))

data class WeeklyScheduleStatus(val missingEmployeeIds: List<String>, val overdue: Boolean)

/** Coverage means at least one assignment per active employee, not a mandatory shift on all six workdays. */
fun weeklyScheduleStatus(week: LocalDate, employees: List<Employee>, schedules: List<WorkSchedule>, now: Instant): WeeklyScheduleStatus {
    val dates = weekDates(week).map { it.toString() }.toSet()
    val covered = schedules.filter { it.date in dates && scheduledShiftIds(it).isNotEmpty() }.map { it.employeeId }.toSet()
    val missing = employees.filter { it.active && it.id !in covered }.map { it.id }.distinct()
    val deadline = mondayOfWeek(week).minusDays(2).atTime(17, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    return WeeklyScheduleStatus(missing, missing.isNotEmpty() && !now.isBefore(deadline))
}

fun weeklyAssignmentPayload(employees: List<Employee>, employeeIds: Set<String>, week: LocalDate,
    dates: Set<LocalDate>, shift: WorkShift, actorId: String): List<WorkSchedule> {
    require(employeeIds.isNotEmpty()) { "Chọn ít nhất một nhân viên" }
    require(dates.isNotEmpty() && dates.all { it in weekDates(week) }) { "Chọn ngày trong tuần đang xem" }
    val selected = employees.filter { it.active && it.id in employeeIds }.distinctBy { it.id }
    require(selected.map { it.id }.toSet() == employeeIds && employeeIds.none { it.isBlank() }) { "Nhân viên đã thay đổi; vui lòng chọn lại" }
    require(shift.id.isNotBlank()) { "Chưa chọn ca" }
    validateScheduleShift(shift)
    return selected.flatMap { employee -> dates.sorted().map { date ->
        WorkSchedule(id = "${employee.id}_$date", employeeId = employee.id, employeeName = employee.fullName,
            department = employee.department, shiftId = shift.id, shiftIds = listOf(shift.id), shiftName = shift.name,
            date = date.toString(), assignedBy = actorId)
    } }
}
