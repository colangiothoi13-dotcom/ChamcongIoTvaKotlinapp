package vn.chamcong.iot.ui.attendance

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal val attendanceZone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
internal val adjustmentTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)

internal data class AttendanceAdjustmentInput(
    val checkInAt: Instant?,
    val checkOutAt: Instant?,
    val workedHoursOverride: Double?,
    val reason: String
)

/** Blank fields request no override. Current values are used only for validation. */
internal fun parseAttendanceAdjustmentInput(
    checkIn: String,
    checkOut: String,
    workedHours: String,
    reason: String,
    currentCheckIn: Instant? = null,
    currentCheckOut: Instant? = null,
    currentWorkedHours: Double? = null
): AttendanceAdjustmentInput {
    require(reason.isNotBlank()) { "Vui lòng nhập lý do điều chỉnh" }
    fun parseTime(value: String): Instant? {
        if (value.isBlank()) return null
        return runCatching {
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}").matches(value.trim()))
            LocalDateTime.parse(value.trim(), adjustmentTimeFormat).atZone(attendanceZone).toInstant()
        }.getOrElse { throw IllegalArgumentException("Giờ phải hợp lệ và có dạng yyyy-MM-dd HH:mm") }
    }
    val start = parseTime(checkIn)
    val end = parseTime(checkOut)
    val hours = if (workedHours.isBlank()) null else workedHours.trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..24.0 }
        ?: throw IllegalArgumentException("Giờ công phải là số từ 0 đến 24")
    require(start != null || end != null || hours != null) { "Nhập ít nhất một giá trị điều chỉnh" }
    require((start != null && start != currentCheckIn) || (end != null && end != currentCheckOut) ||
        (hours != null && hours != currentWorkedHours)) { "Chưa có giá trị nào thay đổi" }
    val effectiveStart = start ?: currentCheckIn
    val effectiveEnd = end ?: currentCheckOut
    require(effectiveStart == null || effectiveEnd == null || effectiveEnd.isAfter(effectiveStart)) {
        "Giờ ra phải sau giờ vào (ca qua đêm cần nhập ngày hôm sau)"
    }
    return AttendanceAdjustmentInput(start, end, hours, reason.trim())
}
