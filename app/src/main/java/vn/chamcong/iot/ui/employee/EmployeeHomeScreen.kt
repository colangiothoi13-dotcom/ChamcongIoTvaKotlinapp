package vn.chamcong.iot.ui.employee

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.ui.AppColorTokens
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", java.util.Locale("vi", "VN"))

private data class HomeUtility(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val background: Color,
    val badge: String? = null,
    val onClick: (() -> Unit)? = null
)

@Composable
fun EmployeeHomeScreen(
    state: MainUiState,
    vm: MainViewModel,
    onOpenSchedule: () -> Unit = {},
    onOpenAttendance: () -> Unit = {},
    onOpenRequests: () -> Unit = {},
    onOpenProfile: () -> Unit = {}
) {
    var notificationDialog by remember { mutableStateOf(false) }
    var unavailableUtility by remember { mutableStateOf<String?>(null) }
    val employee = state.currentEmployee
    if (employee == null) {
        EmptyEmployeeLinkState()
        return
    }

    val today = LocalDate.now(zone)
    val summaries = vm.employeeMonthSummaries(today)
    val todaySummary = summaries.firstOrNull { it.date == today }
    val workedDays = summaries.count { it.workedHours > 0.0 || it.overtimeHours > 0.0 }
    val totalHours = summaries.sumOf {
        if (it.workedSeconds > 0L) it.workedSeconds / 3600.0 else it.workedHours
    }
    val totalOvertimeHours = summaries.sumOf {
        if (it.overtimeSeconds > 0L) it.overtimeSeconds / 3600.0 else it.overtimeHours
    }
    val lateCount = summaries.count { it.status == EmployeeAttendanceStatus.LATE || it.status == EmployeeAttendanceStatus.ABNORMAL }
    val pendingRequests = state.employeeRequests.count { it.status == "PENDING" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp)
                    )
                    .padding(horizontal = AppSpacing.large, vertical = AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.large)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(62.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.width(AppSpacing.medium))
                    Column(Modifier.weight(1f)) {
                        Text(employee.fullName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(
                            state.userProfile?.email?.ifBlank { employee.email }?.ifBlank { "Nhân viên" } ?: "Nhân viên",
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BadgedBox(
                        badge = {
                            if (pendingRequests > 0) Badge { Text(pendingRequests.toString()) }
                        }
                    ) {
                        IconButton(onClick = { notificationDialog = true }) {
                            Icon(Icons.Default.Notifications, contentDescription = "Thông báo", tint = Color.White)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Công việc hôm nay", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenAttendance) {
                        Text("Xem thêm", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                HomeJobRow(
                    title = "Ca làm việc",
                    time = if (todaySummary?.shiftName?.isNullOrBlank() == false) {
                        "${todaySummary.shiftName}  ·  ${todaySummary.shiftStartTime} – ${todaySummary.shiftEndTime}"
                    } else {
                        "Chưa được phân ca"
                    },
                    icon = Icons.Default.CalendarMonth,
                    onClick = onOpenSchedule
                )
                HomeJobRow(
                    title = "Trạng thái chấm công",
                    time = "${todaySummary?.checkIn?.let(::formatTime) ?: "Chưa chấm vào"}  ·  ${todaySummary?.checkOut?.let(::formatTime) ?: "Chưa chấm ra"}",
                    icon = Icons.Default.Fingerprint,
                    onClick = onOpenAttendance
                )
            }
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tiện ích", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenAttendance) {
                        Text("Xem tất cả", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val utilities = listOf(
                    HomeUtility("Đơn báo", Icons.Default.Description, AppColorTokens.orange, Color(0xFFFFEAC7), pendingRequests.takeIf { it > 0 }?.toString(), onOpenRequests),
                    HomeUtility("Lịch họp", Icons.Default.Groups, AppColorTokens.blue, Color(0xFFD9EEFF)),
                    HomeUtility("Thông tin", Icons.Default.Person, AppColorTokens.green, Color(0xFFDFF3E5), onClick = onOpenProfile),
                    HomeUtility("Thâm niên", Icons.Default.Work, AppColorTokens.purple, Color(0xFFF0DFF8), onClick = onOpenProfile),
                    HomeUtility("Tin tức", Icons.Default.Event, AppColorTokens.pink, Color(0xFFF8DDE8)),
                    HomeUtility("Khen thưởng", Icons.Default.Star, AppColorTokens.orange, Color(0xFFFFF0C8)),
                    HomeUtility("Tài liệu", Icons.Default.Folder, Color(0xFF557785), Color(0xFFE0EAED)),
                    HomeUtility("Hỗ trợ", Icons.Default.HeadsetMic, Color(0xFF12AFC1), Color(0xFFD9F3F6), onClick = onOpenRequests)
                )
                utilities.chunked(4).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        rowItems.forEach { utility ->
                            HomeUtilityItem(
                                utility,
                                Modifier.weight(1f),
                                onClick = { utility.onClick?.invoke() ?: run { unavailableUtility = utility.label } }
                            )
                        }
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Text("Trạng thái ca làm", style = MaterialTheme.typography.headlineSmall)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                        StatusLine(
                            label = "Hôm nay",
                            value = todaySummary?.status?.toVietnamese() ?: "Chưa có dữ liệu",
                            tint = if (todaySummary?.status == EmployeeAttendanceStatus.ON_TIME || todaySummary?.status == EmployeeAttendanceStatus.PRESENT) AppColorTokens.green else AppColorTokens.orange
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            SummaryStat("Giờ làm", "%.1f".format(totalHours), Modifier.weight(1f))
                            SummaryStat("Tăng ca", "%.1f".format(totalOvertimeHours), Modifier.weight(1f))
                            SummaryStat("Đi trễ", lateCount.toString(), Modifier.weight(1f))
                            SummaryStat("Ngày công", workedDays.toString(), Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (state.employeeNotifications.isNotEmpty()) {
            item {
                Column(
                    Modifier.padding(horizontal = AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Text("Thông báo mới", style = MaterialTheme.typography.titleLarge)
                    state.employeeNotifications.take(3).forEach { notification ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(AppSpacing.large)) {
                                Text(notification.title, fontWeight = if (notification.read) FontWeight.Normal else FontWeight.Bold)
                                Text(notification.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(AppSpacing.small)) }
    }

    if (notificationDialog) {
        AlertDialog(
            onDismissRequest = { notificationDialog = false },
            title = { Text("Thông báo") },
            text = {
                if (state.employeeNotifications.isEmpty()) {
                    Text("Chưa có thông báo mới")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        state.employeeNotifications.take(8).forEach { notification ->
                            Text(notification.title, fontWeight = if (notification.read) FontWeight.Normal else FontWeight.Bold)
                            Text(notification.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { notificationDialog = false }) { Text("Đóng") } }
        )
        LaunchedEffect(notificationDialog, state.employeeNotifications) {
            if (notificationDialog) {
                state.employeeNotifications.filterNot { it.read }.forEach { notification ->
                    vm.markEmployeeNotificationRead(notification.id)
                }
            }
        }
    }

    unavailableUtility?.let { label ->
        AlertDialog(
            onDismissRequest = { unavailableUtility = null },
            title = { Text(label) },
            text = { Text("Tính năng này đang được phát triển và chưa có dữ liệu trong hệ thống.") },
            confirmButton = { TextButton(onClick = { unavailableUtility = null }) { Text("Đã hiểu") } }
        )
    }
}

@Composable
private fun HomeJobRow(title: String, time: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(AppSpacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(AppSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun HomeUtilityItem(utility: HomeUtility, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(modifier = Modifier.size(68.dp), shape = MaterialTheme.shapes.large, color = utility.background) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(utility.icon, contentDescription = null, tint = utility.tint, modifier = Modifier.size(32.dp))
                }
            }
            utility.badge?.let {
                Badge(modifier = Modifier.align(Alignment.TopEnd)) { Text(it) }
            }
        }
        Spacer(Modifier.height(AppSpacing.small))
        Text(utility.label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun StatusLine(label: String, value: String, tint: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(tint, CircleShape))
        Spacer(Modifier.width(AppSpacing.small))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyEmployeeLinkState() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Chưa liên kết hồ sơ nhân viên", style = MaterialTheme.typography.titleLarge)
            Text("Hãy liên hệ Admin để gán users/{uid}.employeeId vào hồ sơ của bạn.")
        }
    }
}

internal fun EmployeeAttendanceStatus.toVietnamese(): String = when (this) {
    EmployeeAttendanceStatus.PRESENT -> "Đang làm việc"
    EmployeeAttendanceStatus.ON_TIME -> "Đủ công"
    EmployeeAttendanceStatus.LATE -> "Đi trễ"
    EmployeeAttendanceStatus.EARLY_LEAVE -> "Về sớm"
    EmployeeAttendanceStatus.ABNORMAL -> "Bất thường"
    EmployeeAttendanceStatus.MISSING_CHECK_IN -> "Chưa chấm vào"
    EmployeeAttendanceStatus.MISSING_CHECK_OUT -> "Chưa chấm ra"
    EmployeeAttendanceStatus.LEAVE -> "Nghỉ phép"
}

internal fun formatTime(value: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(value)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun EmployeeHomeScreenPreview() {
    PreviewStateScreen { state, vm -> EmployeeHomeScreen(state, vm) }
}
