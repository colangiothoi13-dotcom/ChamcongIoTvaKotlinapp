package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class RequestType {
    LEAVE,
    LATE,
    EARLY_LEAVE,
    REMOTE,
    ATTENDANCE_ADJUSTMENT,
    SHIFT_CHANGE
}

enum class RequestStatus { PENDING, APPROVED, REJECTED }

data class LeaveRequest(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val type: String = RequestType.LEAVE.name,
    val startDate: String = "",
    val endDate: String = "",
    val reason: String = "",
    val attachmentUrl: String? = null,
    val status: String = RequestStatus.PENDING.name,
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: Timestamp? = null,
    val reviewNote: String? = null,
    val createdAt: Timestamp = Timestamp.now()
)

data class AppNotification(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val referenceId: String? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val read: Boolean = false
)
