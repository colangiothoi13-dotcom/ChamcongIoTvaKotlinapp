package vn.chamcong.iot.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.channels.ProducerScope
import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.UserProfile

// Firestore callbacks run outside the collecting coroutine. Close the flow on
// decoding errors so the ViewModel's Flow.catch can handle them.
internal fun <T> ProducerScope<T>.sendSnapshot(error: Exception?, decode: () -> T) {
    if (error != null) {
        close(error)
        return
    }
    try {
        trySend(decode())
    } catch (error: Exception) {
        close(error)
    }
}

internal fun DocumentSnapshot.appNotification(): AppNotification = appNotificationFromFields(
    id,
    getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE).orEmpty()
)

internal fun appNotificationFromFields(id: String, fields: Map<String, Any?>): AppNotification = AppNotification(
    id = id,
    type = fields["type"] as? String ?: "",
    title = fields["title"] as? String ?: "",
    body = fields["body"] as? String ?: "",
    referenceId = fields["referenceId"] as? String,
    recipientEmployeeId = fields["recipientEmployeeId"] as? String,
    audienceLabel = fields["audienceLabel"] as? String,
    // ESTIMATE supplies the pending local timestamp. Also tolerate old documents
    // with a missing/null timestamp without violating the Kotlin model contract.
    createdAt = fields["createdAt"] as? Timestamp ?: Timestamp.now(),
    read = fields["read"] as? Boolean ?: false
)

internal fun DocumentSnapshot.leaveRequest(): LeaveRequest? =
    toObject(LeaveRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.copy(
        id = id,
        createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: Timestamp.now()
    )

internal fun DocumentSnapshot.userProfile(): UserProfile? =
    toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.copy(
        uid = id,
        updatedAt = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: Timestamp.now()
    )
