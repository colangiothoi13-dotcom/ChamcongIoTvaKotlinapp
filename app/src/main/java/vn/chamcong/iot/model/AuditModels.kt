package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class AuditAction {
    LOGIN,
    ACCOUNT_CREATE,
    EMPLOYEE_CREATE,
    EMPLOYEE_UPDATE,
    DEPARTMENT_UPDATE,
    ANNOUNCEMENT_SEND,
    FINGERPRINT_DELETE,
    ATTENDANCE_ADJUST,
    ATTENDANCE_CLASSIFICATION_CORRECT,
    LEAVE_REVIEW,
    LEAVE_CANCEL,
    OVERTIME_REVIEW,
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
