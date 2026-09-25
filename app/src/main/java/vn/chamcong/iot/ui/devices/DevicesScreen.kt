package vn.chamcong.iot.ui.devices

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.DeviceCommandType
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.commandStatusLabel
import vn.chamcong.iot.model.deviceCommandLabel
import vn.chamcong.iot.model.isOnline
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.deviceStatusLabel
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

@Composable
fun DevicesScreen(state: MainUiState, vm: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text("Quản lý thiết bị", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Theo dõi sức khỏe, đồng bộ và điều khiển từ xa", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.xLarge), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(AppSpacing.small))
                        Text("Chưa có thiết bị trên Firebase")
                        Text("Thiết bị sẽ xuất hiện khi firmware ghi snapshot vào devices/{deviceId}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(state.devices, key = { it.id }) { device -> DeviceCard(device, state, vm) }
        }
        item {
            Text("Lệnh gần đây", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (state.commands.isEmpty()) {
            item { Text("Chưa có lệnh thiết bị", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.commands, key = { it["commandId"]?.toString().orEmpty() }) { command -> CommandCard(command) }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceSnapshot, state: MainUiState, vm: MainViewModel) {
    var editing by remember(device.id) { mutableStateOf(false) }
    var restartConfirm by remember(device.id) { mutableStateOf(false) }
    val online = device.isOnline(Instant.now())
    val deviceCommands = state.commands.filter { it["deviceId"]?.toString() == device.id }
    val pending = deviceCommands.any {
        it["status"] in listOf("REQUESTED", "PROCESSING") ||
            (it["status"] == "COMPLETED" && it["applied"] != true)
    }
    val canAct = online && !pending && !state.saving
    val canControlDoor = canAct && device.capabilities.contains("door")
    val latestAttendance = state.attendanceForSummaries
        .filter { it.deviceId == device.id }
        .maxByOrNull { it.timestamp.toDate().time }
    val statusColor = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (online) Icons.Default.CheckCircle else Icons.Default.BluetoothDisabled, null, tint = statusColor)
                Spacer(Modifier.width(AppSpacing.small))
                Column(Modifier.weight(1f)) {
                    Text(device.name.ifBlank { device.id }, fontWeight = FontWeight.Bold)
                    Text(device.location.ifBlank { "Chưa cập nhật vị trí" }, style = MaterialTheme.typography.bodySmall)
                }
                Text(deviceStatusLabel(if (online) "ONLINE" else device.status), color = statusColor)
                IconButton({ editing = true }, enabled = !state.saving) { Icon(Icons.Default.Edit, "Sửa cấu hình") }
            }
            Text("Mã: ${device.id}")
            Text("Phiên bản: ${device.firmwareVersion.ifBlank { "Chưa có dữ liệu" }}")
            Text("Vân tay: ${device.fingerprintCount?.toString() ?: "?"}/${device.capacity?.toString() ?: "?"}")
            Text("Wi-Fi: ${device.wifiStatus} · Firebase: ${device.firebaseSyncStatus} · Cảm biến: ${device.sensorStatus}")
            Text("Cửa: ${device.doorStatus}")
            Text("Quét lỗi trong 5 phút: ${device.failedScanCount}")
            if (device.lastError.isNotBlank()) Text("Lỗi gần nhất: ${device.lastError}", color = MaterialTheme.colorScheme.error)
            if (device.pendingAttendanceCount > 0) {
                Text("Lượt chấm chờ đồng bộ: ${device.pendingAttendanceCount}", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Không có lượt chấm chờ đồng bộ", style = MaterialTheme.typography.bodySmall)
            }
            device.lastHeartbeat?.let {
                Text("Tín hiệu cuối: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(it.toDate())}", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Chưa nhận tín hiệu", style = MaterialTheme.typography.bodySmall)
            latestAttendance?.let { LatestAttendance(it) } ?: Text("Chưa có lượt chấm từ thiết bị này", style = MaterialTheme.typography.bodySmall)

            Text("Điều khiển thiết bị", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                DeviceActionButton(DeviceCommandType.TEST_LED_GREEN, canAct, vm, device.id)
                DeviceActionButton(DeviceCommandType.TEST_LED_RED, canAct, vm, device.id)
                DeviceActionButton(DeviceCommandType.TEST_BUZZER, canAct, vm, device.id)
                DeviceActionButton(DeviceCommandType.OPEN_DOOR, canControlDoor, vm, device.id)
                DeviceActionButton(DeviceCommandType.CLOSE_DOOR, canControlDoor, vm, device.id)
                DeviceActionButton(DeviceCommandType.SYNC_ATTENDANCE, canAct, vm, device.id)
                OutlinedButton(onClick = { restartConfirm = true }, enabled = canAct) {
                    Icon(Icons.Default.RestartAlt, null)
                    Spacer(Modifier.width(AppSpacing.xSmall))
                    Text("Khởi động lại")
                }
            }
            if (pending) Text("Thiết bị đang xử lý một lệnh khác", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            deviceCommands.sortedByDescending { it["createdAt"]?.toString() }.take(2).forEach { command ->
                Text(commandStatusLabel(command), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (editing) DeviceConfigDialog(device, state.saving, onDismiss = { editing = false }) { name, location ->
        vm.updateDeviceConfiguration(device.id, name, location) { editing = false }
    }
    if (restartConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) restartConfirm = false },
            title = { Text("Khởi động lại thiết bị?") },
            text = { Text("Thiết bị sẽ hoàn tất việc ghi nhận lệnh rồi khởi động lại. Hãy xác nhận khi không có người đang quét vân tay.") },
            confirmButton = {
                Button(onClick = {
                    vm.requestDeviceCommand(device.id, DeviceCommandType.RESTART_DEVICE) { restartConfirm = false }
                }, enabled = !state.saving) { Text("Khởi động lại") }
            },
            dismissButton = { TextButton(onClick = { restartConfirm = false }, enabled = !state.saving) { Text("Hủy") } }
        )
    }
}

@Composable
private fun DeviceActionButton(type: DeviceCommandType, enabled: Boolean, vm: MainViewModel, deviceId: String) {
    OutlinedButton(onClick = { vm.requestDeviceCommand(deviceId, type) }, enabled = enabled) {
        val icon = when (type) {
            DeviceCommandType.TEST_LED_GREEN, DeviceCommandType.TEST_LED_RED -> Icons.Default.Lightbulb
            DeviceCommandType.TEST_BUZZER -> Icons.Default.VolumeUp
            DeviceCommandType.OPEN_DOOR -> Icons.Default.LockOpen
            DeviceCommandType.CLOSE_DOOR -> Icons.Default.Lock
            DeviceCommandType.SYNC_ATTENDANCE -> Icons.Default.Sync
            DeviceCommandType.RESTART_DEVICE -> Icons.Default.RestartAlt
        }
        Icon(icon, null)
        Spacer(Modifier.width(AppSpacing.xSmall))
        Text(deviceCommandLabel(type))
    }
}

@Composable
private fun LatestAttendance(item: Attendance) {
    val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(item.timestamp.toDate())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.xSmall))
        Text("Lượt gần nhất: ${item.employeeName.ifBlank { item.employeeId }} · $time", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceConfigDialog(device: DeviceSnapshot, saving: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var location by remember(device.id) { mutableStateOf(device.location) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Cấu hình thiết bị") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedTextField(name, { name = it }, label = { Text("Tên thiết bị") }, singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Vị trí") }, singleLine = true)
            }
        },
        confirmButton = { Button({ onSave(name, location) }, enabled = !saving && name.isNotBlank()) { Text("Lưu") } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("Hủy") } }
    )
}

@Composable
private fun CommandCard(command: Map<String, Any>) {
    val failed = command["status"] == "FAILED"
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.Devices, null, tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(AppSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(commandTypeLabel(command["type"]?.toString()), fontWeight = FontWeight.Bold)
                val employee = command["employeeName"]?.toString()?.takeIf(String::isNotBlank)
                    ?: command["employeeId"]?.toString()?.takeIf(String::isNotBlank)
                employee?.let { Text("Nhân viên: $it") }
                Text("Thiết bị: ${command["deviceId"] ?: command["commandId"] ?: "Không rõ"}")
                Text(commandStatusLabel(command), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun commandTypeLabel(type: String?): String = when (type) {
    DeviceCommandType.TEST_LED_GREEN.name -> "Kiểm tra LED xanh"
    DeviceCommandType.TEST_LED_RED.name -> "Kiểm tra LED đỏ"
    DeviceCommandType.TEST_BUZZER.name -> "Kiểm tra còi"
    DeviceCommandType.OPEN_DOOR.name -> "Mở cửa"
    DeviceCommandType.CLOSE_DOOR.name -> "Đóng cửa"
    DeviceCommandType.SYNC_ATTENDANCE.name -> "Đồng bộ chấm công"
    DeviceCommandType.RESTART_DEVICE.name -> "Khởi động lại thiết bị"
    "DELETE_FINGERPRINT" -> "Xóa vân tay"
    "ENROLL_FINGERPRINT" -> "Đăng ký vân tay"
    else -> "Lệnh thiết bị"
}
