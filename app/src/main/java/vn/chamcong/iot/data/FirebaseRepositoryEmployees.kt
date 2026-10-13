// Chức năng: Quản lý hồ sơ, tài khoản và đăng ký vân tay của nhân viên.
package vn.chamcong.iot.data

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.chamcong.iot.domain.canRollbackNewEmployeeProvisioning
import vn.chamcong.iot.domain.employeeAccountProfile
import vn.chamcong.iot.domain.validateEmployeeAccountInput
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.domain.requireEmployeeAccountAdmin
import vn.chamcong.iot.domain.requireEmployeeAccountProvisioningCurrent
import vn.chamcong.iot.domain.nextEmployeeAccountVersion
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val EMPLOYEE_ACCOUNT_APP_NAME = "employee-account-creator"
private val employeeAccountCreationMutex = Mutex()

suspend fun FirebaseRepository.saveEmployee(employee: Employee, account: EmployeeAccountInput? = null): String {
    account?.let(::validateEmployeeAccountInput)
    val result = saveEmployeeInternal(employee, null)
    if (account != null) {
        try {
            createEmployeeAccount(result.id, account)
        } catch (error: Exception) {
            handleEmployeeAccountFailure(employee, result, error)
        }
    }
    return result.code
}

suspend fun FirebaseRepository.saveAndRequestFingerprint(
    employee: Employee,
    deviceId: String,
    account: EmployeeAccountInput? = null
): String {
    account?.let(::validateEmployeeAccountInput)
    val result = saveEmployeeInternal(employee, deviceId.trim())
    if (account != null) {
        try {
            createEmployeeAccount(result.id, account)
        } catch (error: Exception) {
            handleEmployeeAccountFailure(employee, result, error)
        }
    }
    return result.code
}

private suspend fun FirebaseRepository.requireAccountAdmin(): UserProfile {
    val user = auth.currentUser ?: error("Chưa đăng nhập")
    val profile = db.collection("users").document(user.uid).get(Source.SERVER).await()
        .toObject(UserProfile::class.java)?.copy(uid = user.uid)
    requireEmployeeAccountAdmin(profile, user.uid, user.isAnonymous)
    require(currentUserId == user.uid) { "Phiên đăng nhập đã thay đổi" }
    return requireNotNull(profile)
}

/** Admin reads only the accounts linked to the selected employee. */
fun FirebaseRepository.observeEmployeeAccounts(employeeId: String): Flow<List<UserProfile>> = callbackFlow {
    require(employeeId.isNotBlank() && '/' !in employeeId) { "Mã nhân viên không hợp lệ" }
    val actor = requireAccountAdmin()
    val listener = db.collection("users").whereEqualTo("employeeId", employeeId)
        .addSnapshotListener { snapshot, error ->
            if (currentUserId != actor.uid) close(IllegalStateException("Phiên đăng nhập đã thay đổi"))
            else if (error != null) close(error)
            else runCatching { snapshot?.documents.orEmpty().mapNotNull { document ->
                document.toObject(UserProfile::class.java)?.copy(uid = document.id)
            }.sortedBy { it.email } }.onSuccess { trySend(it) }.onFailure { close(it) }
        }
    awaitClose { listener.remove() }
}

private suspend fun FirebaseRepository.createEmployeeAccount(employeeId: String, input: EmployeeAccountInput) {
    provisionEmployeeAccount(employeeId, input)
}

/** Creates credentials on secondary Auth and atomically links them to a still-active employee. */
suspend fun FirebaseRepository.provisionEmployeeAccount(employeeId: String, input: EmployeeAccountInput): String = employeeAccountCreationMutex.withLock {
    validateEmployeeAccountInput(input)
    require(employeeId.isNotBlank() && '/' !in employeeId) { "Mã nhân viên không hợp lệ" }
    val actor = requireAccountAdmin()
    val employeeRef = db.collection("employees").document(employeeId)
    val baseline = employeeRef.get(Source.SERVER).await()
    val baselineVersion = baseline.getLong("accountVersion") ?: 0L
    val linked = db.collection("users").whereEqualTo("employeeId", employeeId).get(Source.SERVER).await()
    requireEmployeeAccountProvisioningCurrent(baseline.exists(), baseline.getBoolean("active") == true,
        baselineVersion, baselineVersion, !linked.isEmpty)
    require(currentUserId == actor.uid) { "Phiên đăng nhập đã thay đổi" }
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
        val profileRef = db.collection("users").document(user.uid)
        val auditRef = db.collection("audit_logs").document()
        db.runTransaction { tx ->
            require(currentUserId == actor.uid) { "Phiên đăng nhập đã thay đổi" }
            val currentActor = tx.get(db.collection("users").document(actor.uid))
                .toObject(UserProfile::class.java)?.copy(uid = actor.uid)
            val employee = tx.get(employeeRef)
            val existing = tx.get(profileRef)
            requireEmployeeAccountAdmin(currentActor, actor.uid, auth.currentUser?.isAnonymous != false)
            val version = employee.getLong("accountVersion") ?: 0L
            requireEmployeeAccountProvisioningCurrent(employee.exists(), employee.getBoolean("active") == true,
                baselineVersion, version, existing.exists())
            val audit = AuditLog(actorId = actor.uid,
                actorName = currentActor?.displayName?.takeIf(String::isNotBlank) ?: actor.email,
                action = AuditAction.ACCOUNT_CREATE.name, targetType = "user", targetId = user.uid,
                details = "Tạo tài khoản EMPLOYEE cho nhân viên $employeeId")
            validateAuditLog(audit)
            tx.update(employeeRef, "accountVersion", nextEmployeeAccountVersion(version))
            tx.set(profileRef, mapOf("uid" to profile.uid, "email" to profile.email,
                "displayName" to profile.displayName, "role" to profile.role, "active" to true,
                "employeeId" to employeeId, "updatedAt" to FieldValue.serverTimestamp()))
            tx.set(auditRef, audit.toFirestoreData())
        }.await()
        user.uid
    } catch (error: Exception) {
        if (createdUid != null) {
            val cleaned = withContext(NonCancellable) {
                runCatching {
                    val created = accountAuth.currentUser ?: error("Không tìm thấy tài khoản vừa tạo để dọn")
                    require(created.uid == createdUid) { "Tài khoản Auth phụ đã thay đổi" }
                    created.delete().await()
                }.isSuccess
            }
            if (!cleaned) throw IllegalStateException("Chưa lưu được hồ sơ và chưa dọn được tài khoản Auth vừa tạo. Kiểm tra email này trong Firebase Console trước khi thử lại.", error)
        }
        throw error
    } finally {
        accountAuth.signOut()
    }
}

private suspend fun FirebaseRepository.handleEmployeeAccountFailure(
    employee: Employee,
    result: EmployeeSaveResult,
    error: Exception
): Nothing {
    if (error is CancellationException) throw error
    if (employee.id.isBlank()) {
        val rolledBack = try {
            rollbackNewEmployeeProvisioning(result.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rollbackError: Exception) {
            error.addSuppressed(rollbackError)
            throw IllegalStateException(
                "Chưa tạo được tài khoản và chưa xác nhận được hoàn tác hồ sơ ${result.code}. " +
                    "Kiểm tra Nhân viên và trạng thái thiết bị trước khi tạo lại.",
                error
            )
        }
        if (!rolledBack) throw IllegalStateException(
            "Chưa tạo được tài khoản cho ${result.code}. Không thể hoàn tác đăng ký vân tay an toàn; " +
                "hồ sơ và ánh xạ được giữ lại. Kiểm tra Nhân viên trước khi tạo lại hoặc xóa vân tay.",
            error
        )
    }
    throw error
}

/** Compensates only writes whose enrollment command has not been accepted by the device. */
private suspend fun FirebaseRepository.rollbackNewEmployeeProvisioning(employeeId: String): Boolean {
    val employeeRef = db.collection("employees").document(employeeId)
    return db.runTransaction { tx ->
        val snapshot = tx.get(employeeRef)
        if (!snapshot.exists()) return@runTransaction true
        val employee = snapshot.toObject(Employee::class.java)?.copy(id = employeeId)
            ?: error("Không đọc được hồ sơ cần hoàn tác")
        val pendingTemplateId = employee.pendingTemplateId
        val deviceId = employee.fingerprintDeviceId.ifBlank { "GATE-01" }
        val commandRef = db.collection("deviceCommands").document(deviceId)
        val command = tx.get(commandRef)
        val commandBelongsToEmployee = command.getString("employeeId") == employeeId
        if (!canRollbackNewEmployeeProvisioning(
                employee, command.getString("employeeId"), command.getString("status")
            )) return@runTransaction false
        val mappingRef = pendingTemplateId?.let { db.collection("fingerprintMappings").document(it.toString()) }
        val mapping = mappingRef?.let(tx::get)
        if (mapping != null && mapping.exists() &&
            (mapping.getString("employeeId") != employeeId || mapping.getBoolean("enabled") == true)
        ) return@runTransaction false
        if (commandBelongsToEmployee && command.getString("status") == "REQUESTED") {
            tx.delete(commandRef)
        }
        mappingRef?.let(tx::delete)
        tx.delete(employeeRef)
        true
    }.await()
}

internal fun FirebaseRepository.requireFreeCommand(command: DocumentSnapshot) {
    val status = command.getString("status")
    require(status !in listOf("REQUESTED", "PROCESSING") && !(status == "COMPLETED" && command.getBoolean("applied") != true)) {
        "Thiết bị đang có lệnh chưa xử lý xong. Hãy bật thiết bị và chờ hoàn tất."
    }
}
private data class EmployeeSaveResult(val id: String, val code: String)

private suspend fun FirebaseRepository.saveEmployeeInternal(input: Employee, deviceId: String?): EmployeeSaveResult {
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
    val auditRef = db.collection("audit_logs").document()
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
        } else tx.update(ref, mapOf("fullName" to employee.fullName, "email" to employee.email,
            "phone" to employee.phone, "address" to employee.address, "departmentId" to employee.departmentId,
            "department" to employee.department, "position" to employee.position, "hireDate" to employee.hireDate))
        if (slot != null && commandRef != null) {
            tx.update(ref, mapOf("pendingTemplateId" to slot, "fingerprintDeviceId" to deviceId))
            tx.set(db.collection("fingerprintMappings").document(slot.toString()), mapOf(
                "employeeId" to ref.id, "employeeName" to employee.fullName, "templateId" to slot, "enabled" to false))
            tx.set(commandRef, mapOf("requestId" to requestId, "type" to "ENROLL_FINGERPRINT", "deviceId" to deviceId,
                "employeeId" to ref.id, "employeeName" to employee.fullName, "templateId" to slot,
                "status" to "REQUESTED", "createdAt" to FieldValue.serverTimestamp(), "applied" to false))
        }
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = if (input.id.isBlank()) AuditAction.EMPLOYEE_CREATE.name else AuditAction.EMPLOYEE_UPDATE.name,
            targetType = "employee", targetId = ref.id,
            details = "Lưu hồ sơ ${input.fullName}${if (deviceId != null) " và yêu cầu đăng ký vân tay" else ""}"
        )
        validateAuditLog(audit)
        tx.set(auditRef, audit.toFirestoreData())
        employee.code
    }.await()
    return EmployeeSaveResult(ref.id, code)
}

suspend fun FirebaseRepository.removeEmployeeOrFingerprint(employeeId: String, retire: Boolean) {
    val employeeRef = db.collection("employees").document(employeeId)
    val accountBaselineVersion = if (retire) employeeRef.get(Source.SERVER).await().getLong("accountVersion") ?: 0L else null
    val requestId = UUID.randomUUID().toString()
    val retirementDateToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()
    val auditRef = db.collection("audit_logs").document()
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
    val accountProfiles = if (retire) db.collection("users")
        .whereEqualTo("employeeId", employeeId)
        .get(Source.SERVER)
        .await()
        .documents else emptyList()
    db.runTransaction { tx ->
        val employeeSnapshot = tx.get(employeeRef)
        val e = employeeSnapshot.toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
        if (retire) require((employeeSnapshot.getLong("accountVersion") ?: 0L) == accountBaselineVersion) {
            "Tài khoản liên kết vừa thay đổi. Tải lại nhân viên trước khi chuyển sang đã nghỉ"
        }
        val slot = resolveFingerprintTemplateId(e, legacyMappingTemplateIds)
        val commandRef = db.collection("deviceCommands").document(e.fingerprintDeviceId.ifBlank { "GATE-01" })
        val command = tx.get(commandRef)
        // Ban cu co the chua luu pendingTemplateId; tim mau cua lenh that bai.
        val legacySlot = if (command.getString("employeeId") == employeeId && command.getString("type") == "ENROLL_FINGERPRINT" && command.getString("status") != "COMPLETED") command.getLong("templateId")?.toInt() else null
        val template = slot ?: legacySlot
        val mappingRef = template?.let { db.collection("fingerprintMappings").document(it.toString()) }
        val mapping = mappingRef?.let(tx::get)
        if (template != null || command.getString("employeeId") == employeeId) requireFreeCommand(command)
        require(mapping == null || !mapping.exists() || mapping.getString("employeeId") in listOf(null, employeeId)) {
            "Mẫu vân tay đang liên kết với nhân viên khác"
        }
        if (retire) {
            accountProfiles.forEach { profile ->
                val fresh = tx.get(profile.reference)
                require(fresh.exists() && fresh.getString("employeeId") == employeeId) {
                    "Tài khoản liên kết vừa thay đổi. Tải lại trước khi chuyển sang đã nghỉ"
                }
            }
            val retirementDate = e.terminationDate
                .takeIf { value -> runCatching { LocalDate.parse(value) }.isSuccess }
                ?: retirementDateToday
            tx.update(employeeRef, mapOf("active" to false, "terminationDate" to retirementDate))
        }
        if (template != null) {
            tx.set(requireNotNull(mappingRef), mapOf(
                "employeeId" to employeeId, "employeeName" to e.fullName,
                "templateId" to template, "enabled" to false
            ), SetOptions.merge())
            // Persist the fallback device ID for legacy employee documents too;
            // device completion rules read this stored field, not Kotlin defaults.
            tx.update(employeeRef, mapOf(
                "pendingTemplateId" to template,
                "fingerprintDeviceId" to commandRef.id
            ))
            tx.set(commandRef, mapOf("requestId" to requestId, "type" to "DELETE_FINGERPRINT",
                "deviceId" to commandRef.id, "employeeId" to employeeId, "employeeName" to e.fullName,
                "templateId" to template, "status" to "REQUESTED", "applied" to false,
                "createdAt" to FieldValue.serverTimestamp()))
        }
        if (retire) {
            accountProfiles.forEach { profile ->
                tx.update(profile.reference, mapOf(
                    "active" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
            }
        }
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = if (retire) AuditAction.EMPLOYEE_UPDATE.name else AuditAction.FINGERPRINT_DELETE.name,
            targetType = "employee", targetId = employeeId,
            reason = if (retire) "Chuyển nhân viên sang đã nghỉ" else "Xóa mẫu vân tay trên thiết bị",
            details = "Yêu cầu xử lý qua deviceCommands"
        )
        validateAuditLog(audit)
        tx.set(auditRef, audit.toFirestoreData())
    }.await()
}

fun FirebaseRepository.observeEnrollmentCommands(): Flow<List<Map<String, Any>>> = callbackFlow {
    val listener = db.collection("deviceCommands").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().map { it.data.orEmpty() + ("commandId" to it.id) })
    }
    awaitClose { listener.remove() }
}
