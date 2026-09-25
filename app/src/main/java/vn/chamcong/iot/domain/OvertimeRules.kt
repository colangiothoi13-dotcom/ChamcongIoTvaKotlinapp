package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkTimeSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.ZoneId

const val SUPPLEMENTARY_SHIFT_ID = "SUPPLEMENTARY_1800_2200"
const val SUPPLEMENTARY_START_TIME = "18:00"
const val SUPPLEMENTARY_END_TIME = "22:00"
const val SUPPLEMENTARY_HOURS = 4.0
val SUPPLEMENTARY_ZONE_ID: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

fun createOvertimeRequest(employee: Employee, workDate: LocalDate, reason: String = ""): OvertimeRequest {
    val request = OvertimeRequest(
        employeeId = employee.id,
        employeeName = employee.fullName,
        department = employee.department,
        workDate = workDate.toString(),
        startTime = SUPPLEMENTARY_START_TIME,
        endTime = SUPPLEMENTARY_END_TIME,
        reason = reason.trim(),
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

fun isOvertimeRequestWithinDeadline(workDate: String, now: Instant = Instant.now()): Boolean {
    val requestedDate = runCatching { LocalDate.parse(workDate) }.getOrNull() ?: return false
    if (requestedDate.dayOfWeek == DayOfWeek.SUNDAY) return false
    val localNow = now.atZone(SUPPLEMENTARY_ZONE_ID)
    val today = localNow.toLocalDate()
    return requestedDate.isAfter(today) ||
        (requestedDate == today && localNow.toLocalTime().isBefore(LocalTime.of(18, 0)))
}

fun approvedOvertimeSummary(
    employeeId: String,
    workDate: LocalDate,
    attendance: List<Attendance>,
    request: OvertimeRequest,
    zoneId: ZoneId
): WorkTimeSummary? {
    if (request.employeeId != employeeId || request.status != OvertimeRequestStatus.APPROVED.name) return null
    if (runCatching { validateOvertimeRequest(request) }.isFailure) return null
    if (runCatching { LocalDate.parse(request.workDate) }.getOrNull() != workDate) return null
    val shift = WorkShift(
        id = SUPPLEMENTARY_SHIFT_ID,
        name = "Tăng ca",
        category = "SUPPLEMENTARY",
        startTime = request.startTime,
        endTime = request.endTime,
        countsOvertime = true,
        missingCheckOutGraceMinutes = 120
    )
    val rows = attendance.filter {
        it.employeeId == employeeId && it.shiftId == SUPPLEMENTARY_SHIFT_ID &&
            (it.scheduleDate == null || it.scheduleDate == workDate.toString())
    }
    val pair = resolveAttendancePair(rows, workDate, shift, zoneId = zoneId)
    val checkIn = pair.checkIn ?: return null
    val checkOut = pair.checkOut ?: return null
    return calculateWorkTime(checkIn, checkOut, shift, 4, zoneId, workDate)
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
