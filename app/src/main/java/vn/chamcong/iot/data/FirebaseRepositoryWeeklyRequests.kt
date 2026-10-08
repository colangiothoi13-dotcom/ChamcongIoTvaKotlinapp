// Chức năng: Gửi, duyệt và kiểm tra đăng ký lịch làm việc hằng tuần.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.domain.validateScheduleShift
import vn.chamcong.iot.domain.isWeeklyScheduleSubmissionOpen
import vn.chamcong.iot.model.*
import java.time.LocalDate

suspend fun FirebaseRepository.submitWeeklyScheduleRequest(request: WeeklyScheduleRequest): String {
    val employeeId = request.employeeId.trim()
    require(employeeId.isNotBlank()) { "Chưa chọn nhân viên" }
    val weekStart = validatedMonday(request.weekStart)
    require(isWeeklyScheduleSubmissionOpen(LocalDate.parse(weekStart))) {
        "Đã quá hạn gửi đăng ký lịch tuần (12:00 thứ Bảy)"
    }
    val shiftsByDate = validatedWeeklyShiftMap(weekStart, request.shiftsByDate)
    val reason = request.reason.trim()
    require(reason.length <= 500) { "Ghi chú không được vượt quá 500 ký tự" }

    val profile = db.collection("users").document(currentUserId)
        .get(Source.SERVER).await().toObject(UserProfile::class.java)
    require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == employeeId) {
        "Tài khoản không được gửi lịch cho nhân viên này"
    }
    val employee = db.collection("employees").document(employeeId)
        .get(Source.SERVER).await().toObject(Employee::class.java)?.copy(id = employeeId)
    require(employee != null && employee.active && employee.fullName.isNotBlank()) {
        "Nhân viên không còn hoạt động hoặc không tồn tại"
    }
    val selectedShifts = shiftsByDate.values.flatten().distinct().associateWith { requireActiveAssignableShift(it) }
    validateWeeklyShiftCategories(shiftsByDate, selectedShifts)

    val requestId = weeklyScheduleRequestId(employeeId, weekStart)
    require(request.id.isBlank() || request.id == requestId) { "Mã đăng ký lịch tuần không hợp lệ" }
    val requestRef = db.collection("weeklyScheduleRequests").document(requestId)
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { transaction ->
        val existing = transaction.get(requestRef).let(::weeklyScheduleRequest)
        if (existing != null) {
            require(existing.employeeId == employeeId && existing.weekStart == weekStart) {
                "Đăng ký lịch tuần không khớp với nhân viên"
            }
            require(existing.status in setOf(
                WeeklyScheduleRequestStatus.PENDING,
                WeeklyScheduleRequestStatus.NEEDS_REVISION
            )) { "Đăng ký lịch tuần đã được duyệt và không thể sửa" }
        }
        val stored = WeeklyScheduleRequest(
            id = requestId,
            employeeId = employeeId,
            employeeName = employee.fullName,
            department = employee.department,
            weekStart = weekStart,
            shiftsByDate = shiftsByDate,
            status = WeeklyScheduleRequestStatus.PENDING,
            reason = reason,
            createdAt = existing?.createdAt ?: Timestamp.now()
        )
        transaction.set(requestRef, stored.toWeeklyScheduleFirestoreData(
            createdAt = existing?.createdAt ?: FieldValue.serverTimestamp()
        ))
        transaction.set(auditRef, AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.SHIFT_UPDATE.name,
            targetType = "weeklyScheduleRequest",
            targetId = requestId,
            reason = reason,
            details = "Gửi/cập nhật đăng ký lịch tuần bắt đầu $weekStart"
        ).toFirestoreData())
    }.await()
    return requestId
}

suspend fun FirebaseRepository.reviewWeeklyScheduleRequest(
    requestId: String,
    status: WeeklyScheduleRequestStatus,
    reviewNote: String
) {
    require(requestId.isNotBlank()) { "Mã đăng ký lịch tuần không hợp lệ" }
    require(status == WeeklyScheduleRequestStatus.APPROVED || status == WeeklyScheduleRequestStatus.NEEDS_REVISION) {
        "Admin chỉ có thể duyệt hoặc yêu cầu chỉnh sửa"
    }
    val cleanReviewNote = reviewNote.trim()
    require(status != WeeklyScheduleRequestStatus.NEEDS_REVISION || cleanReviewNote.isNotBlank()) {
        "Vui lòng ghi lý do yêu cầu chỉnh sửa"
    }
    require(cleanReviewNote.length <= 1000) { "Phản hồi không được vượt quá 1.000 ký tự" }

    val reviewerId = currentUserId
    val reviewerName = currentUserName
    require(reviewerId.isNotBlank()) { "Chưa đăng nhập" }
    val auditRef = db.collection("audit_logs").document()
    val notificationRef = db.collection("notifications").document()
    db.runTransaction { transaction ->
        val requestRef = db.collection("weeklyScheduleRequests").document(requestId)
        val current = transaction.get(requestRef).let(::weeklyScheduleRequest)
            ?: error("Không tìm thấy đăng ký lịch tuần")
        require(current.status == WeeklyScheduleRequestStatus.PENDING) {
            "Chỉ đăng ký đang chờ duyệt mới được xử lý"
        }
        if (status == WeeklyScheduleRequestStatus.APPROVED) {
            val employee = transaction.get(db.collection("employees").document(current.employeeId))
                .toObject(Employee::class.java)
            require(employee != null && employee.active) {
                "Nhân viên không còn hoạt động hoặc không tồn tại; không thể duyệt đăng ký lịch tuần"
            }
        }

        val normalizedShifts = if (status == WeeklyScheduleRequestStatus.APPROVED) {
            validatedWeeklyShiftMap(current.weekStart, current.shiftsByDate)
        } else emptyMap()
        val shifts = if (status == WeeklyScheduleRequestStatus.APPROVED) {
            val shiftRefs = normalizedShifts.values.flatten().distinct().associateWith {
                db.collection("shifts").document(it)
            }
            shiftRefs.mapValues { (_, ref) ->
                val snapshot = transaction.get(ref)
                val shift = snapshot.toObject(WorkShift::class.java)?.copy(id = snapshot.id)
                    ?: error("Một trong các ca đã bị xóa")
                require(shift.active && canAssignScheduleShift(shift)) { "Ca ${shift.name} không còn được đăng ký" }
                validateScheduleShift(shift)
                shift
            }.also { validateWeeklyShiftCategories(normalizedShifts, it) }
        } else emptyMap()
        val scheduleRefs = if (status == WeeklyScheduleRequestStatus.APPROVED) {
            normalizedShifts.keys.associateWith { date ->
                db.collection("workSchedules").document(scheduleDocumentId(current.employeeId, date))
            }
        } else emptyMap()
        val previousSchedules = scheduleRefs.mapValues { (_, ref) ->
            transaction.get(ref).toObject(WorkSchedule::class.java)
        }

        transaction.update(requestRef, mapOf(
            "status" to status.name,
            "reviewNote" to cleanReviewNote,
            "reviewerId" to reviewerId,
            "reviewerName" to reviewerName,
            "reviewedAt" to FieldValue.serverTimestamp()
        ))

        if (status == WeeklyScheduleRequestStatus.APPROVED) {
            normalizedShifts.forEach { (date, selectedIds) ->
                val scheduleRef = scheduleRefs.getValue(date)
                if (selectedIds.isEmpty()) {
                    if (previousSchedules[date] != null) transaction.delete(scheduleRef)
                    return@forEach
                }
                val selectedShifts = selectedIds.map { shifts.getValue(it) }
                val firstShift = selectedShifts.first()
                val prior = previousSchedules[date]
                val schedule = WorkSchedule(
                    employeeId = current.employeeId,
                    employeeName = current.employeeName,
                    department = current.department,
                    shiftId = firstShift.id,
                    shiftIds = selectedIds,
                    shiftName = firstShift.name,
                    date = date,
                    overtimeHours = prior?.overtimeHours ?: 0,
                    workedHoursOverride = prior?.workedHoursOverride,
                    adjustmentNote = prior?.adjustmentNote.orEmpty(),
                    assignedBy = reviewerId,
                    source = "WEEKLY_EMPLOYEE",
                    note = prior?.note.orEmpty()
                )
                transaction.set(scheduleRef, schedule.toFirestoreData())
            }
        }

        transaction.set(auditRef, AuditLog(
            actorId = reviewerId,
            actorName = reviewerName,
            action = AuditAction.SHIFT_UPDATE.name,
            targetType = "weeklyScheduleRequest",
            targetId = requestId,
            reason = if (status == WeeklyScheduleRequestStatus.NEEDS_REVISION) cleanReviewNote else "",
            details = if (status == WeeklyScheduleRequestStatus.APPROVED) {
                "Duyệt đăng ký lịch tuần ${current.weekStart}; ca theo ngày: " +
                    normalizedShifts.entries.joinToString { (date, ids) -> "$date=${ids.joinToString("+").ifBlank { "OFF" }}" }
            } else {
                "Yêu cầu sửa đăng ký lịch tuần ${current.weekStart}"
            }
        ).toFirestoreData())
        transaction.set(notificationRef, AppNotification(
            type = "WEEKLY_SCHEDULE_RESULT",
            title = if (status == WeeklyScheduleRequestStatus.APPROVED) "Lịch tuần đã được duyệt" else "Cần chỉnh sửa đăng ký lịch tuần",
            body = cleanReviewNote.ifBlank { "Đăng ký lịch tuần ${current.weekStart} đã được duyệt." },
            referenceId = requestId,
            recipientEmployeeId = current.employeeId,
            audienceLabel = current.employeeName
        ).toFirestoreData())
    }.await()
}

internal fun FirebaseRepository.validatedMonday(rawWeekStart: String): String {
    val monday = runCatching { LocalDate.parse(rawWeekStart) }
        .getOrElse { throw IllegalArgumentException("Tuần phải có dạng yyyy-MM-dd") }
    require(monday.toString() == rawWeekStart && monday.dayOfWeek == java.time.DayOfWeek.MONDAY) {
        "Ngày bắt đầu tuần phải là thứ Hai theo dạng yyyy-MM-dd"
    }
    return monday.toString()
}

private fun FirebaseRepository.validatedWeeklyShiftMap(
    weekStart: String,
    rawShiftsByDate: Map<String, List<String>>
): Map<String, List<String>> {
    val monday = LocalDate.parse(validatedMonday(weekStart))
    val workDates = (0L..5L).map { monday.plusDays(it).toString() }
    require(rawShiftsByDate.keys.all { it in workDates }) { "Chỉ có thể đăng ký từ thứ Hai đến thứ Bảy" }
    rawShiftsByDate.forEach { (date, selected) ->
        require(selected.size <= 2 && selected.distinct().size == selected.size) {
            "Mỗi ngày chỉ có thể chọn tối đa hai ca khác nhau"
        }
        require(selected.all(String::isNotBlank)) { "Mã ca không hợp lệ" }
        require(date == runCatching { LocalDate.parse(date).toString() }.getOrNull()) {
            "Ngày đăng ký phải có dạng yyyy-MM-dd"
        }
    }
    return workDates.associateWith { date -> rawShiftsByDate[date].orEmpty().toList() }
}

private fun FirebaseRepository.validateWeeklyShiftCategories(
    shiftsByDate: Map<String, List<String>>,
    shiftsById: Map<String, WorkShift>
) {
    shiftsByDate.values.forEach { selectedIds ->
        val categories = selectedIds.map { id ->
            shiftsById[id]?.category ?: error("Một trong các ca đã bị xóa")
        }
        require(categories.distinct().size == categories.size) {
            "Trong ngày chỉ có thể chọn một ca sáng và một ca chiều"
        }
    }
}

private fun FirebaseRepository.weeklyScheduleRequestId(employeeId: String, weekStart: String): String = "${employeeId}_$weekStart"

internal fun FirebaseRepository.weeklyScheduleRequest(document: DocumentSnapshot): WeeklyScheduleRequest? {
    val status = runCatching {
        WeeklyScheduleRequestStatus.valueOf(document.getString("status").orEmpty())
    }.getOrNull() ?: return null
    val rawShifts = document.get("shiftsByDate") as? Map<*, *>
    val shiftsByDate = if (rawShifts == null) {
        mapOf("__INVALID_DATE__" to listOf("__INVALID_SHIFT__"))
    } else rawShifts.entries.associate { (key, value) ->
        val date = key as? String ?: "__INVALID_DATE__"
        val ids = (value as? List<*>)?.map { it as? String ?: "__INVALID_SHIFT__" }
            ?: listOf("__INVALID_SHIFT__")
        date to ids
    }
    return WeeklyScheduleRequest(
        id = document.id,
        employeeId = document.getString("employeeId").orEmpty(),
        employeeName = document.getString("employeeName").orEmpty(),
        department = document.getString("department").orEmpty(),
        weekStart = document.getString("weekStart").orEmpty(),
        shiftsByDate = shiftsByDate,
        status = status,
        reason = document.getString("reason").orEmpty(),
        reviewNote = document.getString("reviewNote"),
        reviewerId = document.getString("reviewerId"),
        reviewerName = document.getString("reviewerName"),
        createdAt = document.getTimestamp("createdAt") ?: Timestamp.now(),
        reviewedAt = document.getTimestamp("reviewedAt")
    )
}

private fun WeeklyScheduleRequest.toWeeklyScheduleFirestoreData(createdAt: Any): Map<String, Any?> = mapOf(
    "id" to id,
    "employeeId" to employeeId,
    "employeeName" to employeeName,
    "department" to department,
    "weekStart" to weekStart,
    "shiftsByDate" to shiftsByDate,
    "status" to status.name,
    "reason" to reason,
    "reviewNote" to reviewNote,
    "reviewerId" to reviewerId,
    "reviewerName" to reviewerName,
    "createdAt" to createdAt,
    "reviewedAt" to reviewedAt
)
