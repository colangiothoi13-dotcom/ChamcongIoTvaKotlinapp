package vn.chamcong.iot.ui.attendance

import java.time.LocalDate
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceStatus
import vn.chamcong.iot.ui.attendanceResolutionLabel
import vn.chamcong.iot.ui.attendanceStatusLabel

internal data class AttendanceResolutionPresentation(val label: String, val accepted: Boolean = false)

internal fun attendanceAdjustmentDate(row: Attendance): String? = row.scheduleDate?.let {
    runCatching { LocalDate.parse(it).toString() }.getOrNull()
} ?: if (row.scheduleDate == null) row.timestamp.toDate().toInstant().atZone(attendanceZone).toLocalDate().toString() else null

internal fun attendanceResolutionPresentation(row: Attendance): AttendanceResolutionPresentation {
    val label = when {
        !row.verified -> "Bất thường • Chưa xác minh"
        attendanceAdjustmentDate(row) == null -> "Bất thường • Ngày ca không hợp lệ"
        row.type !in listOf("SCAN", "CHECK_IN", "CHECK_OUT", "DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER") -> "Bất thường • Loại không hợp lệ"
        row.resolutionStatus !in listOf("PENDING", "ACCEPTED", "DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER") -> "Bất thường • Trạng thái không hợp lệ"
        row.type == "SCAN" || row.resolutionStatus == "PENDING" -> "${attendanceResolutionLabel("PENDING")} • Chờ hệ thống xử lý"
        row.type == "DUPLICATE" || row.resolutionStatus == "DUPLICATE" -> attendanceResolutionLabel("DUPLICATE")
        row.type == "UNSCHEDULED" || row.resolutionStatus == "UNSCHEDULED" -> attendanceResolutionLabel("UNSCHEDULED")
        row.type == "OUT_OF_ORDER" || row.resolutionStatus == "OUT_OF_ORDER" -> attendanceResolutionLabel("OUT_OF_ORDER")
        row.status !in AttendanceStatus.entries.map { it.name } -> "Bất thường • Trạng thái chấm không hợp lệ"
        else -> return AttendanceResolutionPresentation(attendanceStatusLabel(row.status), accepted = true)
    }
    return AttendanceResolutionPresentation(label)
}
