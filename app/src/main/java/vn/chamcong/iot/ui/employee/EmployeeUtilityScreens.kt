package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.EmployeeDestination
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.requestStatusLabel
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val utilityZone = ZoneId.of("Asia/Ho_Chi_Minh")
private val utilityDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("vi", "VN"))
private val utilityTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("vi", "VN"))

@Composable
fun EmployeeUtilitiesScreen(onOpen: (EmployeeDestination) -> Unit) {
    val entries = listOf(
        Triple(EmployeeDestination.SHIFT_REGISTRATION, "Chọn ca tuần sau và gửi Admin duyệt", Icons.Default.CalendarMonth),
        Triple(EmployeeDestination.REQUESTS, "Gửi đơn và theo dõi kết quả duyệt", Icons.Default.Description),
        Triple(EmployeeDestination.MEETINGS, "Thời gian, địa điểm và nội dung cuộc họp", Icons.Default.Groups),
        Triple(EmployeeDestination.PROFILE, "Xem hồ sơ và cập nhật thông tin liên hệ", Icons.Default.Person),
        Triple(EmployeeDestination.TENURE, "Ngày vào làm và thời gian gắn bó", Icons.Default.Work),
        Triple(EmployeeDestination.NEWS, "Thông tin mới từ Admin", Icons.Default.Event),
        Triple(EmployeeDestination.REWARDS, "Các ghi nhận và khen thưởng của bạn", Icons.Default.Star),
        Triple(EmployeeDestination.DOCUMENTS, "Mở tài liệu được chia sẻ cho bạn", Icons.Default.Folder),
        Triple(EmployeeDestination.SUPPORT, "Gửi yêu cầu hỗ trợ vân tay và xem phản hồi", Icons.Default.HeadsetMic)
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        items(entries, key = { it.first.name }) { (destination, description, icon) ->
            Card(onClick = { onOpen(destination) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(AppSpacing.large), horizontalArrangement = Arrangement.spacedBy(AppSpacing.large)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(if (destination == EmployeeDestination.PROFILE) "Thông tin" else if (destination == EmployeeDestination.REQUESTS) "Đơn báo" else destination.title,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun EmployeeNewsScreen(state: MainUiState, vm: MainViewModel, announcementsOnly: Boolean) {
    var expandedId by rememberSaveable(state.currentEmployee?.id, announcementsOnly) { mutableStateOf<String?>(null) }
    val notifications = state.employeeNotifications
        .filter { !announcementsOnly || it.type == "ANNOUNCEMENT" }
        .sortedByDescending { it.createdAt.seconds }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        if (notifications.isEmpty()) item {
            Text(if (announcementsOnly) "Chưa có tin tức được gửi cho bạn." else "Chưa có thông báo.")
        }
        items(notifications, key = { it.id }) { notification ->
            val expanded = expandedId == notification.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(notification.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(notification.createdAt.toDate().toInstant().atZone(utilityZone).format(utilityTimeFormat),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!notification.read) Text("Chưa đọc", color = MaterialTheme.colorScheme.primary)
                    if (expanded) {
                        Text(notification.body)
                        notification.audienceLabel?.takeIf(String::isNotBlank)?.let { Text("Đối tượng: $it") }
                    }
                    TextButton(onClick = {
                        expandedId = if (expanded) null else notification.id
                        if (!expanded && !notification.read) vm.markEmployeeNotificationRead(notification.id)
                    }) { Text(if (expanded) "Thu gọn" else "Xem nội dung") }
                }
            }
        }
    }
}

@Composable
fun EmployeeTenureScreen(state: MainUiState) {
    val employee = state.currentEmployee
    if (employee == null) {
        Text("Chưa tải được hồ sơ cá nhân")
        return
    }
    val today = LocalDate.now(utilityZone)
    val hireDate = runCatching { LocalDate.parse(employee.hireDate) }.getOrNull()
    val terminationDate = runCatching { LocalDate.parse(employee.terminationDate) }.getOrNull()
    val end = if (!employee.active && terminationDate != null) minOf(terminationDate, today) else today
    val tenure = hireDate?.takeIf { !it.isAfter(end) }?.let { Period.between(it, end) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                    Text(employee.fullName, style = MaterialTheme.typography.titleLarge)
                    Text("Mã nhân viên: ${employee.code}")
                    Text("Phòng ban: ${employee.department.ifBlank { "Chưa cập nhật" }}")
                    Text("Chức vụ: ${employee.position.ifBlank { "Chưa cập nhật" }}")
                    Text("Ngày vào làm: ${hireDate?.format(utilityDateFormat) ?: if (employee.hireDate.isBlank()) "Chưa cập nhật" else "Ngày chưa hợp lệ"}")
                    if (!employee.active) Text("Ngày nghỉ việc: ${terminationDate?.format(utilityDateFormat) ?: "Chưa cập nhật"}")
                    Text(when {
                        tenure != null -> "Thâm niên: ${tenure.years} năm ${tenure.months} tháng ${tenure.days} ngày"
                        hireDate != null && hireDate.isAfter(today) -> "Chưa đến ngày bắt đầu làm việc"
                        else -> "Cần cập nhật ngày vào làm để tính thâm niên"
                    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Thông tin được lấy từ hồ sơ nhân viên do Admin quản lý.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hireDate == null) Text(
                        "Nhờ Admin vào Nhân viên → Sửa hồ sơ → Ngày vào làm để cập nhật.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EmployeeSupportScreen(state: MainUiState, vm: MainViewModel) {
    val employee = state.currentEmployee
    if (employee == null) {
        Text("Chưa tải được hồ sơ cá nhân")
        return
    }
    var reason by rememberSaveable(employee.id) { mutableStateOf("") }
    val history = state.employeeRequests.filter { it.type == RequestType.FINGERPRINT_SUPPORT.name }
        .sortedByDescending { it.createdAt.seconds }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                    Text("Hỗ trợ vân tay", style = MaterialTheme.typography.titleLarge)
                    Text("Mô tả lỗi chấm công hoặc nhu cầu đăng ký lại vân tay. Admin sẽ tiếp nhận và phản hồi yêu cầu.")
                    Text("Mã thiết bị: ${employee.fingerprintDeviceId.ifBlank { "Chưa cập nhật" }}")
                    OutlinedTextField(reason, { reason = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nội dung cần hỗ trợ") }, minLines = 3, enabled = !state.saving,
                        isError = reason.length > 4000,
                        supportingText = { Text("${reason.length}/4.000 ký tự") })
                    Button(onClick = {
                        vm.submitFingerprintSupportRequest(reason.trim()) { reason = "" }
                    }, enabled = !state.saving && reason.trim().isNotEmpty() && reason.length <= 4000,
                        modifier = Modifier.fillMaxWidth()) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Text("Gửi yêu cầu hỗ trợ")
                    }
                }
            }
        }
        item { Text("Yêu cầu đã gửi", style = MaterialTheme.typography.titleMedium) }
        if (history.isEmpty()) item { Text("Bạn chưa gửi yêu cầu hỗ trợ nào.") }
        items(history, key = { it.id }) { request ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(request.createdAt.toDate().toInstant().atZone(utilityZone).format(utilityTimeFormat))
                    Text(request.reason)
                    Text(requestStatusLabel(request.status), fontWeight = FontWeight.SemiBold)
                    request.reviewNote?.takeIf(String::isNotBlank)?.let { Text("Phản hồi của Admin: $it") }
                }
            }
        }
    }
}
