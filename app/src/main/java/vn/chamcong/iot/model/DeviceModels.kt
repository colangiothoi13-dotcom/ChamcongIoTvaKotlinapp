package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import java.time.Duration
import java.time.Instant

enum class DeviceCommandType {
    TEST_LED_GREEN,
    TEST_LED_RED,
    TEST_BUZZER,
    SYNC_ATTENDANCE,
    RESTART_DEVICE
}

data class DeviceSnapshot(
    val id: String = "",
    val name: String = "",
    val location: String = "",
    val status: String = "UNKNOWN",
    val lastHeartbeat: Timestamp? = null,
    val firmwareVersion: String = "",
    val fingerprintCount: Int? = null,
    val capacity: Int? = null,
    val pendingAttendanceCount: Int = 0,
    val capabilities: Set<String> = emptySet(),
    val wifiStatus: String = "UNKNOWN",
    val firebaseSyncStatus: String = "UNKNOWN",
    val sensorStatus: String = "UNKNOWN",
    val failedScanCount: Int = 0,
    val lastError: String = ""
)

/**
 * Converts Firestore's untyped device fields without assuming that old or
 * manually-created documents are perfectly shaped.
 */
fun deviceSnapshotFromFields(id: String, fields: Map<String, Any?>): DeviceSnapshot = DeviceSnapshot(
    id = id,
    name = fields["name"] as? String ?: "",
    location = fields["location"] as? String ?: "",
    status = fields["status"] as? String ?: "UNKNOWN",
    lastHeartbeat = fields["lastHeartbeat"] as? Timestamp,
    firmwareVersion = fields["firmwareVersion"] as? String ?: "",
    fingerprintCount = (fields["fingerprintCount"] as? Number)?.toInt(),
    capacity = (fields["capacity"] as? Number)?.toInt(),
    pendingAttendanceCount = ((fields["pendingAttendanceCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
    capabilities = (fields["capabilities"] as? List<*>)
        .orEmpty()
        .filterIsInstance<String>()
        .toSet(),
    wifiStatus = (fields["wifiStatus"] as? String)?.uppercase() ?: "UNKNOWN",
    firebaseSyncStatus = (fields["firebaseSyncStatus"] as? String)?.uppercase() ?: "UNKNOWN",
    sensorStatus = (fields["sensorStatus"] as? String)?.uppercase() ?: "UNKNOWN",
    failedScanCount = ((fields["failedScanCount"] as? Number)?.toInt() ?: 0).coerceIn(0, 100000),
    lastError = (fields["lastError"] as? String)?.take(240).orEmpty()
)

fun DeviceSnapshot.isOnline(
    now: Instant,
    timeout: Duration = Duration.ofMinutes(2)
): Boolean {
    val heartbeat = lastHeartbeat?.toDate()?.toInstant() ?: return false
    val age = Duration.between(heartbeat, now)
    return !status.equals("OFFLINE", ignoreCase = true)
        && !wifiStatus.equals("OFFLINE", ignoreCase = true)
        && !age.isNegative
        && age <= timeout
}

fun commandStatusLabel(command: Map<String, Any>): String {
    val type = command["type"] as? String
    val isFingerprintCommand = type == "ENROLL_FINGERPRINT" || type == "DELETE_FINGERPRINT"
    val typeLabel = when (type) {
        DeviceCommandType.TEST_LED_GREEN.name -> "Kiểm tra LED xanh"
        DeviceCommandType.TEST_LED_RED.name -> "Kiểm tra LED đỏ"
        DeviceCommandType.TEST_BUZZER.name -> "Kiểm tra còi"
        DeviceCommandType.SYNC_ATTENDANCE.name -> "Đồng bộ chấm công"
        DeviceCommandType.RESTART_DEVICE.name -> "Khởi động lại thiết bị"
        "DELETE_FINGERPRINT" -> "Xóa vân tay"
        "ENROLL_FINGERPRINT" -> "Đăng ký vân tay"
        else -> "Lệnh thiết bị"
    }
    return when (command["status"] as? String) {
        "REQUESTED" -> if (isFingerprintCommand) "Đang chờ thiết bị" else "$typeLabel · Đang chờ thiết bị"
        "PROCESSING" -> if (isFingerprintCommand) "Thiết bị đang chờ thao tác" else "$typeLabel · Thiết bị đang xử lý"
        "COMPLETED" -> if (isFingerprintCommand) {
            if (command["applied"] == true) "Hoàn tất" else "Đã xong, đang đồng bộ"
        } else {
            "$typeLabel · Hoàn tất"
        }
        "FAILED" -> {
            val message = (command["message"] as? String)?.takeIf(String::isNotBlank)
            val fallback = when (type) {
                "DELETE_FINGERPRINT" -> "Xóa vân tay thất bại; vị trí được giữ lại"
                "ENROLL_FINGERPRINT" -> "Đăng ký vân tay thất bại; kiểm tra thiết bị"
                else -> "Lệnh thiết bị thất bại"
            }
            if (message == null && isFingerprintCommand) fallback else "$typeLabel · ${message ?: fallback}"
        }
        else -> "$typeLabel · Trạng thái chưa xác định"
    }
}

fun deviceCommandLabel(type: DeviceCommandType): String = when (type) {
    DeviceCommandType.TEST_LED_GREEN -> "Kiểm tra LED xanh"
    DeviceCommandType.TEST_LED_RED -> "Kiểm tra LED đỏ"
    DeviceCommandType.TEST_BUZZER -> "Kiểm tra còi"
    DeviceCommandType.SYNC_ATTENDANCE -> "Đồng bộ chấm công"
    DeviceCommandType.RESTART_DEVICE -> "Khởi động lại thiết bị"
}
