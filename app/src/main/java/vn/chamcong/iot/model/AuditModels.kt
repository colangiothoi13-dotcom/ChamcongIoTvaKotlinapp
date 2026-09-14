package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class AuditAction {
    LOGIN,
    EMPLOYEE_CREATE,
    EMPLOYEE_UPDATE,
    FINGERPRINT_DELETE,
    ATTENDANCE_ADJUST,
    LEAVE_REVIEW,
    SHIFT_UPDATE,
    DEVICE_CONFIG_UPDATE,
    PASSWORD_CHANGE
}

data class AuditLog(
    val id: String = "",
    val actorId: String = "",
    val actorName: String = "",
    val action: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val reason: String = "",
    val details: String = "",
    val createdAt: Timestamp = Timestamp.now()
)
