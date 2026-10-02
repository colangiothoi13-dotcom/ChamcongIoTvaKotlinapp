// Chức năng: Quản lý hồ sơ, tài khoản và đăng ký vân tay của nhân viên.
package vn.chamcong.iot.data

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.employeeAccountProfile
import vn.chamcong.iot.domain.validateEmployeeAccountInput
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val EMPLOYEE_ACCOUNT_APP_NAME = "employee-account-creator"

suspend fun FirebaseRepository.saveEmployee(employee: Employee, account: EmployeeAccountInput? = null): String {
    account?.let(::validateEmployeeAccountInput)
    val result = saveEmployeeInternal(employee, null)
    if (account != null) {
        try {
            createEmployeeAccount(result.id, account)
        } catch (error: Exception) {
            if (employee.id.isBlank()) runCatching { rollbackNewEmployeeProvisioning(result.id) }
            throw error
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
            if (employee.id.isBlank()) runCatching { rollbackNewEmployeeProvisioning(result.id) }
            throw error
        }
    }
    return result.code
}

private suspend fun FirebaseRepository.createEmployeeAccount(employeeId: String, input: EmployeeAccountInput) {
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

/** Compensates the employee/device writes when account provisioning fails. */
private suspend fun FirebaseRepository.rollbackNewEmployeeProvisioning(employeeId: String) {
    val employeeRef = db.collection("employees").document(employeeId)
    db.runTransaction { tx ->
        val employee = tx.get(employeeRef)
        if (!employee.exists()) return@runTransaction
        val pendingTemplateId = employee.getLong("pendingTemplateId")?.toInt()
        val deviceId = employee.getString("fingerprintDeviceId").orEmpty().ifBlank { "GATE-01" }
        val commandRef = db.collection("deviceCommands").document(deviceId)
        val command = tx.get(commandRef)
        val commandBelongsToEmployee = command.getString("employeeId") == employeeId
        if (commandBelongsToEmployee && command.getString("status") == "REQUESTED") {
            tx.delete(commandRef)
        }
        pendingTemplateId?.let { templateId ->
            tx.delete(db.collection("fingerprintMappings").document(templateId.toString()))
        }
        tx.delete(employeeRef)
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

suspend fun FirebaseRepository.removeEmployeeOrFingerprint(employeeId: String, retire: Boolean) {
    val employeeRef = db.collection("employees").document(employeeId)
    val requestId = UUID.randomUUID().toString()
    val retirementDateToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()
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
        val e = tx.get(employeeRef).toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
        val slot = resolveFingerprintTemplateId(e, legacyMappingTemplateIds)
        val commandRef = db.collection("deviceCommands").document(e.fingerprintDeviceId.ifBlank { "GATE-01" })
        val command = tx.get(commandRef)
        // Ban cu co the chua luu pendingTemplateId; tim mau cua lenh that bai.
        val legacySlot = if (command.getString("employeeId") == employeeId && command.getString("type") == "ENROLL_FINGERPRINT" && command.getString("status") != "COMPLETED") command.getLong("templateId")?.toInt() else null
        val template = slot ?: legacySlot
        if (template != null || command.getString("employeeId") == employeeId) requireFreeCommand(command)
        if (retire) {
            accountProfiles.forEach { profile -> tx.get(profile.reference) }
            val retirementDate = e.terminationDate
                .takeIf { value -> runCatching { LocalDate.parse(value) }.isSuccess }
                ?: retirementDateToday
            tx.update(employeeRef, mapOf("active" to false, "terminationDate" to retirementDate))
        }
        if (template != null) {
            val mappingRef = db.collection("fingerprintMappings").document(template.toString())
            tx.set(mappingRef, mapOf("enabled" to false), SetOptions.merge())
            tx.update(employeeRef, "pendingTemplateId", template)
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
    }.await()
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

fun FirebaseRepository.observeEnrollmentCommands(): Flow<List<Map<String, Any>>> = callbackFlow {
    val listener = db.collection("deviceCommands").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().map { it.data.orEmpty() + ("commandId" to it.id) })
    }
    awaitClose { listener.remove() }
}
suspend fun FirebaseRepository.applyCompletedEnrollment(command: Map<String, Any>) {
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
