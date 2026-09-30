package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate
import java.time.ZoneId

data class AttendanceDateRange(
    val start: LocalDate,
    val endInclusive: LocalDate
) {
    init {
        require(!endInclusive.isBefore(start)) { "Ngày kết thúc phải từ ngày bắt đầu trở đi" }
    }
}

fun parseAttendanceDateRange(start: String, endInclusive: String): AttendanceDateRange? {
    val parsedStart = runCatching { LocalDate.parse(start.trim()) }.getOrNull() ?: return null
    val parsedEnd = runCatching { LocalDate.parse(endInclusive.trim()) }.getOrNull() ?: return null
    return runCatching { AttendanceDateRange(parsedStart, parsedEnd) }.getOrNull()
}

/**
 * Filters without changing persisted attendance. Overnight events use the
 * schedule date assigned by the existing resolver; unassigned events use the
 * local Vietnam calendar date.
 */
fun filterAttendanceByDateRange(
    attendance: List<Attendance>,
    range: AttendanceDateRange,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
): List<Attendance> {
    val assigned = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
    return assigned.filter { row ->
        val date = row.scheduleDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()
        date in range.start..range.endInclusive
    }
}
