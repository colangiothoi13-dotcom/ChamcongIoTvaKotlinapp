package vn.chamcong.iot.model

import java.time.Instant

enum class OvertimeRequestStatus { PENDING, APPROVED, REJECTED }

data class OvertimeRequest(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val workDate: String = "",
    val startTime: String = "17:30",
    val endTime: String = "20:30",
    val status: String = OvertimeRequestStatus.PENDING.name,
    val createdAt: Instant? = null,
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: Instant? = null,
    val rejectionReason: String? = null
)
