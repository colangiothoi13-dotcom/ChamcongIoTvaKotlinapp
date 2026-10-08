package vn.chamcong.iot.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.employeeResourceAudiences
import vn.chamcong.iot.domain.validateEmployeeResource
import vn.chamcong.iot.model.EmployeeResource

/** Employees use their exact audiences so Firestore can enforce read scope. */
fun FirebaseRepository.observeEmployeeResources(
    employeeId: String? = null,
    departmentId: String = ""
): Flow<List<EmployeeResource>> = callbackFlow {
    var query: Query = db.collection("employeeResources")
    if (employeeId != null) query = query.whereIn("audience", employeeResourceAudiences(employeeId, departmentId))
    val listener = query.orderBy("updatedAt", Query.Direction.DESCENDING).limit(200)
        .addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else {
                runCatching {
                    snapshot?.documents.orEmpty().mapNotNull { document ->
                        document.toObject(EmployeeResource::class.java)?.copy(id = document.id)
                    }
                }.onSuccess { trySend(it) }.onFailure { close(it) }
            }
        }
    awaitClose { listener.remove() }
}

suspend fun FirebaseRepository.saveEmployeeResource(resource: EmployeeResource): String {
    val clean = resource.copy(
        id = resource.id.trim(), type = resource.type.trim(), title = resource.title.trim(), body = resource.body.trim(),
        audience = resource.audience.trim(), eventDate = resource.eventDate.trim(), startTime = resource.startTime.trim(),
        endTime = resource.endTime.trim(), location = resource.location.trim(), url = resource.url.trim()
    )
    validateEmployeeResource(clean)
    val ref = if (clean.id.isBlank()) db.collection("employeeResources").document()
        else db.collection("employeeResources").document(clean.id)
    db.runTransaction { transaction ->
        val previous = transaction.get(ref)
        if (clean.id.isNotBlank()) require(previous.exists()) { "Nội dung đã bị xóa, hãy tải lại danh sách" }
        val data = mutableMapOf<String, Any?>(
            "type" to clean.type, "title" to clean.title, "body" to clean.body, "audience" to clean.audience,
            "eventDate" to clean.eventDate, "startTime" to clean.startTime, "endTime" to clean.endTime,
            "location" to clean.location, "url" to clean.url, "updatedAt" to FieldValue.serverTimestamp()
        )
        if (previous.exists()) {
            data["createdAt"] = previous.getTimestamp("createdAt")
            data["createdBy"] = previous.getString("createdBy")
        } else {
            data["createdAt"] = FieldValue.serverTimestamp()
            data["createdBy"] = currentUserId
        }
        transaction.set(ref, data)
    }.await()
    return ref.id
}

suspend fun FirebaseRepository.deleteEmployeeResource(resourceId: String) {
    require(resourceId.isNotBlank() && '/' !in resourceId) { "Mã nội dung không hợp lệ" }
    db.collection("employeeResources").document(resourceId).delete().await()
}
