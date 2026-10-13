package vn.chamcong.iot.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CameraPreviewScreen(onOpenEmployees: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("camera-preview", android.content.Context.MODE_PRIVATE) }
    var address by remember { mutableStateOf(prefs.getString("address", "").orEmpty()) }
    var fingerprint by remember { mutableStateOf(prefs.getString("fingerprint", "").orEmpty()) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var running by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var sensor by remember { mutableStateOf("") }
    var rotation by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var activeClient by remember { mutableStateOf<CameraPreviewClient?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var generation by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun stop() { generation++; stopping = job?.isCompleted == false; activeClient?.cancel(); job?.cancel(); running = false; loading = false; bitmap = null }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    fun start() {
        if (stopping || job?.isCompleted == false) return
        previewError = null
        val config = try { cameraPreviewConnection(address, fingerprint) } catch (failure: Exception) { previewError = failure.localizedMessage; return }
        prefs.edit().putString("address", config.baseUrl).putString("fingerprint", config.fingerprint).apply()
        loading = true; running = true
        val ticket = ++generation
        val client = CameraPreviewClient(config)
        activeClient = client
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                suspend fun token(): String = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: error("Đăng nhập lại tài khoản Admin để xem camera.")
                val health = JSONObject(String(client.request("health", token()), Charsets.UTF_8))
                check(health.optBoolean("cameraReady") && health.optBoolean("wifiConnected")) { "Camera chưa sẵn sàng. Kiểm tra nguồn và Wi-Fi." }
                sensor = health.optString("sensor").take(100)
                while (isActive) {
                    val bytes = client.request("frame", token())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000) { "Kích thước ảnh camera không hợp lệ." }
                    if(ticket != generation) return@launch
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Chưa đọc được ảnh camera.")
                    loading = false
                    delay(500)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if(ticket == generation) { previewError = when(failure) {
                is javax.net.ssl.SSLException -> "Mã xác minh laptop chưa khớp hoặc đã đổi. Kiểm tra mã trong cửa sổ máy chủ trên laptop."
                is java.net.ConnectException, is java.net.SocketTimeoutException -> "Chưa kết nối được laptop. Kiểm tra địa chỉ và bảo đảm điện thoại cùng mạng Wi-Fi."
                else -> failure.localizedMessage ?: "Chưa kết nối được laptop. Kiểm tra địa chỉ, mã xác minh và Wi-Fi."
            }; bitmap = null } }
            finally { if(ticket == generation) { running = false; loading = false }; if(activeClient === client) { activeClient = null; job = null; stopping = false } }
        }
        job = operation
        operation.start()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.large)) {
        AdminPageHeader("Camera & khuôn mặt", "Kiểm tra góc đặt và ánh sáng trước khi đăng ký khuôn mặt.")
        AdminSectionCard {
            Text("Kết nối laptop", style = MaterialTheme.typography.titleMedium)
            Text("Mở mo-web-admin-camera-app.cmd trên laptop, rồi nhập địa chỉ và mã xác minh hiển thị trong cửa sổ đó.")
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Địa chỉ HTTPS của laptop") }, placeholder = { Text("https://192.168.1.10:4174") }, singleLine = true, enabled = !running && !stopping)
            OutlinedTextField(fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(), label = { Text("Mã xác minh laptop (SHA-256)") }, minLines = 2, maxLines = 4, enabled = !running && !stopping)
        }
        AdminSectionCard {
            Text("Camera tại trạm", style = MaterialTheme.typography.titleMedium)
            Text("Ảnh mới được cập nhật liên tục khi đang xem.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Button(::start, enabled = !running && !stopping && address.isNotBlank() && fingerprint.isNotBlank(), shape = MaterialTheme.shapes.small) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(AppSpacing.small)); Text(if(stopping) "Đang dừng…" else if (previewError == null) "Bắt đầu xem" else "Thử lại") }
                OutlinedButton(::stop, enabled = running, shape = MaterialTheme.shapes.small) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(AppSpacing.small)); Text("Dừng xem") }
                OutlinedButton({ rotation = (rotation + 90) % 360 }, shape = MaterialTheme.shapes.small) { Icon(Icons.Default.RotateRight, null); Spacer(Modifier.width(AppSpacing.small)); Text("Xoay 90°") }
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val aspect = bitmap?.let { (it.width.toFloat() / it.height).coerceIn(0.25f, 4f) } ?: (4f / 3f)
            Box(Modifier.fillMaxWidth().aspectRatio(if(rotation % 180 == 0) aspect else 1f / aspect).clipToBounds(), contentAlignment = Alignment.Center) {
                val frame = bitmap
                if (frame != null) Image(frame.asImageBitmap(), contentDescription = "Ảnh camera trực tiếp", modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.toFloat(); val scale = if(rotation % 180 == 0) 1f else maxOf(aspect, 1f / aspect); scaleX = scale; scaleY = scale }, contentScale = ContentScale.Fit)
                else if (loading || stopping) CircularProgressIndicator()
                else Text("Bấm Bắt đầu xem để nhận ảnh mới", modifier = Modifier.padding(AppSpacing.large))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if(stopping) "Đang dừng…" else if (running) "Đang xem${if (sensor.isNotBlank()) " · $sensor" else ""}" else "Đã dừng", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${rotation}°", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AdminSectionCard {
            Text("Đặt camera trước khi lấy mẫu", style = MaterialTheme.typography.titleMedium)
            Text("1. Đặt camera ngang tầm mặt, đủ sáng.")
            Text("2. Chỉ để một người trong khung hình.")
            Text("3. Giữ toàn bộ mặt trong ảnh, tránh bị cắt hoặc che.")
            Text("Đăng ký khuôn mặt cho nhân viên", style = MaterialTheme.typography.titleSmall)
            Text("Vào Nhân viên, chọn Sửa hồ sơ → Khuôn mặt. Laptop cần chạy dịch vụ đăng ký.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onOpenEmployees?.let { openEmployees -> OutlinedButton(onClick = openEmployees, shape = MaterialTheme.shapes.small) { Text("Mở danh sách nhân viên") } }
        }
        Text("Chuyển trang hoặc đưa app vào nền sẽ dừng lấy ảnh. Xem ảnh không đăng ký mẫu và không tạo lượt chấm công.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
