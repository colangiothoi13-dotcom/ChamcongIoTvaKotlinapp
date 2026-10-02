// Chức năng: Lắng nghe dữ liệu Firebase và tải các trang chấm công cho giao diện.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.util.Date

private const val DEFAULT_ATTENDANCE_REALTIME_LIMIT = 500L
private const val DEFAULT_ATTENDANCE_PAGE_SIZE = 200L
private const val MAX_ATTENDANCE_PAGE_SIZE = 500L

fun FirebaseRepository.observeEmployees(): Flow<List<Employee>> = callbackFlow {
    val listener = db.collection("employees").orderBy("fullName").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Employee::class.java)?.copy(id=it.id) })
    }
    awaitClose { listener.remove() }
}
fun FirebaseRepository.observeEmployee(employeeId: String): Flow<Employee?> = callbackFlow {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    val listener = db.collection("employees").document(employeeId).addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.toObject(Employee::class.java)?.copy(id = employeeId))
    }
    awaitClose { listener.remove() }
}
fun FirebaseRepository.observeEmployeeAttendance(employeeId: String): Flow<List<Attendance>> = callbackFlow {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    val listener = db.collection("attendance")
        .whereEqualTo("employeeId", employeeId)
        .orderBy("timestamp", Query.Direction.DESCENDING)
        .limit(DEFAULT_ATTENDANCE_REALTIME_LIMIT)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty()
                .mapNotNull { it.toObject(Attendance::class.java)?.copy(id = it.id) }
                .sortedByDescending { it.timestamp.toDate().time })
        }
    awaitClose { listener.remove() }
}
fun FirebaseRepository.observeEmployeeSchedules(employeeId: String): Flow<List<WorkSchedule>> = callbackFlow {
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
fun FirebaseRepository.observeEmployeeRequests(employeeId: String): Flow<List<LeaveRequest>> = callbackFlow {
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
fun FirebaseRepository.observeEmployeeOvertimeRequests(employeeId: String): Flow<List<OvertimeRequest>> = callbackFlow {
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

fun FirebaseRepository.observeRecentAttendance(): Flow<List<Attendance>> = callbackFlow {
    val listener = db.collection("attendance").orderBy("timestamp", Query.Direction.DESCENDING).limit(50)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Attendance::class.java)?.copy(id=it.id) })
        }
    awaitClose { listener.remove() }
}
fun FirebaseRepository.observeAllAttendance(): Flow<List<Attendance>> = callbackFlow {
    // Keep the realtime shell bounded on Spark. Historical reports should
    // use fetchAttendancePage() with an explicit date range instead of
    // opening a listener over the whole collection.
    val listener = db.collection("attendance")
        .orderBy("timestamp", Query.Direction.DESCENDING)
        .limit(DEFAULT_ATTENDANCE_REALTIME_LIMIT)
        .addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty()
            .mapNotNull { it.toObject(Attendance::class.java)?.copy(id = it.id) }
            .sortedByDescending { it.timestamp.toDate().time })
    }
    awaitClose { listener.remove() }
}
fun FirebaseRepository.observePayroll(): Flow<List<Payroll>> = callbackFlow {
    val listener = db.collection("payroll").orderBy("month", Query.Direction.DESCENDING).addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Payroll::class.java) })
    }
    awaitClose { listener.remove() }
}

/**
 * Loads a bounded attendance page for reports/export. The cursor is the
 * last document returned by the previous call, so old data is fetched on
 * demand rather than kept in a realtime listener or in the ViewModel.
 */
suspend fun FirebaseRepository.fetchAttendancePage(
    startDate: LocalDate,
    endDate: LocalDate,
    employeeId: String? = null,
    pageSize: Long = DEFAULT_ATTENDANCE_PAGE_SIZE,
    after: DocumentSnapshot? = null
): AttendancePage {
    require(!endDate.isBefore(startDate)) { "Khoảng ngày chấm công không hợp lệ" }
    val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    val start = Timestamp(Date.from(startDate.atStartOfDay(zone).toInstant()))
    val endExclusive = Timestamp(Date.from(endDate.plusDays(1).atStartOfDay(zone).toInstant()))
    val safePageSize = pageSize.coerceIn(1L, MAX_ATTENDANCE_PAGE_SIZE)
    var query: Query = db.collection("attendance")
        .whereGreaterThanOrEqualTo("timestamp", start)
        .whereLessThan("timestamp", endExclusive)
    employeeId?.trim()?.takeIf(String::isNotBlank)?.let {
        query = query.whereEqualTo("employeeId", it)
    }
    query = query.orderBy("timestamp", Query.Direction.DESCENDING).limit(safePageSize)
    if (after != null) query = query.startAfter(after)
    val snapshot = query.get(Source.SERVER).await()
    return AttendancePage(
        rows = snapshot.documents.mapNotNull { document ->
            document.toObject(Attendance::class.java)?.copy(id = document.id)
        },
        nextCursor = snapshot.documents.lastOrNull()
    )
}

/**
 * Loads one ordered page for an employee profile. The first page contains
 * the newest rows; subsequent calls use the last document as a cursor so
 * the profile can progressively reveal attendance older than the realtime
 * 500-row shell.
 */
suspend fun FirebaseRepository.fetchEmployeeAttendancePage(
    employeeId: String,
    pageSize: Long = MAX_ATTENDANCE_PAGE_SIZE,
    after: DocumentSnapshot? = null
): AttendancePage {
    require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
    val safePageSize = pageSize.coerceIn(1L, MAX_ATTENDANCE_PAGE_SIZE)
    var query: Query = db.collection("attendance")
        .whereEqualTo("employeeId", employeeId.trim())
        .orderBy("timestamp", Query.Direction.DESCENDING)
        .limit(safePageSize)
    if (after != null) query = query.startAfter(after)
    val snapshot = query.get(Source.SERVER).await()
    return AttendancePage(
        rows = snapshot.documents.mapNotNull { document ->
            document.toObject(Attendance::class.java)?.copy(id = document.id)
        },
        nextCursor = snapshot.documents.lastOrNull()
    )
}
fun FirebaseRepository.observeDepartments(): Flow<List<Department>> = callbackFlow {
    val listener = db.collection("departments").orderBy("name").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { document ->
            document.toObject(Department::class.java)?.copy(id = document.id)
        })
    }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeEmployeePayroll(employeeId: String): Flow<List<Payroll>> = callbackFlow {
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
fun FirebaseRepository.observeDevices(): Flow<List<DeviceSnapshot>> = callbackFlow {
    val listener = db.collection("devices").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().map(::deviceSnapshot))
    }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeShifts(): Flow<List<WorkShift>> = callbackFlow {
    val listener = db.collection("shifts").orderBy("category").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(WorkShift::class.java)?.copy(id = it.id) })
    }
    awaitClose { listener.remove() }
}

// Admin calculation context spans arbitrary report/payroll periods and overnight boundaries.
fun FirebaseRepository.observeSchedules(): Flow<List<WorkSchedule>> = callbackFlow {
    val listener = db.collection("workSchedules")
        .orderBy("date")
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) })
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeLeaveRequests(): Flow<List<LeaveRequest>> = callbackFlow {
    val listener = db.collection("leaveRequests")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(LeaveRequest::class.java)?.copy(id = it.id) })
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeOvertimeRequests(): Flow<List<OvertimeRequest>> = callbackFlow {
    val listener = db.collection("overtimeRequests")
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty()
                .mapNotNull(::overtimeRequest)
                .sortedByDescending { it.createdAt ?: Instant.MIN })
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeNotifications(): Flow<List<AppNotification>> = callbackFlow {
    val listener = db.collection("notifications")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .limit(100)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(AppNotification::class.java)?.copy(id = it.id) })
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeWeeklyScheduleRequests(): Flow<List<WeeklyScheduleRequest>> = callbackFlow {
    val listener = db.collection("weeklyScheduleRequests")
        .orderBy("weekStart", Query.Direction.DESCENDING)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull(::weeklyScheduleRequest))
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeEmployeeWeeklyScheduleRequest(
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

fun FirebaseRepository.observeEmployeeNotifications(employeeId: String): Flow<List<AppNotification>> = callbackFlow {
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

fun FirebaseRepository.observeAnnouncements(): Flow<List<Announcement>> = callbackFlow {
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

fun FirebaseRepository.observeAuditLogs(): Flow<List<AuditLog>> = callbackFlow {
    val listener = db.collection("audit_logs")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .limit(200)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().map(::auditLog))
        }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeUserProfile(): Flow<UserProfile?> = callbackFlow {
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
