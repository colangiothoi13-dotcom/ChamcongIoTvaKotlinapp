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
import vn.chamcong.iot.domain.employeeAccountProfile
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.notificationForRequest
import vn.chamcong.iot.domain.reviewRequest
import vn.chamcong.iot.domain.reviewOvertimeRequest as applyOvertimeReview
import vn.chamcong.iot.domain.validateOvertimeHours
import vn.chamcong.iot.domain.validateOvertimeRequest
import vn.chamcong.iot.domain.validateRequest
import vn.chamcong.iot.domain.validateShift
import vn.chamcong.iot.domain.validateWorkedHoursOverride
import vn.chamcong.iot.domain.validateAuditLog
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

    fun observeEmployeeAttendanceAdjustments(employeeId: String): Flow<List<AttendanceAdjustment>> {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        // Sort locally so the employee shell needs no additional composite index.
        return observeAdjustments(db.collection("attendanceAdjustments").whereEqualTo("employeeId", employeeId))
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

    private fun AttendanceAdjustment.toFirestoreData(): Map<String, Any?> = mapOf(
        "employeeId" to employeeId, "employeeName" to employeeName, "scheduleDate" to scheduleDate,
        "checkInAt" to checkInAt?.let { Timestamp(it.epochSecond, it.nano) },
        "checkOutAt" to checkOutAt?.let { Timestamp(it.epochSecond, it.nano) },
        "workedHoursOverride" to workedHoursOverride, "reason" to reason,
        "actorId" to actorId, "actorName" to actorName, "createdAt" to FieldValue.serverTimestamp()
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
        validateOvertimeHours(schedule.overtimeHours)
        validateWorkedHoursOverride(schedule.workedHoursOverride)
        if (schedule.workedHoursOverride != null) require(schedule.adjustmentNote.isNotBlank()) { "Cần nhập lý do điều chỉnh giờ" }
        val id = scheduleDocumentId(schedule.employeeId, date)
        db.collection("workSchedules").document(id).set(schedule.copy(id = "", date = date)).await()
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
        validateShift(shift)
        require(schedules.isNotEmpty())
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
                                "shiftName" to shift.name, "overtimeHours" to 0, "assignedBy" to currentUserId,
                                "source" to "EMPLOYEE"), SetOptions.merge())
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

    suspend fun assignShiftToDepartment(
        department: String,
        dates: List<String>,
        shift: WorkShift,
        overtimeHours: Int,
        assignedBy: String
    ) {
        require(department.isNotBlank()) { "Chưa chọn phòng ban" }
        require(dates.isNotEmpty()) { "Chưa chọn ngày phân ca" }
        validateShift(shift)
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
            employees.forEach { document ->
                val employee = document.toObject(Employee::class.java) ?: return@forEach
                val id = scheduleDocumentId(document.id, date)
                val schedule = WorkSchedule(
                    id = id,
                    employeeId = document.id,
                    employeeName = employee.fullName,
                    department = employee.department,
                    shiftId = shift.id,
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
        val newSchedules = copyScheduleToNextWeek(source, existingIds).map { it.copy(assignedBy = assignedBy) }
        val batch = db.batch()
        newSchedules.forEach { schedule ->
            batch.set(db.collection("workSchedules").document(schedule.id), schedule.copy(id = ""))
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
        val profile = db.collection("users").document(currentUserId).get().await().toObject(UserProfile::class.java)
        require(profile?.role == UserRole.EMPLOYEE.name && profile.active && profile.employeeId == request.employeeId) {
            "Tài khoản không được gửi đơn cho nhân viên này"
        }
        val requestRef = db.collection("leaveRequests").document()
        requestRef.set(request.copy(id = requestRef.id, status = RequestStatus.PENDING.name).toFirestoreData()).await()
        return requestRef.id
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
            val reviewed = reviewRequest(current, status, reviewerId, reviewerName, note, com.google.firebase.Timestamp.now())
            transaction.update(ref, mapOf<String, Any?>(
                "status" to reviewed.status,
                "reviewerId" to reviewed.reviewerId,
                "reviewerName" to reviewed.reviewerName,
                "reviewedAt" to FieldValue.serverTimestamp(),
                "reviewNote" to reviewed.reviewNote
            ))
        }.await()
        writeAuditLog(AuditLog(
            actorId = reviewerId,
            actorName = reviewerName,
            action = AuditAction.LEAVE_REVIEW.name,
            targetType = "leaveRequest",
            targetId = requestId,
            reason = note,
            details = "Xử lý đơn với trạng thái ${status.name}"
        ))
    }

    suspend fun submitOvertimeRequest(request: OvertimeRequest): String {
        validateOvertimeRequest(request)
        require(request.status == OvertimeRequestStatus.PENDING.name) {
            "Đơn tăng ca phải ở trạng thái chờ duyệt"
        }
        require(request.createdAt == null && request.reviewerId == null && request.reviewerName == null &&
            request.reviewedAt == null && request.rejectionReason == null) {
            "Nhân viên không được tự nhập thông tin duyệt đơn"
        }
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
            department = employee.department
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
            val employee = old ?: input.copy(code=nextEmployeeCode(counterDoc?.getLong("lastNumber") ?: 0, codes), id="")
            require(employee.active) { "Nhân viên đã nghỉ" }
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
