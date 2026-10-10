package vn.chamcong.iot.data

import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.AuditAction
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.UserRole
import vn.chamcong.iot.model.previewPayrollRecalculation
import vn.chamcong.iot.model.requirePayrollPreviewCurrent
import vn.chamcong.iot.model.validatePayrollRecalculationReason

/** This is called only after an Admin reviews and confirms a recalculation preview. */
suspend fun FirebaseRepository.recalculatePayroll(
    previous: Payroll,
    hoursWorked: Double,
    hourlyRate: Long,
    bonus: Long = previous.bonus,
    deduction: Long = previous.deduction,
    reason: String
): Payroll {
    val next = previewPayrollRecalculation(previous, hoursWorked, hourlyRate, bonus, deduction)
    val cleanReason = validatePayrollRecalculationReason(reason)
    val actorId = currentUserId
    require(actorId.isNotBlank()) { "Chưa đăng nhập" }
    val ref = db.collection("payroll").document("${previous.employeeId}_${previous.month}")
    val revisionRef = ref.collection("revisions").document(next.revision.toString())
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { transaction ->
        val profile = transaction.get(db.collection("users").document(actorId)).toObject(UserProfile::class.java)
        require(profile?.active == true && profile.role == UserRole.ADMIN.name) { "Chỉ quản lý được tính lại phiếu lương" }
        val employee = transaction.get(db.collection("employees").document(previous.employeeId)).toObject(Employee::class.java)
            ?: error("Không tìm thấy nhân viên")
        val snapshot = transaction.get(ref)
        val current = snapshot.toObject(Payroll::class.java) ?: error("Phiếu lương không còn tồn tại")
        requirePayrollPreviewCurrent(previous, current, hourlyRate, employee.baseSalary)
        require(!transaction.get(revisionRef).exists()) { "Lịch sử tính lại đã tồn tại. Hãy tải lại phiếu lương" }
        val before = snapshot.data ?: error("Không đọc được phiếu lương")
        val after = before.toMutableMap().apply {
            this["hoursWorked"] = next.hoursWorked
            this["hourlyRate"] = next.hourlyRate
            this["baseSalary"] = next.baseSalary
            this["bonus"] = next.bonus
            this["deduction"] = next.deduction
            this["revision"] = next.revision
        }
        val audit = AuditLog(
            actorId = actorId, actorName = profile.displayName.ifBlank { currentUserName },
            action = AuditAction.EMPLOYEE_UPDATE.name, targetType = "payroll", targetId = ref.id,
            details = "Tính lại phiếu lương ${previous.month}, phiên bản ${next.revision}; " +
                "giờ ${previous.hoursWorked} → ${next.hoursWorked}; " +
                "đơn giá ${previous.hourlyRate} → ${next.hourlyRate}; " +
                "thực nhận ${previous.netSalary} → ${next.netSalary}; lý do: $cleanReason"
        )
        validateAuditLog(audit)
        // Both snapshots use the raw maps so legacy missing fields are accurately archived.
        transaction.set(ref, after)
        transaction.set(revisionRef, mapOf(
            "payrollId" to ref.id, "revision" to next.revision, "previous" to before, "next" to after,
            "actorId" to actorId, "reason" to cleanReason, "createdAt" to FieldValue.serverTimestamp()
        ))
        transaction.set(auditRef, audit.toFirestoreData())
    }.await()
    return next
}
