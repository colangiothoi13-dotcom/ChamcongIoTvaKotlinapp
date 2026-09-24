package vn.chamcong.iot.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.copyScheduleToNextWeek
import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.domain.employeeAccountProfile
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.notificationForRequest
import vn.chamcong.iot.domain.reviewRequest
import vn.chamcong.iot.domain.reviewOvertimeRequest as applyOvertimeReview
import vn.chamcong.iot.domain.validateOvertimeHours
import vn.chamcong.iot.domain.validateOvertimeRequest
import vn.chamcong.iot.domain.isOvertimeRequestWithinDeadline
import vn.chamcong.iot.domain.validateRequest
import vn.chamcong.iot.domain.validateShift
import vn.chamcong.iot.domain.validateScheduleShift
import vn.chamcong.iot.domain.validateWorkedHoursOverride
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.domain.validateAttendanceClassificationOverride
import vn.chamcong.iot.domain.validateEmployeeAccountInput
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.Instant
import vn.chamcong.iot.domain.validateAttendanceAdjustment
import java.util.UUID

class FirebaseRepository(
    private val appContext: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private companion object {
        const val EMPLOYEE_ACCOUNT_APP_NAME = "employee-account-creator"
    }
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
    suspend fun changePassword(newPassword: String) {
        require(newPassword.length >= 6) { "Mật khẩu mới phải có ít nhất 6 ký tự" }
        val user = auth.currentUser ?: error("Chưa đăng nhập")
        require(user.providerData.none { it.providerId == "anonymous" }) { "Thiết bị không được đổi mật khẩu" }
        user.updatePassword(newPassword).await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.PASSWORD_CHANGE.name,
            targetType = "user",
            targetId = currentUserId,
            details = "Đổi mật khẩu"
        ))
    }

    fun observeEmployees(): Flow<List<Employee>> = callbackFlow {
        val listener = db.collection("employees").orderBy("fullName").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Employee::class.java)?.copy(id=it.id) })
        }
        awaitClose { listener.remove() }
    }
    fun observeEmployee(employeeId: String): Flow<Employee?> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("employees").document(employeeId).addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.toObject(Employee::class.java)?.copy(id = employeeId))
        }
        awaitClose { listener.remove() }
    }
    fun observeEmployeeAttendance(employeeId: String): Flow<List<Attendance>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("attendance")
            .whereEqualTo("employeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(Attendance::class.java)?.copy(id = it.id) }
                    .sortedByDescending { it.timestamp.toDate().time })
            }
        awaitClose { listener.remove() }
    }
    fun observeEmployeeSchedules(employeeId: String): Flow<List<WorkSchedule>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("workSchedules")
            .whereEqualTo("employeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) }
                    .sortedBy { it.date })
            }
        awaitClose { listener.remove() }
    }
    fun observeEmployeeRequests(employeeId: String): Flow<List<LeaveRequest>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("leaveRequests")
            .whereEqualTo("employeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(LeaveRequest::class.java)?.copy(id = it.id) }
                    .sortedByDescending { it.createdAt.toDate().time })
            }
        awaitClose { listener.remove() }
    }
    fun observeEmployeeOvertimeRequests(employeeId: String): Flow<List<OvertimeRequest>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("overtimeRequests")
            .whereEqualTo("employeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull(::overtimeRequest)
                    .sortedByDescending { it.createdAt ?: Instant.MIN })
            }
        awaitClose { listener.remove() }
    }

    fun observeRecentAttendance(): Flow<List<Attendance>> = callbackFlow {
        val listener = db.collection("attendance").orderBy("timestamp", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Attendance::class.java)?.copy(id=it.id) })
            }
        awaitClose { listener.remove() }
    }
    fun observeAllAttendance(): Flow<List<Attendance>> = callbackFlow {
        val listener = db.collection("attendance").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty()
                .mapNotNull { it.toObject(Attendance::class.java)?.copy(id = it.id) }
                .sortedByDescending { it.timestamp.toDate().time })
        }
        awaitClose { listener.remove() }
    }
    fun observePayroll(): Flow<List<Payroll>> = callbackFlow {
        val listener = db.collection("payroll").orderBy("month", Query.Direction.DESCENDING).addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Payroll::class.java) })
        }
        awaitClose { listener.remove() }
    }
    fun observeDepartments(): Flow<List<Department>> = callbackFlow {
        val listener = db.collection("departments").orderBy("name").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { document ->
                document.toObject(Department::class.java)?.copy(id = document.id)
            })
        }
        awaitClose { listener.remove() }
    }

    suspend fun saveDepartment(departmentId: String?, rawName: String): String {
        val name = rawName.trim()
        require(name.length in 2..80 && !name.contains('/')) { "Tên phòng ban phải từ 2–80 ký tự" }
        val snapshot = db.collection("departments").get(Source.SERVER).await()
        require(snapshot.documents.none { document ->
            document.id != departmentId && document.getString("name")?.trim()?.equals(name, ignoreCase = true) == true
        }) { "Tên phòng ban đã tồn tại" }

        val ref = departmentId?.let { db.collection("departments").document(it) }
            ?: db.collection("departments").document()
        val old = departmentId?.let { id -> snapshot.documents.firstOrNull { it.id == id } }
        require(departmentId == null || old != null) { "Không tìm thấy phòng ban" }
        val now = FieldValue.serverTimestamp()
        val affectedEmployees = if (old != null && old.getString("name") != name) {
            db.collection("employees").whereEqualTo("department", old.getString("name").orEmpty())
                .get(Source.SERVER).await().documents
        } else emptyList()
        require(affectedEmployees.size <= 498) { "Có quá nhiều nhân viên để đổi tên phòng ban trong một lần" }

        val batch = db.batch()
        batch.set(ref, mapOf(
            "name" to name,
            "active" to (old?.getBoolean("active") ?: true),
            "createdAt" to (old?.getTimestamp("createdAt") ?: now),
            "updatedAt" to now
        ))
        affectedEmployees.forEach { employee ->
            batch.update(employee.reference, mapOf("department" to name, "departmentId" to ref.id))
        }
        val auditRef = db.collection("audit_logs").document()
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.DEPARTMENT_UPDATE.name, targetType = "department", targetId = ref.id,
            details = if (old == null) "Tạo phòng ban $name" else "Đổi tên phòng ban ${old.getString("name")} thành $name"
        )
        batch.set(auditRef, audit.toFirestoreData())
        batch.commit().await()
        return ref.id
    }

    suspend fun setDepartmentActive(departmentId: String, active: Boolean) {
        require(departmentId.isNotBlank()) { "Phòng ban không hợp lệ" }
        val ref = db.collection("departments").document(departmentId)
        val current = ref.get(Source.SERVER).await()
        require(current.exists()) { "Không tìm thấy phòng ban" }
        val auditRef = db.collection("audit_logs").document()
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.DEPARTMENT_UPDATE.name, targetType = "department", targetId = departmentId,
            details = "${if (active) "Kích hoạt" else "Ngừng hoạt động"} phòng ban ${current.getString("name").orEmpty()}"
        )
        db.runBatch { batch ->
            batch.update(ref, mapOf("active" to active, "updatedAt" to FieldValue.serverTimestamp()))
            batch.set(auditRef, audit.toFirestoreData())
        }.await()
    }
    fun observeEmployeePayroll(employeeId: String): Flow<List<Payroll>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("payroll")
            .whereEqualTo("employeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(Payroll::class.java) }
                    .sortedByDescending { it.month })
            }
        awaitClose { listener.remove() }
    }
    fun observeDevices(): Flow<List<DeviceSnapshot>> = callbackFlow {
        val listener = db.collection("devices").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().map(::deviceSnapshot))
        }
        awaitClose { listener.remove() }
    }

    fun observeShifts(): Flow<List<WorkShift>> = callbackFlow {
        val listener = db.collection("shifts").orderBy("category").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(WorkShift::class.java)?.copy(id = it.id) })
        }
        awaitClose { listener.remove() }
    }

    // Admin calculation context spans arbitrary report/payroll periods and overnight boundaries.
    fun observeSchedules(): Flow<List<WorkSchedule>> = callbackFlow {
        val listener = db.collection("workSchedules")
            .orderBy("date")
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) })
            }
        awaitClose { listener.remove() }
    }

    fun observeLeaveRequests(): Flow<List<LeaveRequest>> = callbackFlow {
        val listener = db.collection("leaveRequests")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(LeaveRequest::class.java)?.copy(id = it.id) })
            }
        awaitClose { listener.remove() }
    }

    fun observeOvertimeRequests(): Flow<List<OvertimeRequest>> = callbackFlow {
        val listener = db.collection("overtimeRequests")
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull(::overtimeRequest)
                    .sortedByDescending { it.createdAt ?: Instant.MIN })
            }
        awaitClose { listener.remove() }
    }

    fun observeNotifications(): Flow<List<AppNotification>> = callbackFlow {
        val listener = db.collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(AppNotification::class.java)?.copy(id = it.id) })
            }
        awaitClose { listener.remove() }
    }

    fun observeWeeklyScheduleRequests(): Flow<List<WeeklyScheduleRequest>> = callbackFlow {
        val listener = db.collection("weeklyScheduleRequests")
            .orderBy("weekStart", Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull(::weeklyScheduleRequest))
            }
        awaitClose { listener.remove() }
    }

    fun observeEmployeeWeeklyScheduleRequest(
        employeeId: String,
        weekStart: String
    ): Flow<WeeklyScheduleRequest?> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val monday = validatedMonday(weekStart)
        val listener = db.collection("weeklyScheduleRequests")
            .whereEqualTo("employeeId", employeeId)
            .whereEqualTo("weekStart", monday)
            .limit(1)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents?.firstOrNull()?.let(::weeklyScheduleRequest))
            }
        awaitClose { listener.remove() }
    }

    fun observeEmployeeNotifications(employeeId: String): Flow<List<AppNotification>> = callbackFlow {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val listener = db.collection("notifications")
            .whereEqualTo("recipientEmployeeId", employeeId)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(AppNotification::class.java)?.copy(id = it.id) }
                    .sortedByDescending { it.createdAt.toDate().time })
            }
        awaitClose { listener.remove() }
    }

    fun observeAnnouncements(): Flow<List<Announcement>> = callbackFlow {
        val listener = db.collection("announcements")
            .orderBy("sentAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().mapNotNull { document ->
                    document.toObject(Announcement::class.java)?.copy(id = document.id)
                })
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendAnnouncement(targetDepartment: String?, title: String, body: String): Int {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        require(cleanTitle.isNotBlank() && cleanTitle.length <= 120) { "Tiêu đề thông báo phải từ 1–120 ký tự" }
        require(cleanBody.isNotBlank() && cleanBody.length <= 4000) { "Nội dung thông báo phải từ 1–4.000 ký tự" }
        val allEmployees = db.collection("employees").whereEqualTo("active", true).get(Source.SERVER).await()
            .documents.mapNotNull { it.toObject(Employee::class.java)?.copy(id = it.id) }
        val recipients = allEmployees.filter { employee ->
            targetDepartment.isNullOrBlank() ||
                employee.department == targetDepartment || employee.departmentId == targetDepartment
        }
        require(recipients.size <= 498) { "Thông báo vượt quá giới hạn 498 người nhận. Hãy chọn một phòng ban." }
        require(recipients.isNotEmpty()) { "Không có nhân viên đang làm việc trong nhóm đã chọn" }

        val announcementRef = db.collection("announcements").document()
        val sentAt = FieldValue.serverTimestamp()
        val audienceLabel = targetDepartment?.takeIf(String::isNotBlank) ?: "Tất cả nhân viên"
        val batch = db.batch()
        batch.set(announcementRef, mapOf(
            "title" to cleanTitle,
            "body" to cleanBody,
            "targetDepartment" to targetDepartment?.takeIf(String::isNotBlank),
            "recipientCount" to recipients.size,
            "sentAt" to sentAt,
            "senderId" to currentUserId,
            "senderName" to currentUserName
        ))
        recipients.forEach { employee ->
            val notificationRef = db.collection("notifications").document()
            batch.set(notificationRef, mapOf(
                "type" to "ANNOUNCEMENT",
                "title" to cleanTitle,
                "body" to cleanBody,
                "referenceId" to announcementRef.id,
                "recipientEmployeeId" to employee.id,
                "audienceLabel" to audienceLabel,
                "createdAt" to sentAt,
                "read" to false
            ))
        }
        val auditRef = db.collection("audit_logs").document()
        batch.set(auditRef, AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.ANNOUNCEMENT_SEND.name, targetType = "announcement",
            targetId = announcementRef.id,
            details = "Gửi thông báo đến $audienceLabel (${recipients.size} nhân viên)"
        ).toFirestoreData())
        // Firestore batches are limited to 500 writes. Keep the notification,
        // history entry, and audit entry atomic for the supported recipient cap.
        batch.commit().await()
        return recipients.size
    }

    fun observeAuditLogs(): Flow<List<AuditLog>> = callbackFlow {
        val listener = db.collection("audit_logs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty().map(::auditLog))
            }
        awaitClose { listener.remove() }
    }

    fun observeUserProfile(): Flow<UserProfile?> = callbackFlow {
        val uid = currentUserId
        if (uid.isBlank()) {
            trySend(null)
            awaitClose { }
        } else {
            val listener = db.collection("users").document(uid).addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.toObject(UserProfile::class.java)?.copy(uid = uid))
            }
            awaitClose { listener.remove() }
        }
    }

    suspend fun writeAuditLog(log: AuditLog): String {
        validateAuditLog(log)
        val ref = db.collection("audit_logs").document()
        ref.set(log.toFirestoreData()).await()
        return ref.id
    }

    fun observeAttendanceAdjustments(): Flow<List<AttendanceAdjustment>> = observeAdjustments(
        db.collection("attendanceAdjustments").orderBy("createdAt", Query.Direction.DESCENDING)
    )

    fun observeOffScheduleAttendanceReviews(): Flow<List<OffScheduleAttendanceReview>> = callbackFlow {
        val listener = db.collection("offScheduleReviews")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { value, error ->
                if (error != null) close(error)
                else trySend(value?.documents.orEmpty()
                    .mapNotNull { it.toObject(OffScheduleAttendanceReview::class.java)?.copy(id = it.id) })
            }
        awaitClose { listener.remove() }
    }

    fun observeEmployeeAttendanceAdjustments(employeeId: String): Flow<List<AttendanceAdjustment>> {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        // Sort locally so the employee shell needs no additional composite index.
        return observeAdjustments(db.collection("attendanceAdjustments").whereEqualTo("employeeId", employeeId))
    }

    fun observeAttendanceClassificationOverrides(): Flow<List<AttendanceClassificationOverride>> =
        observeClassificationOverrides(db.collection("attendanceClassificationOverrides"))

    fun observeEmployeeAttendanceClassificationOverrides(employeeId: String): Flow<List<AttendanceClassificationOverride>> {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        return observeClassificationOverrides(
            db.collection("attendanceClassificationOverrides").whereEqualTo("employeeId", employeeId)
        )
    }

    private fun observeClassificationOverrides(query: Query): Flow<List<AttendanceClassificationOverride>> = callbackFlow {
        val listener = query.addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toAttendanceClassificationOverride() }
                .sortedByDescending { it.createdAt })
        }
        awaitClose { listener.remove() }
    }

    private fun observeAdjustments(query: Query): Flow<List<AttendanceAdjustment>> = callbackFlow {
        val listener = query.addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toAttendanceAdjustment() }
                .sortedByDescending { it.createdAt })
        }
        awaitClose { listener.remove() }
    }

    suspend fun saveAttendanceAdjustment(adjustment: AttendanceAdjustment): String {
        require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
        val stored = adjustment.copy(actorId = currentUserId, actorName = currentUserName, reason = adjustment.reason.trim())
        validateAttendanceAdjustment(stored)
        val collection = db.collection("attendanceAdjustments")
        val previous = collection.whereEqualTo("employeeId", stored.employeeId)
            .whereEqualTo("scheduleDate", stored.scheduleDate)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .get(Source.SERVER).await().documents.firstOrNull()?.toAttendanceAdjustment()
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

    suspend fun saveAttendanceClassificationOverride(
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
        require(source.resolutionStatus == AttendanceResolutionStatus.ACCEPTED.name &&
            source.type in AttendanceType.entries.map { it.name }) {
            "Chỉ sửa phân loại của lượt chấm đã được hệ thống chấp nhận"
        }

        val collection = db.collection("attendanceClassificationOverrides")
        val previous = collection.whereEqualTo("attendanceId", requested.attendanceId)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .get(Source.SERVER).await().documents.firstOrNull()?.toAttendanceClassificationOverride()
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

    suspend fun submitOffScheduleAttendanceReview(
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
        db.collection("offScheduleReviews").document(id).set(mapOf(
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
        )).await()
    }

    private fun AttendanceAdjustment.toFirestoreData(): Map<String, Any?> = mapOf(
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

    suspend fun saveShift(shift: WorkShift): String {
        validateShift(shift)
        val ref = if (shift.id.isBlank()) db.collection("shifts").document() else db.collection("shifts").document(shift.id)
        ref.set(shift.copy(id = "")).await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.SHIFT_UPDATE.name,
            targetType = "shift",
            targetId = ref.id,
            details = "Lưu cấu hình ca ${shift.name}"
        ))
        return ref.id
    }

    suspend fun saveSchedule(schedule: WorkSchedule) {
        require(schedule.employeeId.isNotBlank()) { "Chưa chọn nhân viên" }
        require(schedule.shiftId.isNotBlank()) { "Chưa chọn ca" }
        val date = LocalDate.parse(schedule.date).toString()
        require(LocalDate.parse(date).dayOfWeek.value in 1..6) { "Không thể phân ca vào Chủ nhật" }
        val selectedShiftIds = schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }
        require(selectedShiftIds.size in 1..2 && selectedShiftIds.distinct().size == selectedShiftIds.size) {
            "Có thể chọn tối đa hai ca khác nhau trong ngày"
        }
        require(selectedShiftIds.first() == schedule.shiftId) { "Ca chính phải là ca đầu tiên trong danh sách" }
        val selectedShifts = selectedShiftIds.map { requireActiveAssignableShift(it) }
        require(selectedShifts.map { it.category }.distinct().size == selectedShifts.size) {
            "Trong ngày chỉ có thể chọn một ca sáng và một ca chiều"
        }
        validateOvertimeHours(schedule.overtimeHours)
        validateWorkedHoursOverride(schedule.workedHoursOverride)
        if (schedule.workedHoursOverride != null) require(schedule.adjustmentNote.isNotBlank()) { "Cần nhập lý do điều chỉnh giờ" }
        val id = scheduleDocumentId(schedule.employeeId, date)
        db.collection("workSchedules").document(id)
            .set(schedule.copy(id = "", date = date, shiftIds = selectedShiftIds, source = "ADMIN")).await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = if (schedule.workedHoursOverride != null) AuditAction.ATTENDANCE_ADJUST.name else AuditAction.SHIFT_UPDATE.name,
            targetType = "workSchedule",
            targetId = id,
            reason = schedule.adjustmentNote,
            details = "Phân ca ${schedule.shiftName} ngày $date cho ${schedule.employeeName}"
        ))
    }

    /** Explicit assignment only. Stable template IDs are create-only, including concurrent saves. */
    suspend fun saveWeeklySchedules(shift: WorkShift, schedules: List<WorkSchedule>) {
        validateScheduleShift(shift)
        require(schedules.isNotEmpty())
        require(schedules.all { LocalDate.parse(it.date).dayOfWeek.value in 1..6 }) {
            "Không thể phân ca vào Chủ nhật"
        }
        val shiftRef = db.collection("shifts").document(shift.id)
        var saved = 0
        try {
            schedules.chunked(400).forEach { chunk ->
                val auditRef = db.collection("audit_logs").document()
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(shiftRef)
                    if (snapshot.exists()) {
                        require(snapshot.toObject(WorkShift::class.java)?.copy(id = shift.id) == shift) {
                            "Ca mẫu đã bị thay đổi. Vui lòng kiểm tra cấu hình ca."
                        }
                    } else transaction.set(shiftRef, shift.copy(id = ""))
                    chunk.forEach { schedule ->
                        // Only schedule fields: preserve legacy hours adjustments and all attendance history.
                        transaction.set(db.collection("workSchedules").document(scheduleDocumentId(schedule.employeeId, schedule.date)),
                            mapOf("id" to "", "employeeId" to schedule.employeeId, "employeeName" to schedule.employeeName,
                                "department" to schedule.department, "date" to schedule.date, "shiftId" to shift.id,
                                "shiftIds" to listOf(shift.id),
                                "shiftName" to shift.name, "overtimeHours" to 0, "assignedBy" to currentUserId,
                                "source" to "ADMIN"), SetOptions.merge())
                    }
                    transaction.set(auditRef, AuditLog(actorId = currentUserId, actorName = currentUserName,
                        action = AuditAction.SHIFT_UPDATE.name, targetType = "workSchedule",
                        targetId = scheduleDocumentId(chunk.first().employeeId, chunk.first().date),
                        details = "Phân ca tuần: ${chunk.size} lịch; ${chunk.joinToString { it.id }}").toFirestoreData())
                }.await()
                saved += chunk.size
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            throw IllegalStateException("Đã lưu $saved/${schedules.size} lịch. Có thể thử lại an toàn. ${e.localizedMessage}", e)
        }
    }

    suspend fun submitWeeklyScheduleRequest(request: WeeklyScheduleRequest): String {
        val employeeId = request.employeeId.trim()
        require(employeeId.isNotBlank()) { "Chưa chọn nhân viên" }
        val weekStart = validatedMonday(request.weekStart)
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

    suspend fun reviewWeeklyScheduleRequest(
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

    suspend fun assignShiftToDepartment(
        department: String,
        dates: List<String>,
        shift: WorkShift,
        overtimeHours: Int,
        assignedBy: String
    ) {
        require(department.isNotBlank()) { "Chưa chọn phòng ban" }
        require(dates.isNotEmpty()) { "Chưa chọn ngày phân ca" }
        validateScheduleShift(shift)
        requireActiveAssignableShift(shift.id)
        validateOvertimeHours(overtimeHours)
        val employees = db.collection("employees")
            .whereEqualTo("active", true)
            .whereEqualTo("department", department)
            .get(Source.SERVER)
            .await()
            .documents
        val batch = db.batch()
        dates.forEach { rawDate ->
            val date = LocalDate.parse(rawDate).toString()
            require(LocalDate.parse(date).dayOfWeek.value in 1..6) { "Không thể phân ca vào Chủ nhật" }
            employees.forEach { document ->
                val employee = document.toObject(Employee::class.java) ?: return@forEach
                val id = scheduleDocumentId(document.id, date)
                val schedule = WorkSchedule(
                    id = id,
                    employeeId = document.id,
                    employeeName = employee.fullName,
                    department = employee.department,
                    shiftId = shift.id,
                    shiftIds = listOf(shift.id),
                    shiftName = shift.name,
                    date = date,
                    overtimeHours = overtimeHours,
                    assignedBy = assignedBy,
                    source = "DEPARTMENT"
                )
                batch.set(db.collection("workSchedules").document(id), schedule.copy(id = ""))
            }
        }
        batch.commit().await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.SHIFT_UPDATE.name,
            targetType = "department",
            targetId = department,
            details = "Phân lịch cho phòng ban $department, ngày $dates"
        ))
    }

    suspend fun copyPreviousWeek(sourceWeekStart: String, targetWeekStart: String, assignedBy: String): Int {
        val sourceMonday = mondayOfWeek(LocalDate.parse(sourceWeekStart))
        val targetMonday = mondayOfWeek(LocalDate.parse(targetWeekStart))
        require(targetMonday == sourceMonday.plusWeeks(1)) { "Tuần đích phải là tuần kế tiếp" }
        val sourceEnd = sourceMonday.plusDays(6)
        val sourceSnapshot = db.collection("workSchedules")
            .whereGreaterThanOrEqualTo("date", sourceMonday.toString())
            .whereLessThanOrEqualTo("date", sourceEnd.toString())
            .get(Source.SERVER)
            .await()
        val targetSnapshot = db.collection("workSchedules")
            .whereGreaterThanOrEqualTo("date", targetMonday.toString())
            .whereLessThanOrEqualTo("date", targetMonday.plusDays(6).toString())
            .get(Source.SERVER)
            .await()
        val source = sourceSnapshot.documents.mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) }
        val existingIds = targetSnapshot.documents.mapTo(mutableSetOf()) { it.id }
        val newSchedules = copyScheduleToNextWeek(source, existingIds).map {
            it.copy(assignedBy = assignedBy, source = "ADMIN_COPY")
        }
        // Validate all candidates before writing any copied schedule, including legacy custom IDs.
        newSchedules.flatMap { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.distinct()
            .forEach { requireActiveAssignableShift(it) }
        val batch = db.batch()
        newSchedules.forEach { schedule ->
            batch.set(db.collection("workSchedules").document(schedule.id), schedule.copy(
                id = "", shiftIds = schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }
            ))
        }
        if (newSchedules.isNotEmpty()) batch.commit().await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.SHIFT_UPDATE.name,
            targetType = "week",
            targetId = targetMonday.toString(),
            details = "Sao chép $sourceMonday sang $targetMonday: ${newSchedules.size} lịch"
        ))
        return newSchedules.size
    }

    private suspend fun requireAssignableStoredShift(shiftId: String) {
        require(shiftId.isNotBlank()) { "Chưa chọn ca" }
        val snapshot = db.collection("shifts").document(shiftId).get(Source.SERVER).await()
        val shift = snapshot.toObject(WorkShift::class.java)?.copy(id = snapshot.id)
        require(shift != null) { "Ca không còn tồn tại" }
        validateScheduleShift(shift)
    }

    private suspend fun requireActiveAssignableShift(shiftId: String): WorkShift {
        require(shiftId.isNotBlank()) { "Chưa chọn ca" }
        val snapshot = db.collection("shifts").document(shiftId).get(Source.SERVER).await()
        val shift = snapshot.toObject(WorkShift::class.java)?.copy(id = snapshot.id)
            ?: error("Ca không còn tồn tại")
        require(shift.active && canAssignScheduleShift(shift)) { "Ca ${shift.name} không còn được đăng ký" }
        validateScheduleShift(shift)
        return shift
    }

    private fun validatedMonday(rawWeekStart: String): String {
        val monday = runCatching { LocalDate.parse(rawWeekStart) }
            .getOrElse { throw IllegalArgumentException("Tuần phải có dạng yyyy-MM-dd") }
        require(monday.toString() == rawWeekStart && monday.dayOfWeek == java.time.DayOfWeek.MONDAY) {
            "Ngày bắt đầu tuần phải là thứ Hai theo dạng yyyy-MM-dd"
        }
        return monday.toString()
    }

    private fun validatedWeeklyShiftMap(
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

    private fun validateWeeklyShiftCategories(
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

    private fun weeklyScheduleRequestId(employeeId: String, weekStart: String): String = "${employeeId}_$weekStart"

    private fun weeklyScheduleRequest(document: DocumentSnapshot): WeeklyScheduleRequest? {
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

    private fun DocumentSnapshot.toAttendanceClassificationOverride(): AttendanceClassificationOverride? {
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

    suspend fun submitLeaveRequest(request: LeaveRequest): String {
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

    suspend fun submitEmployeeRequest(request: LeaveRequest): String {
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

    suspend fun cancelEmployeeLeaveRequest(requestId: String, employeeId: String) {
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

    suspend fun reviewLeaveRequest(
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

    suspend fun submitOvertimeRequest(request: OvertimeRequest): String {
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

    suspend fun reviewOvertimeRequest(
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

    suspend fun markNotificationRead(notificationId: String) {
        require(notificationId.isNotBlank()) { "Thông báo không hợp lệ" }
        db.collection("notifications").document(notificationId).update("read", true).await()
    }

    private fun scheduleDocumentId(employeeId: String, date: String): String = "${employeeId}_$date"

    private fun WorkSchedule.toFirestoreData(): Map<String, Any?> = mapOf(
        "employeeId" to employeeId,
        "employeeName" to employeeName,
        "department" to department,
        "shiftId" to shiftId,
        "shiftIds" to shiftIds.ifEmpty { listOf(shiftId) },
        "shiftName" to shiftName,
        "date" to date,
        "overtimeHours" to overtimeHours,
        "workedHoursOverride" to workedHoursOverride,
        "adjustmentNote" to adjustmentNote,
        "assignedBy" to assignedBy,
        "source" to source,
        "note" to note
    )

    private fun LeaveRequest.toFirestoreData(): Map<String, Any?> = mapOf(
        "employeeId" to employeeId,
        "employeeName" to employeeName,
        "department" to department,
        "type" to type,
        "startDate" to startDate,
        "endDate" to endDate,
        "reason" to reason,
        "proposedCheckIn" to proposedCheckIn,
        "proposedCheckOut" to proposedCheckOut,
        "requestedShiftId" to requestedShiftId,
        "requestedShiftName" to requestedShiftName,
        "leaveShiftsByDate" to leaveShiftsByDate,
        "appliedAdjustmentId" to appliedAdjustmentId,
        "attachmentUrl" to attachmentUrl,
        "status" to status,
        "reviewerId" to reviewerId,
        "reviewerName" to reviewerName,
        "reviewedAt" to reviewedAt,
        "reviewNote" to reviewNote,
        "createdAt" to FieldValue.serverTimestamp()
    )

    private fun OvertimeRequest.toOvertimeFirestoreData(): Map<String, Any?> = mapOf(
        "employeeId" to employeeId,
        "employeeName" to employeeName,
        "department" to department,
        "workDate" to workDate,
        "startTime" to startTime,
        "endTime" to endTime,
        "reason" to reason,
        "status" to status,
        "createdAt" to FieldValue.serverTimestamp(),
        "reviewerId" to reviewerId,
        "reviewerName" to reviewerName,
        "reviewedAt" to reviewedAt?.let { Timestamp(it.epochSecond, it.nano) },
        "rejectionReason" to rejectionReason
    )

    private fun AppNotification.toFirestoreData(): Map<String, Any?> = mapOf(
        "type" to type,
        "title" to title,
        "body" to body,
        "referenceId" to referenceId,
        "recipientEmployeeId" to recipientEmployeeId,
        "audienceLabel" to audienceLabel,
        "createdAt" to FieldValue.serverTimestamp(),
        "read" to read
    )

    private fun deviceSnapshot(document: DocumentSnapshot): DeviceSnapshot =
        deviceSnapshotFromFields(document.id, document.data.orEmpty())

    suspend fun updateDeviceConfiguration(deviceId: String, name: String, location: String) {
        require(deviceId.isNotBlank()) { "Mã thiết bị không hợp lệ" }
        require(name.isNotBlank()) { "Tên thiết bị không được để trống" }
        db.collection("devices").document(deviceId).update(
            mapOf("name" to name.trim(), "location" to location.trim())
        ).await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.DEVICE_CONFIG_UPDATE.name,
            targetType = "device",
            targetId = deviceId,
            details = "Đổi tên/vị trí thiết bị thành ${name.trim()} / ${location.trim()}"
        ))
    }

    suspend fun requestDeviceCommand(deviceId: String, type: DeviceCommandType): String {
        require(deviceId.isNotBlank() && !deviceId.contains('/')) { "Mã thiết bị không hợp lệ" }
        val requestId = UUID.randomUUID().toString()
        val commandRef = db.collection("deviceCommands").document(deviceId)
        val deviceRef = db.collection("devices").document(deviceId)
        val auditRef = db.collection("audit_logs").document()
        db.runTransaction { tx ->
            val device = tx.get(deviceRef)
            require(device.exists()) { "Không tìm thấy thiết bị" }
            require(device.getString("deviceId").orEmpty().ifBlank { deviceId } == deviceId) {
                "Thiết bị không khớp mã đăng ký"
            }
            requireFreeCommand(tx.get(commandRef))
            tx.set(commandRef, mapOf(
                "requestId" to requestId,
                "type" to type.name,
                "deviceId" to deviceId,
                "status" to "REQUESTED",
                "createdAt" to FieldValue.serverTimestamp(),
                "message" to "",
                "applied" to true
            ))
            val audit = AuditLog(
                actorId = currentUserId,
                actorName = currentUserName,
                action = AuditAction.DEVICE_COMMAND.name,
                targetType = "deviceCommand",
                targetId = requestId,
                details = "Gửi lệnh ${type.name} cho thiết bị $deviceId"
            )
            validateAuditLog(audit)
            tx.set(auditRef, audit.toFirestoreData())
        }.await()
        return requestId
    }

    suspend fun saveEmployee(employee: Employee, account: EmployeeAccountInput? = null): String {
        account?.let(::validateEmployeeAccountInput)
        val result = saveEmployeeInternal(employee, null)
        account?.let { createEmployeeAccount(result.id, it) }
        return result.code
    }

    suspend fun saveAndRequestFingerprint(
        employee: Employee,
        deviceId: String,
        account: EmployeeAccountInput? = null
    ): String {
        account?.let(::validateEmployeeAccountInput)
        val result = saveEmployeeInternal(employee, deviceId.trim())
        account?.let { createEmployeeAccount(result.id, it) }
        return result.code
    }

    private suspend fun createEmployeeAccount(employeeId: String, input: EmployeeAccountInput) {
        validateEmployeeAccountInput(input)
        val primaryApp = FirebaseApp.getInstance()
        val accountApp = runCatching { FirebaseApp.getInstance(EMPLOYEE_ACCOUNT_APP_NAME) }
            .getOrElse {
                FirebaseApp.initializeApp(appContext, primaryApp.options, EMPLOYEE_ACCOUNT_APP_NAME)
                    ?: error("Không khởi tạo được Firebase Auth phụ để tạo tài khoản")
            }
        val accountAuth = FirebaseAuth.getInstance(accountApp)
        accountAuth.signOut()
        var createdUid: String? = null
        try {
            val user = accountAuth.createUserWithEmailAndPassword(input.email.trim(), input.password).await().user
                ?: error("Firebase không trả về tài khoản mới")
            createdUid = user.uid
            val profile = employeeAccountProfile(user.uid, employeeId, input)
            db.collection("users").document(user.uid).set(profile).await()
            runCatching { writeAuditLog(AuditLog(
                actorId = currentUserId,
                actorName = currentUserName,
                action = AuditAction.ACCOUNT_CREATE.name,
                targetType = "user",
                targetId = user.uid,
                details = "Tạo tài khoản EMPLOYEE cho nhân viên $employeeId"
            )) }
        } catch (error: Exception) {
            if (createdUid != null) runCatching { accountAuth.currentUser?.delete()?.await() }
            throw error
        } finally {
            accountAuth.signOut()
        }
    }

    private fun requireFreeCommand(command: DocumentSnapshot) {
        val status = command.getString("status")
        require(status !in listOf("REQUESTED", "PROCESSING") && !(status == "COMPLETED" && command.getBoolean("applied") != true)) {
            "Thiết bị đang có lệnh chưa xử lý xong. Hãy bật thiết bị và chờ hoàn tất."
        }
    }
    private data class EmployeeSaveResult(val id: String, val code: String)

    private suspend fun saveEmployeeInternal(input: Employee, deviceId: String?): EmployeeSaveResult {
        require(input.fullName.isNotBlank()) { "Vui lòng nhập họ tên" }
        require(input.hireDate.isBlank() || runCatching { LocalDate.parse(input.hireDate) }.isSuccess) {
            "Ngày vào làm phải có dạng yyyy-MM-dd"
        }
        if (deviceId != null) require(deviceId.isNotBlank() && !deviceId.contains('/')) { "Mã thiết bị không hợp lệ" }
        val ref = if (input.id.isBlank()) db.collection("employees").document() else db.collection("employees").document(input.id)
        val counter = db.collection("system").document("employeeCounter")
        // Bao gom ca nhan vien da nghi va ma nhap thu cong cua ban cu.
        val codes = if (input.id.isBlank()) db.collection("employees").get(Source.SERVER).await().documents.mapNotNull { it.getString("code") } else emptyList()
        val requestId = UUID.randomUUID().toString()
        val code = db.runTransaction { tx ->
            val old = tx.get(ref).toObject(Employee::class.java)
            require(input.id.isBlank() || old != null) { "Không tìm thấy nhân viên" }
            val counterDoc = if (old == null) tx.get(counter) else null
            val employee = if (old == null) {
                input.copy(code = nextEmployeeCode(counterDoc?.getLong("lastNumber") ?: 0, codes), id = "", active = true)
            } else {
                require(old.active) { "Nhân viên đã nghỉ" }
                old.copy(
                    fullName = input.fullName.trim(),
                    email = input.email.trim(),
                    phone = input.phone.trim(),
                    address = input.address.trim(),
                    departmentId = input.departmentId,
                    department = input.department.trim(),
                    position = input.position.trim(),
                    hireDate = input.hireDate.trim()
                )
            }
            val commandRef = deviceId?.let { db.collection("deviceCommands").document(it) }
            if (commandRef != null) requireFreeCommand(tx.get(commandRef))
            var slot: Int? = null
            if (deviceId != null) {
                require(employee.fingerprintTemplateId == null && employee.pendingTemplateId == null) { "Hãy xóa mẫu vân tay cũ hoặc mẫu đang chờ trước khi đăng ký lại" }
                // Mapping giu cho ca mau dang cho; chi tai su dung sau khi xoa thanh cong.
                slot = (1..127).firstOrNull { !tx.get(db.collection("fingerprintMappings").document(it.toString())).exists() }
                require(slot != null) { "Đã hết 127 vị trí vân tay" }
            }
            if (old == null) {
                tx.set(ref, employee)
                tx.set(counter, mapOf("lastNumber" to employee.code.removePrefix("NV").toLong()))
            } else {
                tx.set(ref, employee)
            }
            if (slot != null && commandRef != null) {
                tx.update(ref, mapOf("pendingTemplateId" to slot, "fingerprintDeviceId" to deviceId))
                tx.set(db.collection("fingerprintMappings").document(slot.toString()), mapOf(
                    "employeeId" to ref.id, "employeeName" to employee.fullName, "templateId" to slot, "enabled" to false))
                tx.set(commandRef, mapOf("requestId" to requestId, "type" to "ENROLL_FINGERPRINT", "deviceId" to deviceId,
                    "employeeId" to ref.id, "employeeName" to employee.fullName, "templateId" to slot,
                    "status" to "REQUESTED", "createdAt" to FieldValue.serverTimestamp(), "applied" to false))
            }
            employee.code
        }.await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = if (input.id.isBlank()) AuditAction.EMPLOYEE_CREATE.name else AuditAction.EMPLOYEE_UPDATE.name,
            targetType = "employee",
            targetId = ref.id,
            details = "Lưu hồ sơ ${input.fullName}${if (deviceId != null) " và yêu cầu đăng ký vân tay" else ""}"
        ))
        return EmployeeSaveResult(ref.id, code)
    }

    suspend fun removeEmployeeOrFingerprint(employeeId: String, retire: Boolean) {
        val employeeRef = db.collection("employees").document(employeeId)
        val requestId = UUID.randomUUID().toString()
        // Older app versions did not always copy the slot back to employees.
        // Look up the mapping as a fallback so retiring that employee also
        // disables the still-enrolled sensor slot.
        val legacyMappingTemplateIds = db.collection("fingerprintMappings")
            .whereEqualTo("employeeId", employeeId)
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                document.getLong("templateId")?.toInt() ?: document.id.toIntOrNull()
            }
        db.runTransaction { tx ->
            val e = tx.get(employeeRef).toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
            val slot = resolveFingerprintTemplateId(e, legacyMappingTemplateIds)
            val commandRef = db.collection("deviceCommands").document(e.fingerprintDeviceId.ifBlank { "GATE-01" })
            val command = tx.get(commandRef)
            // Ban cu co the chua luu pendingTemplateId; tim mau cua lenh that bai.
            val legacySlot = if (command.getString("employeeId") == employeeId && command.getString("type") == "ENROLL_FINGERPRINT" && command.getString("status") != "COMPLETED") command.getLong("templateId")?.toInt() else null
            val template = slot ?: legacySlot
            if (template != null || command.getString("employeeId") == employeeId) requireFreeCommand(command)
            if (retire) tx.update(employeeRef, "active", false)
            if (template != null) {
                val mappingRef = db.collection("fingerprintMappings").document(template.toString())
                tx.set(mappingRef, mapOf("enabled" to false), SetOptions.merge())
                tx.update(employeeRef, "pendingTemplateId", template)
                tx.set(commandRef, mapOf("requestId" to requestId, "type" to "DELETE_FINGERPRINT",
                    "deviceId" to commandRef.id, "employeeId" to employeeId, "employeeName" to e.fullName,
                    "templateId" to template, "status" to "REQUESTED", "applied" to false,
                    "createdAt" to FieldValue.serverTimestamp()))
            }
        }.await()
        if (retire) deactivateEmployeeAccounts(employeeId)
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = if (retire) AuditAction.EMPLOYEE_UPDATE.name else AuditAction.FINGERPRINT_DELETE.name,
            targetType = "employee",
            targetId = employeeId,
            reason = if (retire) "Chuyển nhân viên sang đã nghỉ" else "Xóa mẫu vân tay trên thiết bị",
            details = "Yêu cầu xử lý qua deviceCommands"
        ))
    }

    private suspend fun deactivateEmployeeAccounts(employeeId: String) {
        val profiles = db.collection("users")
            .whereEqualTo("employeeId", employeeId)
            .get(Source.SERVER)
            .await()
            .documents
        if (profiles.isEmpty()) return
        db.runBatch { batch ->
            profiles.forEach { profile ->
                batch.update(profile.reference, mapOf(
                    "active" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
            }
        }.await()
    }
    fun observeEnrollmentCommands(): Flow<List<Map<String, Any>>> = callbackFlow {
        val listener = db.collection("deviceCommands").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().map { it.data.orEmpty() + ("commandId" to it.id) })
        }
        awaitClose { listener.remove() }
    }
    suspend fun applyCompletedEnrollment(command: Map<String, Any>) {
        val commandId = command["commandId"] as? String ?: return
        val ref = db.collection("deviceCommands").document(commandId)
        db.runTransaction { tx ->
            val current = tx.get(ref)
            if (current.getString("status") != "COMPLETED" || current.getBoolean("applied") == true) return@runTransaction
            val id = current.getString("employeeId") ?: error("Lệnh thiếu nhân viên")
            val template = current.getLong("templateId") ?: error("Lệnh thiếu mẫu vân tay")
            val employeeRef = db.collection("employees").document(id)
            val employee = tx.get(employeeRef).toObject(Employee::class.java)
            val mappingRef = db.collection("fingerprintMappings").document(template.toString())
            when (current.getString("type")) {
                "DELETE_FINGERPRINT" -> {
                    tx.delete(mappingRef)
                    if (employee != null) tx.update(employeeRef, mapOf("fingerprintTemplateId" to null, "pendingTemplateId" to null))
                }
                "ENROLL_FINGERPRINT" -> {
                    require(employee != null && employee.active) { "Nhân viên không còn hoạt động" }
                    tx.update(employeeRef, mapOf("fingerprintTemplateId" to template, "pendingTemplateId" to null, "fingerprintDeviceId" to commandId))
                    tx.set(mappingRef, mapOf("employeeId" to id, "employeeName" to employee.fullName, "templateId" to template, "enabled" to true))
                }
                else -> error("Loại lệnh không được hỗ trợ")
            }
            tx.update(ref, "applied", true)
        }.await()
    }
    suspend fun setSalary(employeeId: String, salary: Long) {
        require(salary in 0..1000000000000L) { "Mức lương không hợp lệ" }
        val ref = db.collection("employees").document(employeeId)
        db.runTransaction { tx ->
            val employee = tx.get(ref).toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
            require(employee.active) { "Nhân viên đã nghỉ" }
            tx.update(ref, "baseSalary", salary)
        }.await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.EMPLOYEE_UPDATE.name,
            targetType = "employee",
            targetId = employeeId,
            details = "Cập nhật mức lương cơ bản"
        ))
    }

    suspend fun updateEmployeeContact(employeeId: String, phone: String, address: String) {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        val cleanPhone = phone.trim()
        val cleanAddress = address.trim()
        require(cleanPhone.length <= 25 && cleanPhone.all { it.isDigit() || it in "+() -" }) {
            "Số điện thoại chỉ được chứa chữ số và ký tự + ( ) -"
        }
        require(cleanAddress.length <= 200) { "Địa chỉ tối đa 200 ký tự" }
        val profile = db.collection("users").document(currentUserId).get(Source.SERVER).await()
            .toObject(UserProfile::class.java)
        require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == employeeId) {
            "Tài khoản không được sửa thông tin liên hệ của nhân viên này"
        }
        db.collection("employees").document(employeeId).update(mapOf(
            "phone" to cleanPhone,
            "address" to cleanAddress
        )).await()
    }
    suspend fun savePayroll(employeeId: String, month: String, hoursWorked: Double, bonus: Long, deduction: Long) {
        require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)) { "Tháng phải có dạng yyyy-MM" }
        val employeeRef = db.collection("employees").document(employeeId)
        val ref = db.collection("payroll").document("${employeeId}_$month")
        db.runTransaction { tx ->
            val e = tx.get(employeeRef).toObject(Employee::class.java)?.copy(id=employeeId) ?: error("Không tìm thấy nhân viên")
            val previous = tx.get(ref)
            require(!previous.exists()) { "Đã lưu phiếu lương tháng này. Phiếu đã lưu được giữ nguyên." }
            tx.set(ref, createPayroll(e, month, hoursWorked, bonus, deduction))
        }.await()
        writeAuditLog(AuditLog(
            actorId = currentUserId,
            actorName = currentUserName,
            action = AuditAction.EMPLOYEE_UPDATE.name,
            targetType = "payroll",
            targetId = ref.id,
            details = "Lưu phiếu lương tháng $month"
        ))
    }

    private fun overtimeRequest(document: DocumentSnapshot): OvertimeRequest? {
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

    private fun auditLog(document: DocumentSnapshot): AuditLog = AuditLog(
        id = document.id,
        actorId = document.getString("actorId").orEmpty(),
        actorName = document.getString("actorName").orEmpty(),
        action = document.getString("action").orEmpty(),
        targetType = document.getString("targetType").orEmpty(),
        targetId = document.getString("targetId").orEmpty(),
        reason = document.getString("reason").orEmpty(),
        details = document.getString("details").orEmpty(),
        createdAt = document.getTimestamp("createdAt") ?: Timestamp.now()
    )

    private fun AuditLog.toFirestoreData(): Map<String, Any?> = mapOf(
        "actorId" to actorId,
        "actorName" to actorName,
        "action" to action,
        "targetType" to targetType,
        "targetId" to targetId,
        "reason" to reason,
        "details" to details,
        "createdAt" to FieldValue.serverTimestamp()
    )
}
