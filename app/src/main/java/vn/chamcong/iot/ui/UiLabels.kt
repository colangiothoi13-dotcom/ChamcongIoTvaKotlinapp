package vn.chamcong.iot.ui

internal fun attendanceStatusLabel(status: String): String = when (status) {
    "NORMAL" -> "Bình thường"
    "PRESENT" -> "Có mặt"
    "ON_TIME" -> "Đúng giờ"
    "LATE" -> "Đi trễ"
    "EARLY_LEAVE" -> "Về sớm"
    "MISSING_CHECK_IN" -> "Chưa chấm vào"
    "MISSING_CHECK_OUT" -> "Chưa chấm ra"
    "ABNORMAL" -> "Bất thường"
    "LEAVE" -> "Nghỉ phép"
    "NOT_CHECKED_IN" -> "Chưa đến"
    "ON_LEAVE" -> "Đang nghỉ phép"
    "LEFT" -> "Đã ra về"
    "PENDING" -> "Chờ xử lý"
    "ACCEPTED" -> "Đã ghi nhận"
    else -> status
}

internal fun attendanceResolutionLabel(status: String): String = when (status) {
    "DUPLICATE" -> "Quét trùng"
    "UNSCHEDULED" -> "Chưa có ca"
    "OUT_OF_ORDER" -> "Sai thứ tự"
    "PENDING", "SCAN" -> "Chờ xử lý"
    "ACCEPTED" -> "Đã ghi nhận"
    else -> attendanceStatusLabel(status)
}

internal fun requestStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Chờ duyệt"
    "APPROVED" -> "Đã duyệt"
    "REJECTED" -> "Từ chối"
    "CANCELLED" -> "Đã hủy"
    else -> status
}

internal fun requestTypeLabel(type: String): String = when (type) {
    "LEAVE" -> "Nghỉ phép"
    "LATE" -> "Đi muộn"
    "EARLY_LEAVE" -> "Về sớm"
    "ATTENDANCE_ADJUSTMENT" -> "Sửa chấm công"
    "REMOTE" -> "Ngoài văn phòng"
    "SHIFT_CHANGE" -> "Đổi ca"
    "FINGERPRINT_SUPPORT" -> "Hỗ trợ vân tay"
    else -> type
}

internal fun deviceStatusLabel(status: String): String = when (status.uppercase()) {
    "ONLINE" -> "Đang hoạt động"
    "OFFLINE" -> "Mất kết nối"
    else -> "Chưa rõ"
}

internal fun auditActionLabel(action: String): String = when (action) {
    "LOGIN" -> "Đăng nhập"
    "ACCOUNT_CREATE" -> "Tạo tài khoản"
    "EMPLOYEE_CREATE" -> "Thêm nhân viên"
    "EMPLOYEE_UPDATE" -> "Cập nhật nhân viên"
    "DEPARTMENT_UPDATE" -> "Cập nhật phòng ban"
    "ANNOUNCEMENT_SEND" -> "Gửi thông báo"
    "FINGERPRINT_DELETE" -> "Xóa vân tay"
    "ATTENDANCE_ADJUST" -> "Điều chỉnh chấm công"
    "LEAVE_REVIEW" -> "Xử lý đơn từ"
    "LEAVE_CANCEL" -> "Hủy đơn nghỉ phép"
    "OVERTIME_REVIEW" -> "Duyệt tăng ca"
    "SHIFT_UPDATE" -> "Cập nhật ca"
    "DEVICE_CONFIG_UPDATE" -> "Cập nhật thiết bị"
    "DEVICE_COMMAND" -> "Điều khiển thiết bị"
    "PASSWORD_CHANGE" -> "Đổi mật khẩu"
    else -> action
}

internal fun auditTargetTypeLabel(targetType: String): String = when (targetType) {
    "user" -> "Tài khoản"
    "employee" -> "Nhân viên"
    "attendance" -> "Lượt chấm công"
    "attendanceAdjustment" -> "Điều chỉnh chấm công"
    "leaveRequest" -> "Đơn từ"
    "overtimeRequest" -> "Đơn tăng ca"
    "shift" -> "Ca làm"
    "workSchedule" -> "Lịch phân ca"
    "device" -> "Thiết bị"
    "deviceCommand" -> "Lệnh thiết bị"
    else -> targetType
}

internal fun auditLogTitle(action: String, targetType: String): String =
    "${auditActionLabel(action)} • ${auditTargetTypeLabel(targetType)}"

internal fun auditActorLabel(actorName: String): String =
    actorName.ifBlank { "Người dùng không xác định" }

internal fun userFacingErrorMessage(error: Throwable): String {
    val raw = error.localizedMessage.orEmpty()
    return if (raw.contains("PERMISSION_DENIED", ignoreCase = true)
        || raw.contains("Missing or insufficient permissions", ignoreCase = true)
    ) {
        "Tài khoản chưa có quyền đọc dữ liệu. Hãy kiểm tra vai trò ADMIN, trạng thái active và Firestore Rules."
    } else {
        raw.ifBlank { "Đã xảy ra lỗi, vui lòng thử lại." }
    }
}
