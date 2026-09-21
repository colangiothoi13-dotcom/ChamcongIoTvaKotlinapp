package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class RequestType {
    LEAVE,
    LATE,
    EARLY_LEAVE,
    REMOTE,
    ATTENDANCE_ADJUSTMENT,
    SHIFT_CHANGE,
    FINGERPRINT_SUPPORT
}

enum class RequestStatus { PENDING, APPROVED, REJECTED, CANCELLED }

data class LeaveRequest(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val type: String = RequestType.LEAVE.name,
    val startDate: String = "",
    val endDate: String = "",
    val reason: String = "",
    val proposedCheckIn: String? = null,
    val proposedCheckOut: String? = null,
    val requestedShiftId: String? = null,
    val requestedShiftName: String? = null,
    /** Null is reserved for legacy whole-day leave requests; new requests list shift IDs per date. */
    val leaveShiftsByDate: Map<String, List<String>>? = null,
    val appliedAdjustmentId: String? = null,
    val attachmentUrl: String? = null,
    val status: String = RequestStatus.PENDING.name,
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: Timestamp? = null,
    val reviewNote: String? = null,
    val createdAt: Timestamp = Timestamp.now()
)

data class OffScheduleAttendanceReview(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val scheduleDate: String = "",
    val shiftId: String = "",
    val shiftName: String = "",
    val decision: String = "",
    val reason: String = "",
    val status: String = "PENDING",
    val reviewerId: String = "",
    val reviewerName: String = "",
    val failureReason: String? = null,
    val matchedScanCount: Int = 0,
    val createdAt: Timestamp? = null,
    val reviewedAt: Timestamp? = null
)

data class AppNotification(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val referenceId: String? = null,
    val recipientEmployeeId: String? = null,
    val audienceLabel: String? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val read: Boolean = false
)
