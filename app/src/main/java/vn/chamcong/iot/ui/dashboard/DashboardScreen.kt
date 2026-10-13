package vn.chamcong.iot.ui.dashboard

import vn.chamcong.iot.ui.workitems.WorkOverview

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import vn.chamcong.iot.ui.AppTouchTarget
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.OutlinedButton
import vn.chamcong.iot.model.WorkItemStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceMethod
import vn.chamcong.iot.model.attendanceMethod
import vn.chamcong.iot.ui.attendance.attendanceMethodLabel
import vn.chamcong.iot.model.isOnline
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.CalculationLoadingNotice
import vn.chamcong.iot.ui.AppDestination
import vn.chamcong.iot.ui.attendanceStatusLabel
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: MainUiState,
    vm: MainViewModel,
    onShortcut: ((AdminDashboardAction) -> Unit)? = null,
    onNavigate: (AppDestination) -> Unit
) {
    val inPreview = LocalInspectionMode.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(inPreview) {
        if (!inPreview) while (true) {
            delay(15_000)
            now = Instant.now()
        }
    }
    LaunchedEffect(state.selectedWeekStart) {
        if (!inPreview) vm.loadAttendanceRange(state.selectedWeekStart, state.selectedWeekStart.plusDays(6), force = true)
    }
    val summary = state.dashboard
    val daily = state.dailyDashboard
    val maxDailyCount = summary.weeklyAttendance.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            CalculationLoadingNotice(state, vm)
            AdminPageHeader("Tổng quan", "Chào ${state.userProfile?.displayName?.ifBlank { "bạn" } ?: "bạn"}, cùng theo dõi hoạt động hôm nay.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedButton(onClick = { vm.loadAttendanceRange(state.selectedWeekStart, state.selectedWeekStart.plusDays(6), force = true) }, enabled = !state.attendanceHistoryLoading, shape = MaterialTheme.shapes.small) { Text("Cập nhật") }
                Button(onClick = { onNavigate(AppDestination.REPORTS) }, shape = MaterialTheme.shapes.small) { Text("Xem báo cáo") }
            }
        }
        item { DashboardManagementActions(enabled = !state.saving, onAction = { action -> onShortcut?.invoke(action) ?: onNavigate(action.destination) }) }
        item {
            Text(
                "Tuần báo cáo: ${summary.weekStart} – ${summary.weekStart.plusDays(6)}",
                modifier = Modifier.padding(top = AppSpacing.small),
                style = MaterialTheme.typography.bodyMedium
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹ Tuần trước") }
                TextButton(onClick = { vm.selectWeek(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))) }) { Text("Tuần này") }
                TextButton(onClick = { vm.moveWeek(1) }) { Text("Tuần sau ›") }
            }
            Text("Hôm nay · ${daily.date}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Nhân viên đang làm", daily.activeEmployees.toString(), Icons.Default.Groups, Modifier.weight(1f), onClick = { onNavigate(AppDestination.EMPLOYEES) })
                DashboardMetric("Đang có mặt", daily.presentEmployees.toString(), Icons.Default.CheckCircle, Modifier.weight(1f), onClick = { onNavigate(AppDestination.PRESENCE) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Nhân viên đi trễ", daily.lateEmployees.size.toString(), Icons.Default.Schedule, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary, { onNavigate(AppDestination.ATTENDANCE) })
                DashboardMetric("Công việc chờ duyệt", if(state.workItemsLoading || state.workItemsError != null) "—" else state.workItems.count { it.status == WorkItemStatus.PENDING_REVIEW.name }.toString(), Icons.Default.Assignment, Modifier.weight(1f), onClick = { onNavigate(AppDestination.WORK_ITEMS) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Đã chấm vào", daily.checkedInEmployees.toString(), Icons.Default.Fingerprint, Modifier.weight(1f), onClick = { onNavigate(AppDestination.ATTENDANCE) })
                DashboardMetric("Chưa chấm vào", daily.notCheckedInEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant, { onNavigate(AppDestination.ATTENDANCE) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                DashboardMetric("Nghỉ phép", daily.onLeaveEmployees.toString(), Icons.Default.EventBusy, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary, { onNavigate(AppDestination.REQUESTS) })
                DashboardMetric("Thiếu chấm ra", daily.missingCheckOutEmployees.toString(), Icons.Default.WarningAmber, Modifier.weight(1f), MaterialTheme.colorScheme.error, { onNavigate(AppDestination.ATTENDANCE) })
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable { onNavigate(AppDestination.ATTENDANCE) }, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
        item { WorkOverview(state) { onNavigate(AppDestination.WORK_ITEMS) } }
        item {
            Card(Modifier.fillMaxWidth().clickable { onNavigate(AppDestination.ATTENDANCE) }, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                    Text("Hoạt động chấm công", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Theo tuần đã chọn · nhân viên có lượt chấm hợp lệ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        AssistChip(onClick = { onNavigate(AppDestination.ATTENDANCE) }, label = { Text("Đã chấm: ${summary.checkedEmployees}") })
                        AssistChip(onClick = { onNavigate(AppDestination.ATTENDANCE) }, label = { Text("Đi trễ: ${summary.lateEmployees}") })
                        AssistChip(onClick = { onNavigate(AppDestination.ATTENDANCE) }, label = { Text("Chưa chấm: ${summary.unmarkedEmployees}") })
                    }
                    Text(
                        "Mỗi nhân viên được tính một lần trong ngày.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            Card(Modifier.fillMaxWidth().clickable { onNavigate(AppDestination.DEVICES) }, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(AppSpacing.small))
                        Text("Thiết bị IoT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (state.devices.isEmpty()) {
                        Text("Chưa có thiết bị", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val online = state.devices.count { it.isOnline(now) }
                        Text("${online}/${state.devices.size} thiết bị đang kết nối")
                    }
                    Text("Thiết bị mất kết nối hoặc thiếu tín hiệu sẽ được hiển thị là cần kiểm tra.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val operationalIds = state.operationalEmployees.mapTo(mutableSetOf()) { it.id }
            val pending = state.leaveRequests.count { it.status == "PENDING" && it.employeeId in operationalIds }
            val missing = state.presenceRecords.count { it.status == vn.chamcong.iot.model.PresenceStatus.MISSING_CHECK_OUT }
            val abnormal = state.presenceRecords.count { it.status == vn.chamcong.iot.model.PresenceStatus.ABNORMAL }
            val failedCommands = state.commands.count { it["status"] == "FAILED" }
            val offlineDevices = state.devices.count { !it.isOnline(now) }
            val pendingSync = state.devices.sumOf { it.pendingAttendanceCount }
            val failedScanDevices = state.devices.filter { it.failedScanCount >= 5 }
            val failedScans = failedScanDevices.sumOf { it.failedScanCount }
            val deviceErrors = state.devices.count { it.lastError.isNotBlank() }
            if (pending + missing + abnormal + failedCommands + offlineDevices + pendingSync + failedScanDevices.size + deviceErrors > 0) {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text("Cảnh báo cần xử lý", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (pending > 0) Text("$pending đơn từ đang chờ duyệt")
                        if (missing > 0) Text("$missing người chưa chấm ra")
                        if (abnormal > 0) Text("$abnormal lượt có mặt bất thường")
                        if (failedCommands > 0) Text("$failedCommands lệnh thiết bị thất bại")
                        if (offlineDevices > 0) Text("$offlineDevices thiết bị mất kết nối hoặc chưa rõ trạng thái")
                        if (pendingSync > 0) Text("$pendingSync lượt chấm đang chờ đồng bộ")
                        if (failedScanDevices.isNotEmpty()) Text("$failedScans lượt quét vân tay thất bại gần đây trên ${failedScanDevices.size} thiết bị")
                        if (deviceErrors > 0) Text("$deviceErrors thiết bị có lỗi gần nhất")
                    }
                }
            }
        }
        item {
            if (state.visibleAdminNotifications.isNotEmpty()) {
                AdminNotificationsCard(
                    notifications = state.visibleAdminNotifications,
                    saving = state.saving,
                    onMarkRead = { ids -> ids.forEach { vm.markNotificationRead(it) } }
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chấm công gần nhất", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onNavigate(AppDestination.ATTENDANCE) }) { Text("Xem tất cả") }
            }
        }
        if (state.attendanceForSummaries.isEmpty()) {
            item { EmptyDashboardState() }
        } else {
            items(state.attendanceForSummaries.take(5), key = { it.id }) { DashboardAttendanceRow(it) { onNavigate(AppDestination.ATTENDANCE) } }
        }
        item {
            val failedCommands = state.commands.count { it["status"] == "FAILED" }
            if (failedCommands > 0) {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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

enum class AdminDashboardAction(val title: String, val description: String, val destination: AppDestination) {
    ADD_EMPLOYEE("Thêm nhân viên", "Tạo hồ sơ nhân sự mới.", AppDestination.EMPLOYEES),
    CREATE_WORK("Giao công việc", "Chọn nhân viên, nội dung và hạn hoàn thành.", AppDestination.WORK_ITEMS),
    ASSIGN_SCHEDULE("Phân ca làm việc", "Xếp lịch cho nhân viên hoặc phòng ban.", AppDestination.SCHEDULE),
    ADJUST_ATTENDANCE("Điều chỉnh công", "Bổ sung giờ vào, giờ ra và lý do.", AppDestination.ATTENDANCE),
    REVIEW_REQUESTS("Duyệt đơn từ", "Xử lý lịch tuần, nghỉ phép và tăng ca.", AppDestination.REQUESTS),
    MANAGE_PAYROLL("Quản lý lương", "Đặt đơn giá, lập và tính lại phiếu lương.", AppDestination.PAYROLL)
}

@Composable
private fun DashboardManagementActions(enabled: Boolean, onAction: (AdminDashboardAction) -> Unit) {
    AdminSectionCard {
        Text("Thao tác quản lý", style = MaterialTheme.typography.titleMedium)
        Text("Thêm, sửa và duyệt dữ liệu.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showDescriptions = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        AdminDashboardAction.entries.chunked(if(showDescriptions) 3 else 2).forEach { actions ->
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        actions.forEach { action ->
            Card(
                onClick = { onAction(action) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 104.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Icon(when(action) {
                        AdminDashboardAction.ADD_EMPLOYEE -> Icons.Default.PersonAdd
                        AdminDashboardAction.CREATE_WORK -> Icons.Default.Assignment
                        AdminDashboardAction.ASSIGN_SCHEDULE -> Icons.Default.CalendarMonth
                        AdminDashboardAction.ADJUST_ATTENDANCE -> Icons.Default.Schedule
                        AdminDashboardAction.REVIEW_REQUESTS -> Icons.Default.CheckCircle
                        AdminDashboardAction.MANAGE_PAYROLL -> Icons.Default.Payments
                    }, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text(action.title, style = MaterialTheme.typography.labelLarge)
                        if(showDescriptions) Text(action.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalAlignment = Alignment.Top) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyDashboardState() {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(AppSpacing.xLarge), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(AppSpacing.small))
            Text("Chưa có lượt chấm công hôm nay")
        }
    }
}

@Composable
private fun DashboardAttendanceRow(item: Attendance, onClick: () -> Unit) {
    val time = remember(item.timestamp) {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(item.timestamp.toDate())
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (item.attendanceMethod()) {
                    AttendanceMethod.FACE -> Icons.Default.Face
                    AttendanceMethod.FINGERPRINT -> Icons.Default.Fingerprint
                    AttendanceMethod.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
                },
                null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(AppSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(item.employeeName.ifBlank { item.employeeId }, fontWeight = FontWeight.Bold)
                Text("$time • ${item.deviceId}", style = MaterialTheme.typography.bodySmall)
                Text(attendanceMethodLabel(item), style = MaterialTheme.typography.bodySmall)
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun DashboardScreenPreview() {
    PreviewStateScreen { state, vm -> DashboardScreen(state, vm, onNavigate = {}) }
}
