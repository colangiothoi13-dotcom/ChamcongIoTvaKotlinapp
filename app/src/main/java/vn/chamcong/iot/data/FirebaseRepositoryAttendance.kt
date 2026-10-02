// Chức năng: Đọc, điều chỉnh và phân loại bản ghi chấm công trên Firebase.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.domain.validateAttendanceClassificationOverride
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.Instant
import vn.chamcong.iot.domain.validateAttendanceAdjustment
import java.util.UUID

fun FirebaseRepository.observeAttendanceAdjustments(): Flow<List<AttendanceAdjustment>> = observeAdjustments(
    db.collection("attendanceAdjustments").orderBy("createdAt", Query.Direction.DESCENDING)
)

fun FirebaseRepository.observeOffScheduleAttendanceReviews(): Flow<List<OffScheduleAttendanceReview>> = observeOffScheduleReviews(
    db.collection("offScheduleReviews")
        .orderBy("createdAt", Query.Direction.DESCENDING)
)

fun FirebaseRepository.observeEmployeeOffScheduleAttendanceReviews(employeeId: String): Flow<List<OffScheduleAttendanceReview>> {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    return observeOffScheduleReviews(db.collection("offScheduleReviews").whereEqualTo("employeeId", employeeId))
}

private fun observeOffScheduleReviews(query: Query): Flow<List<OffScheduleAttendanceReview>> = callbackFlow {
    val listener = query.addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty()
                .mapNotNull { document ->
                    document.toObject(OffScheduleAttendanceReview::class.java)?.copy(id = document.id)?.let { review ->
                        when {
                            review.decision == "REJECT" && review.status == "PENDING" -> review.copy(status = "REJECTED")
                            review.decision == "APPROVE" && review.status == "PENDING" -> review.copy(status = "APPROVED")
                            else -> review
                        }
                    }
                }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L })
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeEmployeeAttendanceAdjustments(employeeId: String): Flow<List<AttendanceAdjustment>> {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    // Sort locally so the employee shell needs no additional composite index.
    return observeAdjustments(db.collection("attendanceAdjustments").whereEqualTo("employeeId", employeeId))
}

fun FirebaseRepository.observeAttendanceClassificationOverrides(): Flow<List<AttendanceClassificationOverride>> =
    observeClassificationOverrides(db.collection("attendanceClassificationOverrides"))

fun FirebaseRepository.observeEmployeeAttendanceClassificationOverrides(employeeId: String): Flow<List<AttendanceClassificationOverride>> {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    return observeClassificationOverrides(
        db.collection("attendanceClassificationOverrides").whereEqualTo("employeeId", employeeId)
    )
}

private fun FirebaseRepository.observeClassificationOverrides(query: Query): Flow<List<AttendanceClassificationOverride>> = callbackFlow {
    val listener = query.addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { it.toAttendanceClassificationOverride() }
            .sortedByDescending { it.createdAt })
    }
    awaitClose { listener.remove() }
}

private fun FirebaseRepository.observeAdjustments(query: Query): Flow<List<AttendanceAdjustment>> = callbackFlow {
    val listener = query.addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { it.toAttendanceAdjustment() }
            .sortedByDescending { it.createdAt })
    }
    awaitClose { listener.remove() }
}

suspend fun FirebaseRepository.saveAttendanceAdjustment(adjustment: AttendanceAdjustment): String {
    require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
    val stored = adjustment.copy(actorId = currentUserId, actorName = currentUserName, reason = adjustment.reason.trim())
    validateAttendanceAdjustment(stored)
    val collection = db.collection("attendanceAdjustments")
    val previous = collection.whereEqualTo("scheduleDate", stored.scheduleDate)
        .get(Source.SERVER).await().documents
        .mapNotNull { it.toAttendanceAdjustment() }
        .filter { it.employeeId == stored.employeeId }
        .maxByOrNull { it.createdAt }
    val ref = collection.document()
    // Shared id lets rules enforce the audit/adjustment pair in both directions.
    val auditRef = db.collection("audit_logs").document(ref.id)
    fun values(value: AttendanceAdjustment?): String = value?.let {
        "id=${it.id}, checkInAt=${it.checkInAt}, checkOutAt=${it.checkOutAt}, workedHoursOverride=${it.workedHoursOverride}"
    } ?: "none"
    val audit = AuditLog(
        actorId = currentUserId, actorName = currentUserName,
        action = AuditAction.ATTENDANCE_ADJUST.name, targetType = "attendanceAdjustment",
        targetId = ref.id, reason = stored.reason,
        details = "employeeId=${stored.employeeId}; scheduleDate=${stored.scheduleDate}; " +
            "before=[${values(previous)}]; after=[${values(stored.copy(id = ref.id))}]"
    )
    validateAuditLog(audit)
    db.runBatch { batch ->
        batch.set(ref, stored.toFirestoreData())
        batch.set(auditRef, audit.toFirestoreData())
    }.await()
    return ref.id
}

suspend fun FirebaseRepository.saveAttendanceClassificationOverride(
    requested: AttendanceClassificationOverride
): String {
    require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
    require(requested.attendanceId.isNotBlank()) { "Thiếu mã lượt chấm cần sửa" }
    val scanSnapshot = db.collection("attendance").document(requested.attendanceId)
        .get(Source.SERVER).await()
    require(scanSnapshot.exists()) { "Không tìm thấy lượt chấm gốc" }
    val source = scanSnapshot.toObject(Attendance::class.java)?.copy(id = scanSnapshot.id)
        ?: error("Không thể đọc lượt chấm gốc")
    val sourceTimestamp = source.timestamp.toDate().toInstant()
    require(source.employeeId == requested.employeeId && sourceTimestamp == requested.sourceTimestamp &&
        source.type == requested.sourceType && source.status == requested.sourceStatus &&
        source.resolutionStatus == requested.sourceResolutionStatus) {
        "Lượt chấm đã thay đổi. Đóng hộp thoại và mở lại trước khi sửa."
    }
    require((source.resolutionStatus == AttendanceResolutionStatus.ACCEPTED.name &&
        source.type in AttendanceType.entries.map { it.name }) ||
        (source.type == "SCAN" && source.resolutionStatus == AttendanceResolutionStatus.PENDING.name)) {
        "Chỉ xử lý lượt quét đang chờ hoặc lượt chấm đã được chấp nhận"
    }

    val collection = db.collection("attendanceClassificationOverrides")
    // Sorting on the server here would require a composite index for attendanceId + createdAt.
    val previous = collection.whereEqualTo("attendanceId", requested.attendanceId)
        .get(Source.SERVER).await().documents
        .mapNotNull { it.toAttendanceClassificationOverride() }
        .maxByOrNull { it.createdAt }
    val previousType = previous?.correctedType ?: source.type
    val previousStatus = previous?.correctedStatus ?: source.status
    val ref = collection.document()
    val stored = requested.copy(
        id = ref.id,
        employeeName = source.employeeName,
        scheduleDate = requested.scheduleDate,
        sourceTimestamp = sourceTimestamp,
        sourceType = source.type,
        sourceStatus = source.status,
        sourceResolutionStatus = source.resolutionStatus,
        previousType = previousType,
        previousStatus = previousStatus,
        reason = requested.reason.trim(),
        actorId = currentUserId,
        actorName = currentUserName
    )
    validateAttendanceClassificationOverride(stored)

    val sourceDescription = "timestamp=$sourceTimestamp, type=${source.type}, status=${source.status}, " +
        "resolutionStatus=${source.resolutionStatus}, scheduleDate=${source.scheduleDate}, shiftId=${source.shiftId}"
    val audit = AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = AuditAction.ATTENDANCE_CLASSIFICATION_CORRECT.name,
        targetType = "attendanceClassificationOverride",
        targetId = ref.id,
        reason = stored.reason,
        details = "attendanceId=${source.id}; rawBefore=[$sourceDescription]; rawAfter=[$sourceDescription]; " +
            "effectiveBefore=[type=$previousType, status=$previousStatus]; " +
            "effectiveAfter=[type=${stored.correctedType}, status=${stored.correctedStatus}]"
    )
    validateAuditLog(audit)
    db.runBatch { batch ->
        batch.set(ref, stored.toFirestoreData())
        batch.set(db.collection("audit_logs").document(ref.id), audit.toFirestoreData())
    }.await()
    return ref.id
}

suspend fun FirebaseRepository.submitOffScheduleAttendanceReview(
    employeeId: String,
    employeeName: String,
    scheduleDate: String,
    shift: WorkShift,
    decision: String,
    reason: String
) {
    require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
    require(decision in setOf("APPROVE", "REJECT")) { "Lựa chọn xử lý không hợp lệ" }
    require(shift.active && shift.category in setOf(ShiftCategory.MORNING.name, ShiftCategory.EVENING.name)) {
        "Chỉ có thể duyệt vào ca sáng hoặc ca chiều đang hoạt động"
    }
    require(reason.trim().isNotBlank()) { "Vui lòng nhập lý do xử lý" }
    require(reason.trim().length <= 500) { "Lý do không được quá 500 ký tự" }
    val date = LocalDate.parse(scheduleDate).toString()
    val id = UUID.randomUUID().toString()
    val review = mapOf(
        "id" to id,
        "employeeId" to employeeId,
        "employeeName" to employeeName,
        "scheduleDate" to date,
        "shiftId" to shift.id,
        "shiftName" to shift.name,
        "decision" to decision,
        "reason" to reason.trim(),
        "status" to "PENDING",
        "reviewerId" to currentUserId,
        "reviewerName" to currentUserName,
        "createdAt" to FieldValue.serverTimestamp()
    )
    val audit = AuditLog(
        actorId = currentUserId, actorName = currentUserName,
        action = AuditAction.OFF_SCHEDULE_REVIEW.name,
        targetType = "offScheduleReview", targetId = id,
        reason = reason.trim(),
        details = "$decision $employeeId $date ${shift.id}"
    )
    validateAuditLog(audit)
    db.runBatch { batch ->
        batch.set(db.collection("offScheduleReviews").document(id), review)
        batch.set(db.collection("audit_logs").document(id), audit.toFirestoreData())
    }.await()
}

internal fun AttendanceAdjustment.toFirestoreData(): Map<String, Any?> = mapOf(
    "employeeId" to employeeId, "employeeName" to employeeName, "scheduleDate" to scheduleDate,
    "checkInAt" to checkInAt?.let { Timestamp(it.epochSecond, it.nano) },
    "checkOutAt" to checkOutAt?.let { Timestamp(it.epochSecond, it.nano) },
    "workedHoursOverride" to workedHoursOverride, "reason" to reason,
    "actorId" to actorId, "actorName" to actorName, "createdAt" to FieldValue.serverTimestamp()
)

private fun AttendanceClassificationOverride.toFirestoreData(): Map<String, Any?> = mapOf(
    "attendanceId" to attendanceId,
    "employeeId" to employeeId,
    "employeeName" to employeeName,
    "scheduleDate" to scheduleDate,
    "sourceTimestamp" to Timestamp(sourceTimestamp.epochSecond, sourceTimestamp.nano),
    "sourceType" to sourceType,
    "sourceStatus" to sourceStatus,
    "sourceResolutionStatus" to sourceResolutionStatus,
    "previousType" to previousType,
    "previousStatus" to previousStatus,
    "correctedType" to correctedType,
    "correctedStatus" to correctedStatus,
    "reason" to reason,
    "actorId" to actorId,
    "actorName" to actorName,
    "createdAt" to FieldValue.serverTimestamp()
)

private fun DocumentSnapshot.toAttendanceAdjustment(): AttendanceAdjustment? {
    // Unacknowledged server timestamps must not win latest-adjustment selection.
    val created = getTimestamp("createdAt") ?: return null
    fun instant(field: String): Instant? = getTimestamp(field)?.let {
        Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong())
    }
    return AttendanceAdjustment(
        id = id, employeeId = getString("employeeId").orEmpty(),
        employeeName = getString("employeeName").orEmpty(), scheduleDate = getString("scheduleDate").orEmpty(),
        checkInAt = instant("checkInAt"), checkOutAt = instant("checkOutAt"),
        workedHoursOverride = (get("workedHoursOverride") as? Number)?.toDouble(),
        reason = getString("reason").orEmpty(), actorId = getString("actorId").orEmpty(),
        actorName = getString("actorName").orEmpty(),
        createdAt = Instant.ofEpochSecond(created.seconds, created.nanoseconds.toLong())
    )
}

internal fun DocumentSnapshot.toAttendanceClassificationOverride(): AttendanceClassificationOverride? {
    val created = getTimestamp("createdAt") ?: return null
    val timestamp = getTimestamp("sourceTimestamp") ?: return null
    return AttendanceClassificationOverride(
        id = id,
        attendanceId = getString("attendanceId").orEmpty(),
        employeeId = getString("employeeId").orEmpty(),
        employeeName = getString("employeeName").orEmpty(),
        scheduleDate = getString("scheduleDate").orEmpty(),
        sourceTimestamp = Instant.ofEpochSecond(timestamp.seconds, timestamp.nanoseconds.toLong()),
        sourceType = getString("sourceType").orEmpty(),
        sourceStatus = getString("sourceStatus").orEmpty(),
        sourceResolutionStatus = getString("sourceResolutionStatus").orEmpty(),
        previousType = getString("previousType").orEmpty(),
        previousStatus = getString("previousStatus").orEmpty(),
        correctedType = getString("correctedType").orEmpty(),
        correctedStatus = getString("correctedStatus").orEmpty(),
        reason = getString("reason").orEmpty(),
        actorId = getString("actorId").orEmpty(),
        actorName = getString("actorName").orEmpty(),
        createdAt = Instant.ofEpochSecond(created.seconds, created.nanoseconds.toLong())
    )
}
