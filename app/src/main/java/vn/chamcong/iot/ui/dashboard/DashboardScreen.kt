package vn.chamcong.iot.ui.dashboard

import vn.chamcong.iot.ui.AppSpacing
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

@Composable
fun DashboardScreen(state: MainUiState, vm: MainViewModel, onOpenAttendance: () -> Unit) {
    val summary = state.dashboard
    val daily = state.dailyDashboard
    val maxDailyCount = summary.weeklyAttendance.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text("Tổng quan hệ thống", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Theo dõi nhân sự và chấm công trong ngày", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Hôm nay · ${daily.date}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Đang hoạt động", daily.activeEmployees.toString(), Icons.Default.Groups, Modifier.weight(1f))
                DashboardMetric("Đã chấm vào", daily.checkedInEmployees.toString(), Icons.Default.Fingerprint, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Chưa chấm vào", daily.notCheckedInEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
                DashboardMetric("Đang có mặt", daily.presentEmployees.toString(), Icons.Default.CheckCircle, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Nghỉ phép", daily.onLeaveEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                DashboardMetric("Thiếu chấm ra", daily.missingCheckOutEmployees.toString(), Icons.Default.WarningAmber, Modifier.weight(1f), MaterialTheme.colorScheme.error)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("Nhân viên đi trễ (${daily.lateEmployees.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (daily.lateEmployees.isEmpty()) {
                        Text("Chưa có nhân viên đi trễ", style = MaterialTheme.typography.bodySmall)
                    } else {
                        daily.lateEmployees.take(10).forEach { employee ->
                            Text("• ${employee.fullName.ifBlank { employee.code }} · ${employee.department.ifBlank { "Chưa có phòng ban" }}")
                        }
                    }
                }
            }
        }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Nhân viên", summary.activeEmployees.toString(), Icons.Default.Groups, Modifier.weight(1f))
                DashboardMetric("Đã chấm", summary.checkedEmployees.toString(), Icons.Default.Fingerprint, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Đi trễ", summary.lateEmployees.toString(), Icons.Default.WarningAmber, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                DashboardMetric("Chưa chấm", summary.unmarkedEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
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
                                Spacer(Modifier.height(AppSpacing.xSmall))
                                Box(
                                    Modifier.width(22.dp).height(barHeight.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                )
                                Spacer(Modifier.height(AppSpacing.xSmall))
                                Text("${day.date.dayOfMonth}/${day.date.monthValue}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(AppSpacing.small))
                        Text("Thiết bị", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (state.devices.isEmpty()) {
                        Text("Chưa có snapshot thiết bị trên Firebase", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            val pendingSync = state.devices.sumOf { it.pendingAttendanceCount }
            val failedScanDevices = state.devices.filter { it.failedScanCount >= 5 }
            val failedScans = failedScanDevices.sumOf { it.failedScanCount }
            val deviceErrors = state.devices.count { it.lastError.isNotBlank() }
            if (pending + missing + abnormal + failedCommands + offlineDevices + pendingSync + failedScanDevices.size + deviceErrors > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text("Cảnh báo cần xử lý", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (pending > 0) Text("$pending đơn từ đang chờ duyệt")
                        if (missing > 0) Text("$missing người chưa chấm ra")
                        if (abnormal > 0) Text("$abnormal lượt có mặt bất thường")
                if (failedCommands > 0) Text("$failedCommands lệnh thiết bị thất bại")
                if (offlineDevices > 0) Text("$offlineDevices thiết bị offline/chưa rõ")
                        if (pendingSync > 0) Text("$pendingSync lượt chấm đang chờ đồng bộ")
                        if (failedScanDevices.isNotEmpty()) Text("$failedScans lượt quét vân tay thất bại gần đây trên ${failedScanDevices.size} thiết bị")
                        if (deviceErrors > 0) Text("$deviceErrors thiết bị có lỗi gần nhất")
                    }
                }
            }
        }
        item {
            if (state.notifications.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
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
        if (state.attendanceForSummaries.isEmpty()) {
            item { EmptyDashboardState() }
        } else {
            items(state.attendanceForSummaries.take(5), key = { it.id }) { DashboardAttendanceRow(it) }
        }
        item {
            val failedCommands = state.commands.count { it["status"] == "FAILED" }
            if (failedCommands > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(AppSpacing.small))
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
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = tint.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyDashboardState() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.xLarge), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(AppSpacing.small))
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
        Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(AppSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(item.employeeName.ifBlank { item.employeeId }, fontWeight = FontWeight.Bold)
                Text("$time • ${item.deviceId}", style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = attendanceStatusLabel(item.status),
                    modifier = Modifier.padding(horizontal = AppSpacing.small, vertical = AppSpacing.xSmall),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
