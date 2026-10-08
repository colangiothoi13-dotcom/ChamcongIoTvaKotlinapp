// Chức năng: Quản lý phòng ban, thông báo, thiết bị và bảng lương trên Firebase.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.validateAuditLog
import vn.chamcong.iot.model.*
import java.util.UUID

suspend fun FirebaseRepository.saveDepartment(departmentId: String?, rawName: String): String {
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

    val auditRef = db.collection("audit_logs").document()
    val audit = AuditLog(
        actorId = currentUserId, actorName = currentUserName,
        action = AuditAction.DEPARTMENT_UPDATE.name, targetType = "department", targetId = ref.id,
        details = if (old == null) "Tạo phòng ban $name" else "Đổi tên phòng ban ${old.getString("name")} thành $name"
    )
    validateAuditLog(audit)
    db.runTransaction { transaction ->
        val current = transaction.get(ref)
        if (old == null) {
            require(!current.exists()) { "Phòng ban đã được tạo. Vui lòng tải lại danh sách." }
        } else {
            require(current.exists() && current.getString("name") == old.getString("name")) {
                "Phòng ban đã thay đổi. Vui lòng tải lại trước khi đổi tên."
            }
        }
        // Read all candidate profiles before writes; a transfer causes retry and is skipped.
        val employees = affectedEmployees.associateWith { transaction.get(it.reference) }
        if (old == null) {
            transaction.set(ref, mapOf("name" to name, "active" to true, "createdAt" to now, "updatedAt" to now))
        } else {
            // Preserve current active/createdAt instead of replacing them from the preliminary query.
            transaction.update(ref, mapOf("name" to name, "updatedAt" to now))
        }
        employees.forEach { (candidate, employee) ->
            val currentDepartmentId = employee.getString("departmentId").orEmpty()
            val stillMember = currentDepartmentId == ref.id ||
                (currentDepartmentId.isBlank() && employee.getString("department") == old?.getString("name"))
            if (employee.exists() && stillMember) {
                transaction.update(candidate.reference, mapOf("department" to name, "departmentId" to ref.id))
            }
        }
        transaction.set(auditRef, audit.toFirestoreData())
    }.await()
    return ref.id
}

suspend fun FirebaseRepository.setDepartmentActive(departmentId: String, active: Boolean) {
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

suspend fun FirebaseRepository.sendAnnouncement(targetDepartment: String?, title: String, body: String): Int {
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

internal fun FirebaseRepository.deviceSnapshot(document: DocumentSnapshot): DeviceSnapshot =
    deviceSnapshotFromFields(document.id, document.data.orEmpty())

suspend fun FirebaseRepository.updateDeviceConfiguration(deviceId: String, name: String, location: String) {
    require(deviceId.isNotBlank()) { "Mã thiết bị không hợp lệ" }
    require(name.isNotBlank()) { "Tên thiết bị không được để trống" }
    val audit = AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = AuditAction.DEVICE_CONFIG_UPDATE.name,
        targetType = "device",
        targetId = deviceId,
        details = "Đổi tên/vị trí thiết bị thành ${name.trim()} / ${location.trim()}"
    )
    validateAuditLog(audit)
    db.runBatch { batch ->
        batch.update(db.collection("devices").document(deviceId),
            mapOf("name" to name.trim(), "location" to location.trim()))
        batch.set(db.collection("audit_logs").document(), audit.toFirestoreData())
    }.await()
}

suspend fun FirebaseRepository.requestDeviceCommand(deviceId: String, type: DeviceCommandType): String {
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

suspend fun FirebaseRepository.setSalary(employeeId: String, salary: Long) {
    require(salary in 0..1000000000000L) { "Mức lương không hợp lệ" }
    val ref = db.collection("employees").document(employeeId)
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { tx ->
        val employee = tx.get(ref).toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
        require(employee.active) { "Nhân viên đã nghỉ" }
        tx.update(ref, "baseSalary", salary)
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.EMPLOYEE_UPDATE.name, targetType = "employee",
            targetId = employeeId, details = "Cập nhật mức lương cơ bản"
        )
        validateAuditLog(audit)
        tx.set(auditRef, audit.toFirestoreData())
    }.await()
}

suspend fun FirebaseRepository.updateEmployeeContact(employeeId: String, phone: String, address: String) {
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
suspend fun FirebaseRepository.savePayroll(employeeId: String, month: String, hoursWorked: Double, bonus: Long, deduction: Long) {
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)) { "Tháng phải có dạng yyyy-MM" }
    val employeeRef = db.collection("employees").document(employeeId)
    val ref = db.collection("payroll").document("${employeeId}_$month")
    val auditRef = db.collection("audit_logs").document()
    db.runTransaction { tx ->
        val e = tx.get(employeeRef).toObject(Employee::class.java)?.copy(id=employeeId) ?: error("Không tìm thấy nhân viên")
        val previous = tx.get(ref)
        require(!previous.exists()) { "Đã lưu phiếu lương tháng này. Phiếu đã lưu được giữ nguyên." }
        tx.set(ref, createPayroll(e, month, hoursWorked, bonus, deduction))
        val audit = AuditLog(
            actorId = currentUserId, actorName = currentUserName,
            action = AuditAction.EMPLOYEE_UPDATE.name, targetType = "payroll",
            targetId = ref.id, details = "Lưu phiếu lương tháng $month"
        )
        validateAuditLog(audit)
        tx.set(auditRef, audit.toFirestoreData())
    }.await()
}
