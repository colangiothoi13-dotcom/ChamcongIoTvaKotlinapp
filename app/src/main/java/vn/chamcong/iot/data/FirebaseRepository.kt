package vn.chamcong.iot.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.*
import java.util.UUID

class FirebaseRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    val isSignedIn: Boolean get() = auth.currentUser != null
    suspend fun signIn(email: String, password: String) { auth.signInWithEmailAndPassword(email.trim(), password).await() }
    fun signOut() = auth.signOut()

    fun observeEmployees(): Flow<List<Employee>> = callbackFlow {
        val listener = db.collection("employees").orderBy("fullName").addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Employee::class.java)?.copy(id=it.id) })
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
    fun observePayroll(): Flow<List<Payroll>> = callbackFlow {
        val listener = db.collection("payroll").orderBy("month", Query.Direction.DESCENDING).addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().mapNotNull { it.toObject(Payroll::class.java) })
        }
        awaitClose { listener.remove() }
    }
    suspend fun saveEmployee(employee: Employee): String = saveEmployeeInternal(employee, null)
    suspend fun saveAndRequestFingerprint(employee: Employee, deviceId: String): String = saveEmployeeInternal(employee, deviceId.trim())

    private fun requireFreeCommand(command: DocumentSnapshot) {
        val status = command.getString("status")
        require(status !in listOf("REQUESTED", "PROCESSING") && !(status == "COMPLETED" && command.getBoolean("applied") != true)) {
            "Thiết bị đang có lệnh chưa xử lý xong. Hãy bật thiết bị và chờ hoàn tất."
        }
    }
    private suspend fun saveEmployeeInternal(input: Employee, deviceId: String?): String {
        require(input.fullName.isNotBlank()) { "Vui lòng nhập họ tên" }
        if (deviceId != null) require(deviceId.isNotBlank() && !deviceId.contains('/')) { "Mã thiết bị không hợp lệ" }
        val ref = if (input.id.isBlank()) db.collection("employees").document() else db.collection("employees").document(input.id)
        val counter = db.collection("system").document("employeeCounter")
        // Bao gom ca nhan vien da nghi va ma nhap thu cong cua ban cu.
        val codes = if (input.id.isBlank()) db.collection("employees").get(Source.SERVER).await().documents.mapNotNull { it.getString("code") } else emptyList()
        val requestId = UUID.randomUUID().toString()
        return db.runTransaction { tx ->
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
    }

    suspend fun removeEmployeeOrFingerprint(employeeId: String, retire: Boolean) {
        val employeeRef = db.collection("employees").document(employeeId)
        val requestId = UUID.randomUUID().toString()
        db.runTransaction { tx ->
            val e = tx.get(employeeRef).toObject(Employee::class.java) ?: error("Không tìm thấy nhân viên")
            val slot = e.fingerprintTemplateId ?: e.pendingTemplateId
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
    }
    suspend fun savePayroll(employeeId: String, month: String, bonus: Long, deduction: Long) {
        require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)) { "Tháng phải có dạng yyyy-MM" }
        val employeeRef = db.collection("employees").document(employeeId)
        val ref = db.collection("payroll").document("${employeeId}_$month")
        db.runTransaction { tx ->
            val e = tx.get(employeeRef).toObject(Employee::class.java)?.copy(id=employeeId) ?: error("Không tìm thấy nhân viên")
            val previous = tx.get(ref)
            require(!previous.exists()) { "Đã lưu phiếu lương tháng này. Phiếu đã lưu được giữ nguyên." }
            tx.set(ref, createPayroll(e, month, bonus, deduction))
        }.await()
    }
}
