package vn.chamcong.iot.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.MetadataChanges
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.*

fun FirebaseRepository.observePerformanceReviews(): Flow<List<PerformanceReview>> = callbackFlow {
    val listener = db.collection("performanceReviews").addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        if (error != null) close(error)
        else if (snapshot != null && !snapshot.metadata.isFromCache) try {
            trySend(snapshot?.documents.orEmpty().mapNotNull { document ->
                document.toObject(PerformanceReview::class.java)?.copy(id = document.id)
            })
        } catch (invalid: Exception) { close(invalid) }
    }
    awaitClose { listener.remove() }
}

suspend fun FirebaseRepository.savePerformanceReview(review: PerformanceReview, previous: PerformanceReview? = null) {
    val clean = validatePerformanceReview(review)
    val id = performanceReviewDocumentId(clean)
    val actorId = currentUserId
    require(actorId.isNotBlank()) { "Chưa đăng nhập" }
    val ref = db.collection("performanceReviews").document(id)
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { transaction ->
        val profile = transaction.get(db.collection("users").document(actorId)).toObject(UserProfile::class.java)
        require(profile?.active == true && profile.role == UserRole.ADMIN.name) { "Chỉ quản lý được lưu đánh giá" }
        require(transaction.get(db.collection("employees").document(clean.employeeId)).exists()) { "Không tìm thấy nhân viên" }
        val snapshot = transaction.get(ref)
        val current = snapshot.toObject(PerformanceReview::class.java)?.copy(id = snapshot.id)
        require(if (previous == null) current == null else current == previous) {
            "Đánh giá đã thay đổi. Mở lại để kiểm tra trước khi lưu."
        }
        val audit = AuditLog(actorId = actorId, actorName = profile.displayName.ifBlank { currentUserName },
            action = AuditAction.EMPLOYEE_UPDATE.name, targetType = "performanceReview", targetId = id,
            details = "Đánh giá ${clean.period}: ${clean.score}/100; ${clean.note}")
        validateAuditLog(audit)
        transaction.set(ref, mapOf("employeeId" to clean.employeeId, "period" to clean.period,
            "score" to clean.score, "note" to clean.note))
        transaction.set(auditRef, audit.toFirestoreData())
    }.await()
}
