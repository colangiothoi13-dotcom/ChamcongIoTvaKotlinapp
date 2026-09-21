package vn.chamcong.iot.ui.devices

import vn.chamcong.iot.ui.AppSpacing
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.commandStatusLabel
import vn.chamcong.iot.model.isOnline
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
            Text("Theo dõi tín hiệu thiết bị và trạng thái lệnh vân tay", style = MaterialTheme.typography.bodyMedium)
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
            items(state.devices, key = { it.id }) { device -> DeviceCard(device, state.saving, vm) }
        }
        item {
            Text("Lệnh gần đây", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (state.commands.isEmpty()) {
            item { Text("Chưa có lệnh đăng ký hoặc xóa vân tay", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.commands, key = { it["commandId"]?.toString().orEmpty() }) { command ->
                CommandCard(command)
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceSnapshot, saving: Boolean, vm: MainViewModel) {
    var editing by remember(device.id) { mutableStateOf(false) }
    val online = device.isOnline(Instant.now())
    val statusColor = if (online) MaterialTheme.colorScheme.primary else if (device.status.equals("OFFLINE", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
                IconButton({ editing = true }, enabled = !saving) { Icon(Icons.Default.Edit, "Sửa cấu hình") }
            }
            Text("Mã: ${device.id}")
            Text("Phiên bản phần mềm: ${device.firmwareVersion.ifBlank { "Chưa có dữ liệu" }}")
            Text("Vân tay: ${device.fingerprintCount?.toString() ?: "?"}/${device.capacity?.toString() ?: "?"}")
            if (device.pendingAttendanceCount > 0) {
                Text("Lượt chấm chờ đồng bộ: ${device.pendingAttendanceCount}", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Không có lượt chấm chờ đồng bộ", style = MaterialTheme.typography.bodySmall)
            }
            device.lastHeartbeat?.let {
                Text("Tín hiệu cuối: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(it.toDate())}", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Chưa nhận tín hiệu", style = MaterialTheme.typography.bodySmall)
            val capabilities = device.capabilities
            Text(
                if (capabilities.isEmpty()) "Phần mềm thiết bị chưa công bố khả năng kiểm tra đèn/còi"
                else "Khả năng thiết bị: ${capabilities.sorted().joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (editing) DeviceConfigDialog(device, saving, onDismiss = { editing = false }) { name, location ->
        vm.updateDeviceConfiguration(device.id, name, location) { editing = false }
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
                Text(if (command["type"] == "DELETE_FINGERPRINT") "Xóa vân tay" else "Đăng ký vân tay", fontWeight = FontWeight.Bold)
                Text("Nhân viên: ${command["employeeName"] ?: command["employeeId"] ?: "Không rõ"}")
                Text(commandStatusLabel(command), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
