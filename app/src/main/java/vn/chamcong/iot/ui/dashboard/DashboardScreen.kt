package vn.chamcong.iot.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.isOnline
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.attendanceStatusLabel
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

private val Brand = Color(0xFF147D64)

@Composable
fun DashboardScreen(state: MainUiState, vm: MainViewModel, onOpenAttendance: () -> Unit) {
    val summary = state.dashboard
    val maxDailyCount = summary.weeklyAttendance.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Tổng quan tuần", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Theo dõi nhanh tình hình nhân sự và máy chấm công", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tuần ${summary.weekStart} – ${summary.weekStart.plusDays(6)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹") }
                TextButton(onClick = { vm.selectWeek(java.time.LocalDate.now()) }) { Text("Tuần này") }
                TextButton(onClick = { vm.moveWeek(1) }) { Text("›") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Nhân viên", summary.activeEmployees.toString(), Icons.Default.Groups, Modifier.weight(1f))
                DashboardMetric("Đã chấm", summary.checkedEmployees.toString(), Icons.Default.Fingerprint, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Đi trễ", summary.lateEmployees.toString(), Icons.Default.WarningAmber, Modifier.weight(1f), Color(0xFFB66A00))
                DashboardMetric("Chưa chấm", summary.unmarkedEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), Color(0xFF6D7470))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Lượt chấm trong tuần", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().height(140.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        summary.weeklyAttendance.forEach { day ->
                            val barHeight = (day.count.toFloat() / maxDailyCount * 96f).coerceAtLeast(8f)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Text(day.count.toString(), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier.width(22.dp).height(barHeight.dp).background(Brand, RoundedCornerShape(10.dp))
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("${day.date.dayOfMonth}/${day.date.monthValue}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, null, tint = Brand)
                        Spacer(Modifier.width(8.dp))
                        Text("Thiết bị", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (state.devices.isEmpty()) {
                        Text("Chưa có snapshot thiết bị trên Firebase", color = Color(0xFF6D7470))
                    } else {
                        val online = state.devices.count { it.isOnline(Instant.now()) }
                        Text("${online}/${state.devices.size} thiết bị đang online")
                    }
                    Text("Thiết bị mất kết nối hoặc thiếu tín hiệu sẽ được hiển thị là cần kiểm tra.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val pending = state.leaveRequests.count { it.status == "PENDING" }
            val missing = state.presenceRecords.count { it.status == vn.chamcong.iot.model.PresenceStatus.MISSING_CHECK_OUT }
            val abnormal = state.presenceRecords.count { it.status == vn.chamcong.iot.model.PresenceStatus.ABNORMAL }
            val failedCommands = state.commands.count { it["status"] == "FAILED" }
            val offlineDevices = state.devices.count { !it.isOnline(Instant.now()) }
            if (pending + missing + abnormal + failedCommands + offlineDevices > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cảnh báo cần xử lý", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (pending > 0) Text("$pending đơn từ đang chờ duyệt")
                        if (missing > 0) Text("$missing người chưa chấm ra")
                        if (abnormal > 0) Text("$abnormal lượt có mặt bất thường")
                        if (failedCommands > 0) Text("$failedCommands lệnh thiết bị thất bại")
                        if (offlineDevices > 0) Text("$offlineDevices thiết bị offline/chưa rõ")
                    }
                }
            }
        }
        item {
            if (state.notifications.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Thông báo trong app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        state.notifications.take(5).forEach { notification ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(notification.title, fontWeight = if (notification.read) FontWeight.Normal else FontWeight.Bold)
                                    Text(notification.body, style = MaterialTheme.typography.bodySmall)
                                }
                                if (!notification.read) TextButton(onClick = { vm.markNotificationRead(notification.id) }) { Text("Đã đọc") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chấm công mới nhất", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = onOpenAttendance) { Text("Xem tất cả") }
            }
        }
        if (state.attendance.isEmpty()) {
            item { EmptyDashboardState() }
        } else {
            items(state.attendance.take(5), key = { it.id }) { DashboardAttendanceRow(it) }
        }
        item {
            val failedCommands = state.commands.count { it["status"] == "FAILED" }
            if (failedCommands > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, tint = Color(0xFFB3261E))
                        Spacer(Modifier.width(8.dp))
                        Text("Có $failedCommands lệnh thiết bị thất bại cần kiểm tra.")
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    tint: Color = Brand
) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyDashboardState() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CheckCircle, null, tint = Brand, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Chưa có lượt chấm công hôm nay")
        }
    }
}

@Composable
private fun DashboardAttendanceRow(item: Attendance) {
    val time = remember(item.timestamp) {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(item.timestamp.toDate())
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Fingerprint, null, tint = Brand)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.employeeName.ifBlank { item.employeeId }, fontWeight = FontWeight.Bold)
                Text("$time • ${item.deviceId}", style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = {}, label = { Text(attendanceStatusLabel(item.status)) })
        }
    }
}
