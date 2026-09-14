package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import java.time.Duration
import java.time.Instant

data class DeviceSnapshot(
    val id: String = "",
    val name: String = "",
    val location: String = "",
    val status: String = "UNKNOWN",
    val lastHeartbeat: Timestamp? = null,
    val firmwareVersion: String = "",
    val fingerprintCount: Int? = null,
    val capacity: Int? = null,
    val capabilities: Set<String> = emptySet()
)

fun DeviceSnapshot.isOnline(
    now: Instant,
    timeout: Duration = Duration.ofMinutes(2)
): Boolean {
    val heartbeat = lastHeartbeat?.toDate()?.toInstant() ?: return false
    val age = Duration.between(heartbeat, now)
    return !status.equals("OFFLINE", ignoreCase = true) && !age.isNegative && age <= timeout
}

fun commandStatusLabel(command: Map<String, Any>): String {
    val type = command["type"] as? String
    return when (command["status"] as? String) {
        "REQUESTED" -> "Đang chờ thiết bị"
        "PROCESSING" -> "Thiết bị đang chờ thao tác"
        "COMPLETED" -> if (command["applied"] == true) "Hoàn tất" else "Đã xong, đang đồng bộ"
        "FAILED" -> if (type == "DELETE_FINGERPRINT") {
            "Xóa vân tay thất bại; vị trí được giữ lại"
        } else {
            "Đăng ký vân tay thất bại; kiểm tra thiết bị"
        }
        else -> "Trạng thái chưa xác định"
    }
}
