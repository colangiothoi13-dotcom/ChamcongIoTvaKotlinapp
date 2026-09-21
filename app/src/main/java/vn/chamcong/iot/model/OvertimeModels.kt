package vn.chamcong.iot.model

import java.time.Instant

enum class OvertimeRequestStatus { PENDING, APPROVED, REJECTED }

data class OvertimeRequest(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val department: String = "",
    val workDate: String = "",
    val startTime: String = "18:00",
    val endTime: String = "22:00",
    val reason: String = "",
    val status: String = OvertimeRequestStatus.PENDING.name,
    val createdAt: Instant? = null,
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: Instant? = null,
    val rejectionReason: String? = null
)
