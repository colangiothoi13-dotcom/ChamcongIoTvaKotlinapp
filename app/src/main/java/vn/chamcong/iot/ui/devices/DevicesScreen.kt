package vn.chamcong.iot.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.commandStatusLabel
import vn.chamcong.iot.model.isOnline
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

private val Brand = Color(0xFF147D64)

@Composable
fun DevicesScreen(state: MainUiState, vm: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Quản lý thiết bị", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Theo dõi heartbeat và trạng thái lệnh vân tay", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Devices, null, tint = Color(0xFF6D7470), modifier = Modifier.size(40.dp))
                        Spacer(Modifier.size(8.dp))
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
            item { Text("Chưa có lệnh đăng ký hoặc xóa vân tay", color = Color(0xFF6D7470)) }
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
    val statusColor = if (online) Brand else if (device.status.equals("OFFLINE", ignoreCase = true)) Color(0xFFB3261E) else Color(0xFF6D7470)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (online) Icons.Default.CheckCircle else Icons.Default.BluetoothDisabled, null, tint = statusColor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name.ifBlank { device.id }, fontWeight = FontWeight.Bold)
                    Text(device.location.ifBlank { "Chưa cập nhật vị trí" }, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (online) "Online" else if (device.status.equals("OFFLINE", true)) "Offline" else "Chưa rõ", color = statusColor)
                IconButton({ editing = true }, enabled = !saving) { Icon(Icons.Default.Edit, "Sửa cấu hình") }
            }
            Text("Mã: ${device.id}")
            Text("Firmware: ${device.firmwareVersion.ifBlank { "Chưa có dữ liệu" }}")
            Text("Vân tay: ${device.fingerprintCount?.toString() ?: "?"}/${device.capacity?.toString() ?: "?"}")
            device.lastHeartbeat?.let {
                Text("Heartbeat: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(it.toDate())}", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Chưa nhận heartbeat", style = MaterialTheme.typography.bodySmall)
            val capabilities = device.capabilities
            Text(
                if (capabilities.isEmpty()) "Firmware hiện tại chưa công bố capability test LED/còi"
                else "Capability: ${capabilities.sorted().joinToString()}",
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.Devices, null, tint = if (failed) Color(0xFFB3261E) else Brand)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (command["type"] == "DELETE_FINGERPRINT") "Xóa vân tay" else "Đăng ký vân tay", fontWeight = FontWeight.Bold)
                Text("Nhân viên: ${command["employeeName"] ?: command["employeeId"] ?: "Không rõ"}")
                Text(commandStatusLabel(command), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
