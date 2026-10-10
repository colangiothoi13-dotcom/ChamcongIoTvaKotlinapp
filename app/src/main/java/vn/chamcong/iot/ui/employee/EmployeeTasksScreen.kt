package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.EmployeeDestination
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.employeeTaskDestinations

data class EmployeeTaskItem(
    val destination: EmployeeDestination,
    val description: String,
    val icon: ImageVector
)

data class EmployeeTaskGroup(
    val title: String,
    val items: List<EmployeeTaskItem>
)

val employeeTaskGroups = listOf(
    EmployeeTaskGroup(
        "Ca làm & chấm công",
        listOf(
            EmployeeTaskItem(EmployeeDestination.REQUESTS, "Gửi đơn nghỉ phép, điều chỉnh công và theo dõi kết quả", Icons.Default.Description),
            EmployeeTaskItem(EmployeeDestination.SHIFT_REGISTRATION, "Đăng ký ca tuần sau để Admin duyệt", Icons.Default.CalendarMonth),
            EmployeeTaskItem(EmployeeDestination.SCHEDULE, "Xem ca và lịch làm việc của bạn", Icons.Default.Event),
            EmployeeTaskItem(EmployeeDestination.ATTENDANCE, "Theo dõi giờ vào, giờ ra và công theo ngày", Icons.Default.Fingerprint)
        )
    ),
    EmployeeTaskGroup(
        "Thu nhập & hồ sơ",
        listOf(
            EmployeeTaskItem(EmployeeDestination.PAYROLL, "Xem bảng lương, giờ làm và tăng ca", Icons.Default.Payments),
            EmployeeTaskItem(EmployeeDestination.PROFILE, "Xem hồ sơ và cập nhật thông tin liên hệ", Icons.Default.Person),
            EmployeeTaskItem(EmployeeDestination.TENURE, "Ngày vào làm và thời gian gắn bó", Icons.Default.Work),
            EmployeeTaskItem(EmployeeDestination.REWARDS, "Xem các ghi nhận và khen thưởng của bạn", Icons.Default.Star)
        )
    ),
    EmployeeTaskGroup(
        "Thông tin & hỗ trợ",
        listOf(
            EmployeeTaskItem(EmployeeDestination.NOTIFICATIONS, "Thông báo và phản hồi dành cho bạn", Icons.Default.Notifications),
            EmployeeTaskItem(EmployeeDestination.NEWS, "Cập nhật tin tức từ Admin", Icons.Default.Event),
            EmployeeTaskItem(EmployeeDestination.MEETINGS, "Xem thời gian, địa điểm và nội dung cuộc họp", Icons.Default.Groups),
            EmployeeTaskItem(EmployeeDestination.DOCUMENTS, "Mở tài liệu được chia sẻ cho bạn", Icons.Default.Folder),
            EmployeeTaskItem(EmployeeDestination.SUPPORT, "Gửi yêu cầu hỗ trợ vân tay và xem phản hồi", Icons.Default.HeadsetMic)
        )
    )
).also { groups ->
    check(groups.flatMap { it.items }.map { it.destination } == employeeTaskDestinations) {
        "Danh mục Tiện ích không khớp với điều hướng nhân viên"
    }
}

@Composable
fun EmployeeTasksScreen(state: MainUiState, onOpen: (EmployeeDestination) -> Unit) {
    val pendingRequests = state.employeeRequests.count { it.status == RequestStatus.PENDING.name } +
        state.employeeOvertimeRequests.count { it.status == OvertimeRequestStatus.PENDING.name }
    val pendingSupport = state.employeeRequests.count {
        it.type == RequestType.FINGERPRINT_SUPPORT.name && it.status == RequestStatus.PENDING.name
    }
    val unreadNotifications = state.employeeNotifications.count { !it.read }

    LazyColumn(
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                Text("Tiện ích của bạn", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Chọn chức năng bạn cần sử dụng",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        items(employeeTaskGroups, key = { it.title }) { group ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(
                            start = AppSpacing.xLarge,
                            top = AppSpacing.large,
                            end = AppSpacing.xLarge,
                            bottom = AppSpacing.small
                        )
                    )
                    group.items.forEachIndexed { index, task ->
                        val badge = when (task.destination) {
                            EmployeeDestination.REQUESTS -> pendingRequests.takeIf { it > 0 }?.let { "$it chờ duyệt" }
                            EmployeeDestination.NOTIFICATIONS -> unreadNotifications.takeIf { it > 0 }?.let { "$it chưa đọc" }
                            EmployeeDestination.SUPPORT -> pendingSupport.takeIf { it > 0 }?.let { "$it chờ phản hồi" }
                            else -> null
                        }
                        EmployeeTaskRow(
                            task = task,
                            badge = badge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppTouchTarget.minimum)
                                .clickable(role = Role.Button) { onOpen(task.destination) }
                        )
                        if (index < group.items.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = AppSpacing.xLarge))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeeTaskRow(task: EmployeeTaskItem, badge: String?, modifier: Modifier = Modifier) {
    val (tint, background) = employeeTaskColors(task.destination)
    Row(
        modifier = modifier.padding(horizontal = AppSpacing.large, vertical = AppSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = MaterialTheme.shapes.medium,
            color = background
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(task.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(AppSpacing.medium))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(task.destination.title, style = MaterialTheme.typography.titleMedium)
            Text(
                task.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (badge != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = AppSpacing.small, vertical = AppSpacing.xSmall)
                    )
                }
            }
        }
        Spacer(Modifier.width(AppSpacing.small))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun employeeTaskColors(destination: EmployeeDestination): Pair<Color, Color> = when (destination) {
    EmployeeDestination.REQUESTS, EmployeeDestination.PAYROLL, EmployeeDestination.REWARDS ->
        MaterialTheme.colorScheme.onTertiaryContainer to MaterialTheme.colorScheme.tertiaryContainer
    EmployeeDestination.PROFILE, EmployeeDestination.TENURE, EmployeeDestination.DOCUMENTS ->
        MaterialTheme.colorScheme.onSecondaryContainer to MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.onPrimaryContainer to MaterialTheme.colorScheme.primaryContainer
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun EmployeeTasksScreenPreview() {
    PreviewStateScreen { state, _ -> EmployeeTasksScreen(state, onOpen = {}) }
}
