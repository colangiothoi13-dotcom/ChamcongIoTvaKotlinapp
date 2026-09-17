package vn.chamcong.iot.ui.attendance

import java.time.LocalDate
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceStatus

internal data class AttendanceResolutionPresentation(val label: String, val accepted: Boolean = false)

internal fun attendanceAdjustmentDate(row: Attendance): String? = row.scheduleDate?.let {
    runCatching { LocalDate.parse(it).toString() }.getOrNull()
} ?: if (row.scheduleDate == null) row.timestamp.toDate().toInstant().atZone(attendanceZone).toLocalDate().toString() else null

internal fun attendanceResolutionPresentation(row: Attendance): AttendanceResolutionPresentation {
    val label = when {
        !row.verified -> "ABNORMAL • Chưa xác minh"
        attendanceAdjustmentDate(row) == null -> "ABNORMAL • Ngày ca không hợp lệ"
        row.type !in listOf("SCAN", "CHECK_IN", "CHECK_OUT") -> "ABNORMAL • Loại không hợp lệ"
        row.resolutionStatus !in listOf("PENDING", "ACCEPTED", "DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER") -> "ABNORMAL • Trạng thái không hợp lệ"
        row.type == "SCAN" || row.resolutionStatus == "PENDING" -> "SCAN/PENDING • Chờ xử lý"
        row.resolutionStatus == "DUPLICATE" -> "DUPLICATE • Quét trùng"
        row.resolutionStatus == "UNSCHEDULED" -> "UNSCHEDULED • Chưa có ca"
        row.resolutionStatus == "OUT_OF_ORDER" -> "OUT_OF_ORDER • Sai thứ tự"
        row.status !in AttendanceStatus.entries.map { it.name } -> "ABNORMAL • Trạng thái chấm không hợp lệ"
        else -> return AttendanceResolutionPresentation("ACCEPTED • ${row.status}", accepted = true)
    }
    return AttendanceResolutionPresentation(label)
}
