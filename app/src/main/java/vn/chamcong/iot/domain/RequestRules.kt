package vn.chamcong.iot.domain

import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import com.google.firebase.Timestamp
import java.time.LocalDate

fun validateRequest(request: LeaveRequest) {
    require(request.employeeId.isNotBlank()) { "Đơn thiếu mã nhân viên" }
    require(request.type in RequestType.entries.map { it.name }) { "Loại đơn không được hỗ trợ" }
    val start = runCatching { LocalDate.parse(request.startDate) }
        .getOrElse { throw IllegalArgumentException("Ngày bắt đầu phải có dạng yyyy-MM-dd") }
    val end = runCatching { LocalDate.parse(request.endDate) }
        .getOrElse { throw IllegalArgumentException("Ngày kết thúc phải có dạng yyyy-MM-dd") }
    require(!end.isBefore(start)) { "Ngày kết thúc phải từ ngày bắt đầu trở đi" }
    require(request.reason.isNotBlank()) { "Lý do không được để trống" }
    require(request.status in RequestStatus.entries.map { it.name }) { "Trạng thái đơn không hợp lệ" }
    request.leaveShiftsByDate?.let { scope ->
        require(request.type == RequestType.LEAVE.name) { "Chỉ đơn nghỉ phép được khai báo ca nghỉ" }
        require(scope.isNotEmpty() && scope.size <= 31) { "Chọn ít nhất một ngày nghỉ (tối đa 31 ngày)" }
        require(scope.all { (dateText, shiftIds) ->
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
            date != null && date in start..end && shiftIds.isNotEmpty() &&
                shiftIds.size <= 2 && shiftIds.all(String::isNotBlank) && shiftIds.distinct().size == shiftIds.size
        }) { "Phạm vi ca nghỉ không hợp lệ" }
    }
}

fun reviewRequest(
    request: LeaveRequest,
    status: RequestStatus,
    reviewerId: String,
    reviewerName: String,
    note: String,
    reviewedAt: Timestamp
): LeaveRequest {
    validateRequest(request)
    require(request.status == RequestStatus.PENDING.name) { "Đơn này đã được xử lý" }
    require(status == RequestStatus.APPROVED || status == RequestStatus.REJECTED) { "Phải chọn duyệt hoặc từ chối" }
    require(reviewerId.isNotBlank() && reviewerName.isNotBlank()) { "Thiếu thông tin người duyệt" }
    if (status == RequestStatus.REJECTED) require(note.isNotBlank()) { "Cần nhập lý do từ chối" }
    return request.copy(
        status = status.name,
        reviewerId = reviewerId,
        reviewerName = reviewerName,
        reviewedAt = reviewedAt,
        reviewNote = note.trim().ifBlank { null }
    )
}

fun notificationForRequest(request: LeaveRequest): AppNotification = AppNotification(
    type = "REQUEST",
    title = "Có đơn từ mới cần duyệt",
    body = "${request.employeeName.ifBlank { request.employeeId }} đã gửi một đơn từ.",
    referenceId = request.id
)
