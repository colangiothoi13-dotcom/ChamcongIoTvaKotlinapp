package vn.chamcong.iot.domain

import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus

fun requestReviewNote(value: String, required: Boolean): String {
    val note = value.trim()
    require(!required || note.isNotBlank()) { "Vui lòng nhập lý do xử lý." }
    require(note.length <= 1000) { "Ghi chú tối đa 1.000 ký tự." }
    return note
}

/** Approval notes belong to the notification; approved overtime audits retain an empty reason. */
fun overtimeReviewNotification(request: OvertimeRequest, note: String): AppNotification {
    require(request.status in setOf(OvertimeRequestStatus.APPROVED.name, OvertimeRequestStatus.REJECTED.name)) {
        "Đơn tăng ca chưa được xử lý."
    }
    val cleanNote = requestReviewNote(note, required = request.status == OvertimeRequestStatus.REJECTED.name)
    return AppNotification(
        type = "OVERTIME_RESULT",
        title = if (request.status == OvertimeRequestStatus.APPROVED.name) "Tăng ca đã được duyệt" else "Đơn tăng ca bị từ chối",
        body = "${request.workDate} ${request.startTime}–${request.endTime}. ${cleanNote.ifBlank { "Đã xử lý" }}",
        referenceId = request.id, recipientEmployeeId = request.employeeId,
        audienceLabel = request.employeeName
    )
}
