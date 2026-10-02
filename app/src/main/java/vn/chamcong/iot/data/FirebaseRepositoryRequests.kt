// Chức năng: Xử lý đơn nghỉ, yêu cầu tăng ca và trạng thái thông báo.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.notificationForRequest
import vn.chamcong.iot.domain.reviewRequest
import vn.chamcong.iot.domain.reviewOvertimeRequest as applyOvertimeReview
import vn.chamcong.iot.domain.validateOvertimeRequest
import vn.chamcong.iot.domain.isOvertimeRequestWithinDeadline
import vn.chamcong.iot.domain.validateRequest
import vn.chamcong.iot.domain.validateScheduleShift
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import vn.chamcong.iot.domain.validateAttendanceAdjustment

suspend fun FirebaseRepository.submitLeaveRequest(request: LeaveRequest): String {
    validateRequest(request)
    val requestRef = db.collection("leaveRequests").document()
    val notificationRef = db.collection("notifications").document()
    val stored = request.copy(id = requestRef.id, status = RequestStatus.PENDING.name)
    val notification = notificationForRequest(stored).copy(id = notificationRef.id)
    db.runBatch {
        it.set(requestRef, stored.toFirestoreData())
        it.set(notificationRef, notification.toFirestoreData())
    }.await()
    return requestRef.id
}

suspend fun FirebaseRepository.submitEmployeeRequest(request: LeaveRequest): String {
    validateRequest(request)
    require(request.status == RequestStatus.PENDING.name) { "Đơn Nhân viên phải ở trạng thái chờ duyệt" }
    require(request.reviewerId == null && request.reviewerName == null && request.reviewedAt == null) {
        "Nhân viên không được tự nhập thông tin duyệt đơn"
    }
    when (request.type) {
        RequestType.ATTENDANCE_ADJUSTMENT.name -> {
            require(request.startDate == request.endDate) { "Điều chỉnh công chỉ áp dụng cho một ngày" }
            val validCheckIn = request.proposedCheckIn?.let { runCatching { java.time.LocalTime.parse(it) }.isSuccess } == true
            val validCheckOut = request.proposedCheckOut?.let { runCatching { java.time.LocalTime.parse(it) }.isSuccess } == true
            require(validCheckIn || validCheckOut) {
                "Nhập giờ vào hoặc giờ ra đề xuất theo dạng HH:mm"
            }
        }
        RequestType.SHIFT_CHANGE.name -> {
            require(request.startDate == request.endDate) { "Đổi ca chỉ áp dụng cho một ngày" }
            require(!request.requestedShiftId.isNullOrBlank()) { "Chọn ca muốn đổi sang" }
            requireAssignableStoredShift(request.requestedShiftId)
            val scheduled = db.collection("workSchedules").document(scheduleDocumentId(request.employeeId, request.startDate))
                .get(Source.SERVER).await().toObject(WorkSchedule::class.java)
            require(scheduled != null) { "Ngày này chưa được phân ca để đổi" }
        }
    }
    val profile = db.collection("users").document(currentUserId).get().await().toObject(UserProfile::class.java)
    require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == request.employeeId) {
        "Tài khoản không được gửi đơn cho nhân viên này"
    }
    val requestRef = db.collection("leaveRequests").document()
    val stored = request.copy(id = requestRef.id, status = RequestStatus.PENDING.name)
    if (request.type == RequestType.LEAVE.name) {
        val scope = request.leaveShiftsByDate ?: error("Chọn các ca nghỉ theo lịch đã phân")
        require(scope.isNotEmpty() && scope.size <= 31) { "Chọn từ 1 đến 31 ngày nghỉ" }
        val schedules = scope.keys.associateWith { date ->
            db.collection("workSchedules").document(scheduleDocumentId(request.employeeId, date))
        }
        db.runTransaction { transaction ->
            schedules.forEach { (date, ref) ->
                val scheduled = transaction.get(ref).toObject(WorkSchedule::class.java)
                    ?: error("Ngày $date chưa có lịch làm được phân")
                val assignedIds = (scheduled.shiftIds.ifEmpty { listOf(scheduled.shiftId) })
                    .filter(String::isNotBlank).distinct().toSet()
                val requestedIds = scope[date].orEmpty()
                require(requestedIds.isNotEmpty() && requestedIds.all(assignedIds::contains)) {
                    "Chỉ chọn ca đã có trong lịch ngày $date"
                }
            }
            transaction.set(requestRef, stored.toFirestoreData())
        }.await()
    } else {
        requestRef.set(stored.toFirestoreData()).await()
    }
    return requestRef.id
}

suspend fun FirebaseRepository.cancelEmployeeLeaveRequest(requestId: String, employeeId: String) {
    require(requestId.isNotBlank() && employeeId.isNotBlank()) { "Thiếu thông tin đơn nghỉ phép" }
    val requestRef = db.collection("leaveRequests").document(requestId)
    val profileRef = db.collection("users").document(currentUserId)
    val auditRef = db.collection("audit_logs").document("${requestId}_CANCEL")
    db.runTransaction { transaction ->
        val profile = transaction.get(profileRef).toObject(UserProfile::class.java)
        require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == employeeId) {
            "Tài khoản không được hủy đơn của nhân viên này"
        }
        val request = transaction.get(requestRef).toObject(LeaveRequest::class.java)
            ?: error("Không tìm thấy đơn nghỉ phép")
        require(request.employeeId == employeeId && request.type == RequestType.LEAVE.name) {
            "Chỉ có thể hủy đơn nghỉ phép của bạn"
        }
        require(request.status == RequestStatus.PENDING.name) { "Chỉ có thể hủy đơn đang chờ duyệt" }
        transaction.update(requestRef, "status", RequestStatus.CANCELLED.name)
        transaction.set(auditRef, mapOf(
            "actorId" to currentUserId,
            "actorName" to request.employeeName,
            "action" to AuditAction.LEAVE_CANCEL.name,
            "targetType" to "leaveRequest",
            "targetId" to requestId,
            "reason" to "Employee cancelled pending leave request",
            "details" to "Employee cancelled pending leave request",
            "createdAt" to FieldValue.serverTimestamp()
        ))
    }.await()
}

suspend fun FirebaseRepository.reviewLeaveRequest(
    requestId: String,
    status: RequestStatus,
    reviewerId: String,
    reviewerName: String,
    note: String
) {
    val ref = db.collection("leaveRequests").document(requestId)
    db.runTransaction { transaction ->
        val current = transaction.get(ref).toObject(LeaveRequest::class.java)?.copy(id = requestId)
            ?: error("Không tìm thấy đơn từ")
        val cleanNote = note.trim()
        val reviewed = reviewRequest(current, status, reviewerId, reviewerName, cleanNote, com.google.firebase.Timestamp.now())
        val adjustmentRef = if (status == RequestStatus.APPROVED && current.type == RequestType.ATTENDANCE_ADJUSTMENT.name) {
            db.collection("attendanceAdjustments").document()
        } else null
        val adjustmentAuditRef = adjustmentRef?.let { db.collection("audit_logs").document(it.id) }
        val scheduleRef = if (status == RequestStatus.APPROVED && current.type == RequestType.SHIFT_CHANGE.name) {
            db.collection("workSchedules").document(scheduleDocumentId(current.employeeId, current.startDate))
        } else null
        val requestedShiftRef = if (scheduleRef != null) {
            db.collection("shifts").document(current.requestedShiftId.orEmpty())
        } else null
        val schedule = scheduleRef?.let { transaction.get(it).toObject(WorkSchedule::class.java) }
        val requestedShift = requestedShiftRef?.let { transaction.get(it).toObject(WorkShift::class.java)?.copy(id = current.requestedShiftId.orEmpty()) }
        if (scheduleRef != null) require(schedule != null) { "Không tìm thấy lịch ca cần đổi" }
        if (requestedShiftRef != null) {
            require(requestedShift != null && requestedShift.active) { "Ca muốn đổi không còn hoạt động" }
            validateScheduleShift(requestedShift)
        }

        val requestUpdate = mutableMapOf<String, Any?>(
            "status" to reviewed.status,
            "reviewerId" to reviewed.reviewerId,
            "reviewerName" to reviewed.reviewerName,
            "reviewedAt" to FieldValue.serverTimestamp(),
            "reviewNote" to reviewed.reviewNote
        )
        if (adjustmentRef != null && adjustmentAuditRef != null) {
            val date = LocalDate.parse(current.startDate)
            val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
            val checkIn = current.proposedCheckIn?.takeIf(String::isNotBlank)?.let { time ->
                date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
            }
            var checkOut = current.proposedCheckOut?.takeIf(String::isNotBlank)?.let { time ->
                date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
            }
            if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
                checkOut = date.plusDays(1).atTime(java.time.LocalTime.parse(current.proposedCheckOut.orEmpty())).atZone(zone).toInstant()
            }
            val adjustment = AttendanceAdjustment(
                employeeId = current.employeeId,
                employeeName = current.employeeName,
                scheduleDate = current.startDate,
                checkInAt = checkIn,
                checkOutAt = checkOut,
                reason = "Được duyệt từ đơn ${requestId}: ${current.reason}",
                actorId = reviewerId,
                actorName = reviewerName
            )
            validateAttendanceAdjustment(adjustment)
            transaction.set(adjustmentRef, adjustment.toFirestoreData())
            val adjustmentAudit = AuditLog(
                actorId = reviewerId,
                actorName = reviewerName,
                action = AuditAction.ATTENDANCE_ADJUST.name,
                targetType = "attendanceAdjustment",
                targetId = adjustmentRef.id,
                reason = adjustment.reason,
                details = "Điều chỉnh theo đơn $requestId; employeeId=${current.employeeId}; scheduleDate=${current.startDate}"
            )
            transaction.set(adjustmentAuditRef, adjustmentAudit.toFirestoreData())
            requestUpdate["appliedAdjustmentId"] = adjustmentRef.id
        }
        if (scheduleRef != null && schedule != null && requestedShift != null) {
            val updatedSchedule = schedule.copy(
                shiftId = requestedShift.id,
                shiftIds = listOf(requestedShift.id),
                shiftName = requestedShift.name,
                source = "REQUEST",
                note = "Đổi ca được duyệt từ đơn $requestId"
            )
            transaction.set(scheduleRef, updatedSchedule.toFirestoreData())
        }
        transaction.update(ref, requestUpdate)

        val reviewAudit = AuditLog(
            actorId = reviewerId,
            actorName = reviewerName,
            action = if (current.type == RequestType.SHIFT_CHANGE.name && status == RequestStatus.APPROVED)
                AuditAction.SHIFT_UPDATE.name else AuditAction.LEAVE_REVIEW.name,
            targetType = if (current.type == RequestType.SHIFT_CHANGE.name && status == RequestStatus.APPROVED)
                "workSchedule" else "leaveRequest",
            targetId = if (scheduleRef != null) scheduleRef.id else requestId,
            reason = cleanNote,
            details = "Xử lý đơn ${current.type} với trạng thái ${status.name}; requestId=$requestId"
        )
        transaction.set(db.collection("audit_logs").document("${requestId}_REVIEW"), reviewAudit.toFirestoreData())

        val notification = AppNotification(
            type = "REQUEST_RESULT",
            title = if (status == RequestStatus.APPROVED) "Đơn từ đã được duyệt" else "Đơn từ bị từ chối",
            body = "${current.type}: ${reviewed.reviewNote?.ifBlank { "Đã xử lý" } ?: "Đã xử lý"}",
            referenceId = requestId,
            recipientEmployeeId = current.employeeId,
            audienceLabel = current.employeeName
        )
        transaction.set(db.collection("notifications").document(), notification.toFirestoreData())
    }.await()
}

suspend fun FirebaseRepository.submitOvertimeRequest(request: OvertimeRequest): String {
    require(isOvertimeRequestWithinDeadline(request.workDate)) {
        "Đơn tăng ca phải gửi trước 18:00 trong ngày làm hoặc đăng ký cho ngày tương lai"
    }
    validateOvertimeRequest(request)
    require(request.status == OvertimeRequestStatus.PENDING.name) {
        "Đơn tăng ca phải ở trạng thái chờ duyệt"
    }
    require(request.createdAt == null && request.reviewerId == null && request.reviewerName == null &&
        request.reviewedAt == null && request.rejectionReason == null) {
        "Nhân viên không được tự nhập thông tin duyệt đơn"
    }
    require(request.reason.isNotBlank()) { "Vui lòng nhập lý do đăng ký tăng ca" }
    val profile = db.collection("users").document(currentUserId).get().await().toObject(UserProfile::class.java)
    require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == request.employeeId) {
        "Tài khoản không được gửi đơn tăng ca cho nhân viên này"
    }
    val employee = db.collection("employees").document(request.employeeId)
        .get(Source.SERVER)
        .await()
        .toObject(Employee::class.java)
        ?.copy(id = request.employeeId)
    require(employee != null && employee.active && employee.fullName.isNotBlank()) {
        "Nhân viên không còn hoạt động hoặc không tồn tại"
    }
    val canonicalRequest = request.copy(
        employeeName = employee.fullName,
        department = employee.department,
        reason = request.reason.trim()
    )
    validateOvertimeRequest(canonicalRequest)
    val requestId = scheduleDocumentId(request.employeeId, request.workDate)
    db.collection("overtimeRequests").document(requestId)
        .set(canonicalRequest.copy(id = requestId, status = OvertimeRequestStatus.PENDING.name).toOvertimeFirestoreData())
        .await()
    return requestId
}

suspend fun FirebaseRepository.assignOvertimeToEmployee(employeeId: String, workDate: String, reason: String) {
    val date = LocalDate.parse(workDate)
    require(date.dayOfWeek.value in 1..6) { "Không thể phân tăng ca vào Chủ nhật" }
    require(reason.trim().isNotBlank()) { "Cần nhập lý do phân tăng ca" }
    require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
    val employee = db.collection("employees").document(employeeId).get(Source.SERVER).await()
        .toObject(Employee::class.java)?.copy(id = employeeId)
    require(employee != null && employee.active && employee.fullName.isNotBlank()) {
        "Nhân viên không còn hoạt động hoặc không tồn tại"
    }
    val requestId = scheduleDocumentId(employeeId, workDate)
    val requestRef = db.collection("overtimeRequests").document(requestId)
    val auditRef = db.collection("audit_logs").document("${requestId}_OVERTIME_ASSIGN")
    db.runTransaction { transaction ->
        require(!transaction.get(requestRef).exists()) { "Nhân viên đã có ca tăng ca ngày này" }
        val request = OvertimeRequest(
            employeeId = employee.id, employeeName = employee.fullName, department = employee.department,
            workDate = workDate, reason = reason.trim(), status = OvertimeRequestStatus.APPROVED.name,
            reviewerId = currentUserId, reviewerName = currentUserName
        )
        validateOvertimeRequest(request)
        transaction.set(requestRef, request.toOvertimeFirestoreData() +
            ("reviewedAt" to FieldValue.serverTimestamp()))
        transaction.set(auditRef, AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.OVERTIME_ASSIGN.name, targetType = "overtimeRequest",
            targetId = requestId, reason = reason.trim(),
            details = "Phân ca tăng ca 18:00–22:00 ngày $workDate cho ${employee.fullName}"
        ).toFirestoreData())
    }.await()
}

suspend fun FirebaseRepository.reviewOvertimeRequest(
    requestId: String,
    status: OvertimeRequestStatus,
    reason: String
) {
    require(requestId.isNotBlank()) { "Mã đơn tăng ca không hợp lệ" }
    val reviewerId = currentUserId
    val reviewerName = currentUserName
    require(reviewerId.isNotBlank()) { "Chưa đăng nhập" }
    db.runTransaction { transaction ->
        val requestRef = db.collection("overtimeRequests").document(requestId)
        val current = transaction.get(requestRef).let(::overtimeRequest)
            ?: error("Không tìm thấy đơn tăng ca")
        val reviewed = applyOvertimeReview(
            request = current,
            status = status,
            reviewerId = reviewerId,
            reviewerName = reviewerName,
            reason = reason,
            reviewedAt = Instant.now()
        )
        transaction.update(requestRef, mapOf<String, Any?>(
            "status" to reviewed.status,
            "reviewerId" to reviewed.reviewerId,
            "reviewerName" to reviewed.reviewerName,
            "reviewedAt" to FieldValue.serverTimestamp(),
            "rejectionReason" to reviewed.rejectionReason
        ))
        val audit = AuditLog(
            actorId = reviewerId,
            actorName = reviewerName,
            action = AuditAction.OVERTIME_REVIEW.name,
            targetType = "overtimeRequest",
            targetId = requestId,
            reason = reviewed.rejectionReason.orEmpty(),
            details = "Xử lý đơn tăng ca với trạng thái ${status.name}"
        )
        validateAuditLog(audit)
        val auditId = "${requestId}_${AuditAction.OVERTIME_REVIEW.name}"
        transaction.set(
            db.collection("audit_logs").document(auditId),
            audit.toFirestoreData() + ("status" to reviewed.status)
        )
    }.await()
}

suspend fun FirebaseRepository.markNotificationRead(notificationId: String) {
    require(notificationId.isNotBlank()) { "Thông báo không hợp lệ" }
    db.collection("notifications").document(notificationId).update("read", true).await()
}

internal fun FirebaseRepository.overtimeRequest(document: DocumentSnapshot): OvertimeRequest? {
    if (!document.exists()) return null
    fun instant(field: String): Instant? = document.getTimestamp(field)?.toDate()?.toInstant()
    return OvertimeRequest(
        id = document.id,
        employeeId = document.getString("employeeId").orEmpty(),
        employeeName = document.getString("employeeName").orEmpty(),
        department = document.getString("department").orEmpty(),
        workDate = document.getString("workDate").orEmpty(),
        startTime = document.getString("startTime").orEmpty(),
        endTime = document.getString("endTime").orEmpty(),
        reason = document.getString("reason").orEmpty(),
        status = document.getString("status").orEmpty(),
        createdAt = instant("createdAt"),
        reviewerId = document.getString("reviewerId"),
        reviewerName = document.getString("reviewerName"),
        reviewedAt = instant("reviewedAt"),
        rejectionReason = document.getString("rejectionReason")
    )
}
