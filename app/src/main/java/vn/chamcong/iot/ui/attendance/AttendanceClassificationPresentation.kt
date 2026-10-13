package vn.chamcong.iot.ui.attendance

import vn.chamcong.iot.domain.canCorrectAttendanceClassification
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceClassificationOverride
import java.time.LocalDate

internal data class AttendanceClassificationSelection(val type: String, val status: String)

internal fun initialAttendanceClassificationSelection(
    row: Attendance,
    previous: AttendanceClassificationOverride?
): AttendanceClassificationSelection {
    val type = (previous?.correctedType ?: row.type).takeIf { it in setOf("CHECK_IN", "CHECK_OUT", "DISCARDED") } ?: "CHECK_IN"
    val status = if (type == "DISCARDED") "DISCARDED"
        else (previous?.correctedStatus ?: row.status).takeIf { it in setOf("NORMAL", "ON_TIME", "LATE", "EARLY_LEAVE") } ?: "NORMAL"
    return AttendanceClassificationSelection(type, status)
}

internal fun attendanceClassificationDraft(
    row: Attendance,
    source: Attendance,
    previous: AttendanceClassificationOverride?,
    correctedType: String,
    correctedStatus: String,
    reason: String
): AttendanceClassificationOverride {
    require(canCorrectAttendanceClassification(source)) { "Lượt chấm gốc không đủ điều kiện sửa phân loại." }
    require(row.id == source.id && row.employeeId == source.employeeId) { "Lượt chấm đã thay đổi. Hãy mở lại." }
    val date = requireNotNull(attendanceAdjustmentDate(row)) { "Ngày ca không hợp lệ." }
    LocalDate.parse(date)
    require(previous == null || previous.attendanceId == source.id && previous.sourceTimestamp == source.timestamp.toDate().toInstant()) {
        "Lịch sử phân loại không khớp lượt chấm gốc."
    }
    require(correctedType in setOf("CHECK_IN", "CHECK_OUT", "DISCARDED")) { "Chọn phân loại hợp lệ." }
    require(correctedStatus in setOf("NORMAL", "ON_TIME", "LATE", "EARLY_LEAVE", "DISCARDED")) { "Chọn trạng thái hợp lệ." }
    require((correctedType == "DISCARDED") == (correctedStatus == "DISCARDED")) { "Trạng thái loại lượt quét không hợp lệ." }
    val previousType = previous?.correctedType ?: source.type
    val previousStatus = previous?.correctedStatus ?: source.status
    require(correctedType != previousType || correctedStatus != previousStatus) { "Chưa có thay đổi phân loại." }
    val trimmedReason = reason.trim()
    require(trimmedReason.isNotBlank() && trimmedReason.length <= 500) { "Nhập lý do xử lý, tối đa 500 ký tự." }
    return AttendanceClassificationOverride(
        attendanceId = source.id, employeeId = source.employeeId,
        employeeName = source.employeeName.ifBlank { row.employeeName.ifBlank { source.employeeId } },
        scheduleDate = date, sourceTimestamp = source.timestamp.toDate().toInstant(),
        sourceType = source.type, sourceStatus = source.status, sourceResolutionStatus = source.resolutionStatus,
        previousType = previousType, previousStatus = previousStatus,
        correctedType = correctedType, correctedStatus = correctedStatus, reason = trimmedReason
    )
}

internal fun classificationTypeLabel(type: String): String = when (type) {
    "CHECK_IN" -> "Vào ca"
    "CHECK_OUT" -> "Ra ca"
    "SCAN" -> "Lượt quét chưa phân loại"
    "DISCARDED" -> "Đã loại lượt quét"
    else -> type
}
