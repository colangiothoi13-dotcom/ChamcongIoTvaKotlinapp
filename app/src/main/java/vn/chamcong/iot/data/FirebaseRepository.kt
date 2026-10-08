// Chức năng: Giữ kết nối Firebase, phiên đăng nhập và các thao tác xác thực dùng chung.
package vn.chamcong.iot.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.*

data class AttendancePage(
    val rows: List<Attendance>,
    val nextCursor: DocumentSnapshot? = null
)

class FirebaseRepository(
    internal val appContext: Context,
    internal val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    internal val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    val isSignedIn: Boolean get() = auth.currentUser != null
    val currentUserId: String get() = auth.currentUser?.uid.orEmpty()
    val currentUserName: String get() = auth.currentUser?.email ?: "Admin"
    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        runCatching {
            writeAuditLog(AuditLog(
                actorId = currentUserId,
                actorName = currentUserName,
                action = AuditAction.LOGIN.name,
                targetType = "user",
                targetId = currentUserId,
                details = "Đăng nhập bằng Email/Password"
            ))
        }
    }
    fun signOut() = auth.signOut()
    suspend fun sendPasswordReset(email: String) {
        require(email.trim().isNotBlank()) { "Vui lòng nhập email" }
        auth.sendPasswordResetEmail(email.trim()).await()
    }
    /** Returns whether the audit was saved; a false result still means Auth changed the password. */
    suspend fun changePassword(newPassword: String): Boolean {
        require(newPassword.length >= 6) { "Mật khẩu mới phải có ít nhất 6 ký tự" }
        val user = auth.currentUser ?: error("Chưa đăng nhập")
        require(user.providerData.none { it.providerId == "anonymous" }) { "Thiết bị không được đổi mật khẩu" }
        return performPasswordChange(
            updatePassword = { user.updatePassword(newPassword).await() },
            saveAudit = {
                writeAuditLog(AuditLog(
                    actorId = user.uid,
                    actorName = user.email ?: currentUserName,
                    action = AuditAction.PASSWORD_CHANGE.name,
                    targetType = "user",
                    targetId = user.uid,
                    details = "Đổi mật khẩu"
                ))
            }
        )
    }

    suspend fun writeAuditLog(log: AuditLog): String {
        validateAuditLog(log)
        val ref = db.collection("audit_logs").document()
        ref.set(log.toFirestoreData()).await()
        return ref.id
    }
}
