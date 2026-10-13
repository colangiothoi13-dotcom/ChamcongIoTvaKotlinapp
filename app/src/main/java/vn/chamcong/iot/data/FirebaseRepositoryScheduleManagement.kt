package vn.chamcong.iot.data

import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.*

suspend fun FirebaseRepository.setSupplementaryShiftActive(expected: WorkShift, active: Boolean) {
    require(expected.id.isNotBlank() && '/' !in expected.id && expected.category == ShiftCategory.SUPPLEMENTARY.name) {
        "Chỉ thay đổi trạng thái cấu hình ca bổ sung"
    }
    val actorId = currentUserId
    require(actorId.isNotBlank()) { "Chưa đăng nhập" }
    val ref = db.collection("shifts").document(expected.id)
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { tx ->
        val actor = tx.get(db.collection("users").document(actorId)).toObject(UserProfile::class.java)
        require(actor?.active == true && actor.role == UserRole.ADMIN.name) { "Chỉ quản lý được đổi trạng thái ca" }
        val current = tx.get(ref).toObject(WorkShift::class.java)?.copy(id = ref.id)
        require(current == expected) { "Cấu hình ca đã thay đổi. Mở lại trước khi lưu." }
        val audit = AuditLog(actorId = actorId, actorName = actor.displayName.ifBlank { currentUserName },
            action = AuditAction.SHIFT_UPDATE.name, targetType = "shift", targetId = ref.id,
            details = "${if (active) "Kích hoạt" else "Ngừng"} cấu hình ca ${expected.name}")
        validateAuditLog(audit)
        tx.update(ref, "active", active)
        tx.set(auditRef, audit.toFirestoreData())
    }.await()
}

suspend fun FirebaseRepository.removeWorkSchedule(expected: WorkSchedule) {
    require(expected.id.isNotBlank() && '/' !in expected.id) { "Mã lịch làm việc không hợp lệ" }
    val actorId = currentUserId
    require(actorId.isNotBlank()) { "Chưa đăng nhập" }
    val ref = db.collection("workSchedules").document(expected.id)
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { tx ->
        val actor = tx.get(db.collection("users").document(actorId)).toObject(UserProfile::class.java)
        require(actor?.active == true && actor.role == UserRole.ADMIN.name) { "Chỉ quản lý được bỏ lịch làm việc" }
        val current = tx.get(ref).toObject(WorkSchedule::class.java)?.copy(id = ref.id)
        require(current == expected) { "Lịch làm việc đã thay đổi. Mở lại trước khi bỏ lịch." }
        val audit = AuditLog(actorId = actorId, actorName = actor.displayName.ifBlank { currentUserName },
            action = AuditAction.SHIFT_UPDATE.name, targetType = "workSchedule", targetId = ref.id,
            details = "Bỏ lịch ngày ${expected.date} của ${expected.employeeName}")
        validateAuditLog(audit)
        tx.delete(ref)
        tx.set(auditRef, audit.toFirestoreData())
    }.await()
}
