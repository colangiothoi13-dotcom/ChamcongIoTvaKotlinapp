package vn.chamcong.iot.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceRecordHistory
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import java.time.LocalDate

private suspend fun Query.completeAttendanceHistory(): List<DocumentSnapshot> {
    val rows = mutableListOf<DocumentSnapshot>()
    var pageQuery = limit(200)
    while (true) {
        val page = pageQuery.get(Source.SERVER).await()
        rows += page.documents
        if (page.size() < 200) return rows
        require(rows.size < 5000) { "Lịch sử quá lớn; chưa thể tải đầy đủ." }
        pageQuery = startAfter(page.documents.last()).limit(200)
    }
}

suspend fun FirebaseRepository.fetchAttendanceRecordHistory(
    attendanceId: String,
    employeeId: String,
    scheduleDate: String
): AttendanceRecordHistory = coroutineScope {
    require(attendanceId.isNotBlank() && employeeId.isNotBlank()) { "Thiếu lượt chấm hoặc nhân viên cần xem." }
    val requestedDate = LocalDate.parse(scheduleDate).toString()
    val actorId = currentUserId
    require(actorId.isNotBlank()) { "Chưa đăng nhập." }
    val admin = db.collection("users").document(actorId).get(Source.SERVER).await()
    require(admin.getString("role") == "ADMIN" && admin.getBoolean("active") == true) {
        "Chỉ Admin đang hoạt động được xem lịch sử chấm công."
    }
    val raw = db.collection("attendance").document(attendanceId).get(Source.SERVER).await()
    val source = raw.toObject(Attendance::class.java)?.copy(id = raw.id)
        ?: error("Lượt chấm không còn tồn tại.")
    require(source.employeeId == employeeId) { "Nhân viên của lượt chấm đã thay đổi. Hãy mở lại chi tiết." }
    val date = source.scheduleDate?.let { LocalDate.parse(it).toString() } ?: requestedDate
    val classifications = async {
        db.collection("attendanceClassificationOverrides").whereEqualTo("attendanceId", attendanceId)
            .completeAttendanceHistory().map { document ->
                document.toAttendanceClassificationOverride()
                    ?: error("Lịch sử phân loại có bản ghi chưa thể đọc đầy đủ.")
            }.sortedByDescending { it.createdAt }
    }
    val adjustments = async {
        db.collection("attendanceAdjustments").whereEqualTo("employeeId", employeeId)
            .whereEqualTo("scheduleDate", date).completeAttendanceHistory().map { document ->
                document.toAttendanceAdjustment()
                    ?: error("Lịch sử điều chỉnh có bản ghi chưa thể đọc đầy đủ.")
            }.sortedByDescending { it.createdAt }
    }
    val reviews = async {
        db.collection("offScheduleReviews").whereEqualTo("employeeId", employeeId)
            .whereEqualTo("scheduleDate", date).completeAttendanceHistory().map { document ->
                document.toObject(OffScheduleAttendanceReview::class.java)?.copy(id = document.id)
                    ?: error("Lịch sử chấm ngoài lịch có bản ghi chưa thể đọc đầy đủ.")
            }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
    }
    val classificationRows = classifications.await()
    val adjustmentRows = adjustments.await()
    val reviewRows = reviews.await()
    val expectedTargets = buildMap {
        classificationRows.forEach { put(it.id, "attendanceClassificationOverride") }
        adjustmentRows.forEach { put(it.id, "attendanceAdjustment") }
        reviewRows.forEach { put(it.id, "offScheduleReview") }
    }
    val audits = expectedTargets.keys.chunked(30).flatMap { ids ->
        db.collection("audit_logs").whereIn(FieldPath.documentId(), ids).get(Source.SERVER).await()
            .documents.mapNotNull { document ->
                val createdAt = document.getTimestamp("createdAt") ?: return@mapNotNull null
                document.toObject(AuditLog::class.java)?.copy(id = document.id, createdAt = createdAt)?.takeIf {
                    it.targetId == document.id && it.targetType == expectedTargets[document.id]
                }
            }
    }.sortedByDescending { it.createdAt.toDate().time }
    require(currentUserId == actorId) { "Phiên đăng nhập đã thay đổi." }
    AttendanceRecordHistory(source, date, classificationRows, adjustmentRows, reviewRows, audits)
}
