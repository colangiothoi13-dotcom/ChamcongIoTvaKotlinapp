package vn.chamcong.iot.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.*
import java.time.Instant

fun FirebaseRepository.observeFaceProfiles(): Flow<List<FaceProfile>> = callbackFlow {
    val listener = db.collection("faceProfiles").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().map { faceProfileFromFields(it.id, it.data.orEmpty()) })
    }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeFaceStations(): Flow<List<FaceStation>> = callbackFlow {
    val listener = db.collection("faceStations").addSnapshotListener { value, error ->
        if (error != null) close(error)
        else trySend(value?.documents.orEmpty().map { faceStationFromFields(it.id, it.data.orEmpty()) })
    }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeFaceEnrollmentCommands(): Flow<List<FaceEnrollmentCommand>> = callbackFlow {
    val listener = db.collection("faceEnrollmentCommands")
        .orderBy("requestedAt", Query.Direction.DESCENDING).limit(100)
        .addSnapshotListener { value, error ->
            if (error != null) close(error)
            else trySend(value?.documents.orEmpty().map { faceEnrollmentCommandFromFields(it.id, it.data.orEmpty()) })
        }
    awaitClose { listener.remove() }
}

private val faceDocumentIdPattern = Regex("^[A-Za-z0-9_-]{1,128}$")
private fun validFaceDocumentId(value: String): Boolean = faceDocumentIdPattern.matches(value)

suspend fun FirebaseRepository.requestFaceEnrollment(
    employeeId: String,
    stationId: String,
    action: FaceEnrollmentAction
): String {
    require(validFaceDocumentId(employeeId) && validFaceDocumentId(stationId)) { "Chưa chọn nhân viên hoặc trạm camera" }
    val uid = currentUserId
    require(uid.isNotBlank()) { "Chưa đăng nhập" }
    val commandRef = db.collection("faceEnrollmentCommands").document()
    val employeeRef = db.collection("employees").document(employeeId)
    val stationRef = db.collection("faceStations").document(stationId)
    val lockRef = db.collection("faceEnrollmentLocks").document(employeeId)
    db.runTransaction { tx ->
        val user = tx.get(db.collection("users").document(uid))
        require(user.getString("role") == "ADMIN" && user.getBoolean("active") == true) { "Chỉ Admin đang hoạt động được quản lý mẫu khuôn mặt" }
        val employee = tx.get(employeeRef)
        require(employee.exists() && employee.getBoolean("active") == true) {
            "Nhân viên không tồn tại hoặc đã nghỉ"
        }
        val stationDocument = tx.get(stationRef)
        val station = faceStationFromFields(stationId, stationDocument.data.orEmpty())
        require(stationDocument.exists() && station.available(Instant.now())) {
            "Trạm camera chưa sẵn sàng. Bật dịch vụ laptop ở chế độ Firebase và kiểm tra camera."
        }
        val lock = tx.get(lockRef)
        val previousIds = listOf(station.pendingCommandId, lock.getString("commandId").orEmpty())
            .filter(String::isNotBlank).distinct()
        previousIds.forEach { oldId ->
            require(validFaceDocumentId(oldId)) { "Khóa yêu cầu không hợp lệ; kiểm tra dịch vụ laptop" }
            val old = tx.get(db.collection("faceEnrollmentCommands").document(oldId))
            require(old.exists() && old.getString("status") in setOf("COMPLETED", "FAILED", "CANCELLED")) {
                "Nhân viên hoặc trạm đang có yêu cầu chưa hoàn tất. Chờ hoặc hủy yêu cầu trước."
            }
        }
        tx.set(commandRef, mapOf(
            "requestId" to commandRef.id,
            "employeeId" to employeeId,
            "stationId" to stationId,
            "deviceId" to station.faceDeviceId,
            "sessionId" to station.sessionId,
            "action" to action.name,
            "status" to "PENDING",
            "requestedBy" to uid,
            "requestedAt" to FieldValue.serverTimestamp(),
            "sampleCount" to if (action == FaceEnrollmentAction.ENROLL) 10 else 0,
            "samplesCaptured" to 0,
            "message" to "",
            "cancelRequested" to false
        ))
        tx.update(stationRef, mapOf("pendingCommandId" to commandRef.id, "updatedAt" to FieldValue.serverTimestamp()))
        tx.set(lockRef, mapOf("employeeId" to employeeId, "commandId" to commandRef.id,
            "stationId" to stationId, "updatedAt" to FieldValue.serverTimestamp()))
        commandRef.id
    }.await()
    return commandRef.id
}

suspend fun FirebaseRepository.cancelFaceEnrollment(requestId: String) {
    require(validFaceDocumentId(requestId)) { "Yêu cầu không hợp lệ" }
    val ref = db.collection("faceEnrollmentCommands").document(requestId)
    db.runTransaction { tx ->
        val command = tx.get(ref)
        require(command.exists() && command.getString("status") in setOf("PENDING", "CAPTURING")) {
            "Yêu cầu đã kết thúc hoặc đang lưu mẫu; không thể hủy"
        }
        require(command.getBoolean("cancelRequested") != true) { "Đang chờ laptop xác nhận hủy" }
        tx.update(ref, mapOf("cancelRequested" to true, "cancelRequestedAt" to FieldValue.serverTimestamp()))
    }.await()
}
