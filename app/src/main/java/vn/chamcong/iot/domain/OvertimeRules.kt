package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val SUPPLEMENTARY_SHIFT_ID = "SUPPLEMENTARY_1730_2030"
const val SUPPLEMENTARY_START_TIME = "17:30"
const val SUPPLEMENTARY_END_TIME = "20:30"
const val SUPPLEMENTARY_HOURS = 3.0
val SUPPLEMENTARY_ZONE_ID: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

fun createOvertimeRequest(employee: Employee, workDate: LocalDate): OvertimeRequest {
    val request = OvertimeRequest(
        employeeId = employee.id,
        employeeName = employee.fullName,
        department = employee.department,
        workDate = workDate.toString(),
        startTime = SUPPLEMENTARY_START_TIME,
        endTime = SUPPLEMENTARY_END_TIME,
        status = OvertimeRequestStatus.PENDING.name
    )
    validateOvertimeRequest(request)
    return request
}

fun validateOvertimeRequest(request: OvertimeRequest) {
    require(request.employeeId.isNotBlank()) { "Employee is required" }
    require(runCatching { LocalDate.parse(request.workDate) }.isSuccess) { "Work date is required" }
    require(request.startTime == SUPPLEMENTARY_START_TIME && request.endTime == SUPPLEMENTARY_END_TIME) {
        "Overtime must use the fixed supplementary shift"
    }
    require(request.status in OvertimeRequestStatus.entries.map { it.name }) { "Invalid overtime status" }
    if (request.status == OvertimeRequestStatus.REJECTED.name) {
        require(!request.rejectionReason.isNullOrBlank()) { "Rejection reason is required" }
    }
}

fun reviewOvertimeRequest(
    request: OvertimeRequest,
    status: OvertimeRequestStatus,
    reviewerId: String,
    reviewerName: String,
    reason: String,
    reviewedAt: Instant
): OvertimeRequest {
    validateOvertimeRequest(request)
    require(request.status == OvertimeRequestStatus.PENDING.name) { "Overtime request was already reviewed" }
    require(status != OvertimeRequestStatus.PENDING) { "Review must approve or reject the request" }
    require(reviewerId.isNotBlank() && reviewerName.isNotBlank()) { "Reviewer is required" }
    if (status == OvertimeRequestStatus.REJECTED) {
        require(reason.isNotBlank()) { "Rejection reason is required" }
    }

    return request.copy(
        status = status.name,
        reviewerId = reviewerId,
        reviewerName = reviewerName,
        reviewedAt = reviewedAt,
        rejectionReason = reason.trim().takeIf { status == OvertimeRequestStatus.REJECTED }
    ).also(::validateOvertimeRequest)
}
