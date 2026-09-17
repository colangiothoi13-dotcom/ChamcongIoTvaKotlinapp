package vn.chamcong.iot.ui.overtime

import java.time.LocalDate
import vn.chamcong.iot.domain.SUPPLEMENTARY_END_TIME
import vn.chamcong.iot.domain.SUPPLEMENTARY_START_TIME
import vn.chamcong.iot.domain.SUPPLEMENTARY_ZONE_ID
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus

private const val FIXED_WINDOW_SEPARATOR = "–"

fun overtimeStatusLabel(status: String): String = when (status) {
    OvertimeRequestStatus.PENDING.name -> "Chờ duyệt"
    OvertimeRequestStatus.APPROVED.name -> "Đã duyệt"
    OvertimeRequestStatus.REJECTED.name -> "Từ chối"
    else -> "Không xác định"
}

fun overtimeWindowLabel(request: OvertimeRequest): String {
    val fixedWindow = "$SUPPLEMENTARY_START_TIME$FIXED_WINDOW_SEPARATOR$SUPPLEMENTARY_END_TIME"
    val hasStaleTimes = request.startTime != SUPPLEMENTARY_START_TIME ||
        request.endTime != SUPPLEMENTARY_END_TIME
    return if (hasStaleTimes) {
        "$fixedWindow • Dữ liệu giờ đã lưu không hợp lệ"
    } else {
        fixedWindow
    }
}

fun isValidOvertimeWorkDate(
    value: String,
    today: LocalDate = LocalDate.now(SUPPLEMENTARY_ZONE_ID)
): Boolean {
    val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull() ?: return false
    return !date.isBefore(today)
}
