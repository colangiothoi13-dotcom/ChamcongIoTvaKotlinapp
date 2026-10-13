package vn.chamcong.iot.ui.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class CameraPreviewClient(private val config: CameraPreviewConnection) {
    private val activeConnection = AtomicReference<HttpsURLConnection?>()
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; activeConnection.getAndSet(null)?.disconnect() }
    private val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client certificate is not supported")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Thiếu chứng chỉ laptop.")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                val expected = config.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("Mã xác minh laptop chưa khớp. Kiểm tra mã trong cửa sổ máy chủ.")
            }
        }), null)
    }

    suspend fun request(endpoint: String, idToken: String): ByteArray = withContext(Dispatchers.IO) {
        require(endpoint in listOf("health", "frame"))
        currentCoroutineContext().ensureActive()
        val url = URL("${config.baseUrl}/api/camera/$endpoint")
        val connection = url.openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        // The certificate pin is the endpoint identity, paired on the laptop.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, _ -> hostname == url.host }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer $idToken")
        connection.setRequestProperty("X-Camera-Preview", "1")
        connection.setRequestProperty("Accept", if (endpoint == "frame") "image/jpeg" else "application/json")
        activeConnection.set(connection)
        try {
            if(cancelled) throw kotlinx.coroutines.CancellationException("Camera preview stopped")
            currentCoroutineContext().ensureActive()
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val limit = if (status == 200 && endpoint == "frame") 5 * 1024 * 1024 else 16 * 1024
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Ảnh hoặc trạng thái camera quá lớn." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            currentCoroutineContext().ensureActive()
            if (status != 200) {
                val message = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("message") }.getOrDefault("")
                error(message.ifBlank { if (status in 300..399) "Địa chỉ laptop chuyển hướng; hãy kiểm tra lại." else "Chưa kết nối được camera. Kiểm tra laptop và Wi-Fi rồi thử lại." })
            }
            require(connection.contentType?.substringBefore(';') == if (endpoint == "frame") "image/jpeg" else "application/json") { "Laptop chưa trả về dữ liệu camera hợp lệ." }
            if (endpoint == "frame") require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "Ảnh camera không hợp lệ." }
            bytes
        } finally { connection.disconnect(); activeConnection.compareAndSet(connection, null) }
    }
}
