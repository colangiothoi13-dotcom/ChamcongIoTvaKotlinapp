package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import java.time.Duration
import java.time.Instant

enum class FaceEnrollmentAction { ENROLL, DELETE }

data class FaceProfile(
    val employeeId: String = "",
    val enabled: Boolean = false,
    val version: Long = 0,
    val enrolledAt: Timestamp? = null
) {
    val registered: Boolean get() = enabled && version > 0
}

data class FaceStation(
    val stationId: String = "",
    val faceDeviceId: String = "",
    val sessionId: String = "",
    val mode: String = "",
    val lastSeenAt: Timestamp? = null,
    val cameraReady: Boolean = false,
    val sensor: String = "",
    val pendingCommandId: String = ""
) {
    fun available(now: Instant): Boolean {
        val seen = lastSeenAt?.toDate()?.toInstant() ?: return false
        val age = Duration.between(seen, now)
        return stationId.isNotBlank() && faceDeviceId.isNotBlank() && sessionId.isNotBlank() &&
            mode == "firebase" && cameraReady && age >= Duration.ofSeconds(-30) &&
            age <= Duration.ofSeconds(90)
    }
}

data class FaceEnrollmentCommand(
    val requestId: String = "",
    val employeeId: String = "",
    val stationId: String = "",
    val deviceId: String = "",
    val action: String = "",
    val status: String = "",
    val requestedAt: Timestamp? = null,
    val sampleCount: Int = 10,
    val samplesCaptured: Int = 0,
    val message: String = "",
    val errorCode: String = "",
    val cancelRequested: Boolean = false
) {
    val active: Boolean get() = status in setOf("PENDING", "CAPTURING", "FINALIZING")
    val canCancel: Boolean get() = !cancelRequested && status in setOf("PENDING", "CAPTURING")
    val statusLabel: String get() = when {
        active && cancelRequested -> "Đang hủy; chờ laptop xác nhận"
        status == "PENDING" -> "Đang chờ laptop"
        status == "CAPTURING" -> if (action == "DELETE") "Đang xóa mẫu" else "Đang thu ảnh: $samplesCaptured/$sampleCount"
        status == "FINALIZING" -> "Đang lưu mẫu; vui lòng chờ"
        status == "COMPLETED" -> if (action == "DELETE") "Đã xóa mẫu khuôn mặt" else "Đã đăng ký khuôn mặt"
        status == "FAILED" -> "Thất bại: ${message.ifBlank { "Kiểm tra laptop và camera rồi thử lại" }}"
        status == "CANCELLED" -> "Đã hủy yêu cầu"
        else -> "Chưa xác định trạng thái"
    }
}

fun faceProfileFromFields(id: String, fields: Map<String, Any?>) = FaceProfile(
    employeeId = fields["employeeId"] as? String ?: id,
    enabled = fields["enabled"] == true,
    version = (fields["version"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
    enrolledAt = fields["enrolledAt"] as? Timestamp
)

fun faceStationFromFields(id: String, fields: Map<String, Any?>) = FaceStation(
    stationId = fields["stationId"] as? String ?: id,
    faceDeviceId = fields["faceDeviceId"] as? String ?: "",
    sessionId = fields["sessionId"] as? String ?: "",
    mode = fields["mode"] as? String ?: "",
    lastSeenAt = fields["lastSeenAt"] as? Timestamp,
    cameraReady = fields["cameraReady"] == true,
    sensor = fields["sensor"] as? String ?: "",
    pendingCommandId = fields["pendingCommandId"] as? String ?: ""
)

fun faceEnrollmentCommandFromFields(id: String, fields: Map<String, Any?>) = FaceEnrollmentCommand(
    requestId = id,
    employeeId = fields["employeeId"] as? String ?: "",
    stationId = fields["stationId"] as? String ?: "",
    deviceId = fields["deviceId"] as? String ?: "",
    action = fields["action"] as? String ?: "",
    status = fields["status"] as? String ?: "",
    requestedAt = fields["requestedAt"] as? Timestamp,
    sampleCount = ((fields["sampleCount"] as? Number)?.toInt() ?: 10).coerceIn(0, 30),
    samplesCaptured = ((fields["samplesCaptured"] as? Number)?.toInt() ?: 0).coerceIn(0, 30),
    message = (fields["message"] as? String).orEmpty().take(300),
    errorCode = (fields["errorCode"] as? String).orEmpty(),
    cancelRequested = fields["cancelRequested"] == true
)
