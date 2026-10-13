package vn.chamcong.iot.ui.camera

import java.net.URI

internal data class CameraPreviewConnection(val baseUrl: String, val fingerprint: String)

internal fun cameraPreviewConnection(address: String, fingerprint: String): CameraPreviewConnection {
    val uri = runCatching { URI(address.trim()) }.getOrElse { error("Địa chỉ laptop không hợp lệ.") }
    val host = uri.host.orEmpty()
    val segments = host.split('.')
    val parts = if(segments.size == 4 && segments.all { it.matches(Regex("(?:0|[1-9]\\d{0,2})")) }) segments.map(String::toInt) else emptyList()
    val privateIp = parts.size == 4 && parts.all { it in 0..255 } &&
        (parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31 || host == "127.0.0.1")
    require(uri.scheme == "https" && privateIp && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")) {
        "Dùng địa chỉ HTTPS trong mạng nội bộ hiển thị trên laptop, ví dụ https://192.168.1.10:4174."
    }
    val port = if (uri.port == -1) 4174 else uri.port
    require(port in 1..65535) { "Cổng laptop không hợp lệ." }
    val pin = fingerprint.trim().replace(":", "").replace(Regex("\\s"), "").uppercase()
    require(pin.matches(Regex("[A-F0-9]{64}"))) { "Nhập đủ mã SHA-256 hiển thị trong cửa sổ máy chủ trên laptop." }
    return CameraPreviewConnection("https://$host:$port", pin)
}
