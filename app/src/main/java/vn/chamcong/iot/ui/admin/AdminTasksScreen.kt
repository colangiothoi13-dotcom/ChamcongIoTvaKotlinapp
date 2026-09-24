package vn.chamcong.iot.ui.admin

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.AppDestination
import vn.chamcong.iot.ui.adminTaskDestinations

data class AdminTaskItem(
    val destination: AppDestination,
    val description: String,
    val icon: ImageVector
)

data class AdminTaskGroup(
    val title: String,
    val items: List<AdminTaskItem>
)

val adminTaskGroups = listOf(
    AdminTaskGroup(
        "Hiện diện & thiết bị",
        listOf(
            AdminTaskItem(AppDestination.PRESENCE, "Biết nhanh ai đang có mặt", Icons.Default.HowToReg),
            AdminTaskItem(AppDestination.DEVICES, "Theo dõi trạng thái và điều khiển thiết bị từ xa", Icons.Default.Devices)
        )
    ),
    AdminTaskGroup(
        "Lịch & ca",
        listOf(
            AdminTaskItem(AppDestination.SHIFT_MANAGEMENT, "Mở nhanh quản lý ca và lịch làm việc", Icons.Default.CalendarMonth),
            AdminTaskItem(AppDestination.SHIFTS, "Tạo ca sáng, ca chiều và ca bổ sung", Icons.Default.Schedule),
            AdminTaskItem(AppDestination.SCHEDULE, "Phân ca theo tuần/tháng và sao chép lịch", Icons.Default.Event)
        )
    ),
    AdminTaskGroup(
        "Lương & báo cáo",
        listOf(
            AdminTaskItem(AppDestination.PAYROLL, "Tính lương, giờ làm và tăng ca", Icons.Default.Payments),
            AdminTaskItem(AppDestination.PERFORMANCE, "Theo dõi hiệu suất nhân sự", Icons.Default.Insights),
            AdminTaskItem(AppDestination.REPORTS, "Báo cáo chấm công, nghỉ phép và thiết bị", Icons.Default.Assessment),
            AdminTaskItem(AppDestination.MONTHLY_TIMESHEET, "Tổng hợp công, ngày công và giờ làm theo tháng", Icons.Default.CalendarMonth)
        )
    ),
    AdminTaskGroup(
        "Quản trị",
        listOf(
            AdminTaskItem(AppDestination.AUDIT, "Lịch sử thay đổi trong hệ thống", Icons.Default.History),
            AdminTaskItem(AppDestination.DEPARTMENTS, "Tạo, chỉnh sửa và bật/tắt phòng ban", Icons.Default.Business),
            AdminTaskItem(AppDestination.ANNOUNCEMENTS, "Gửi thông báo và xem lịch sử gửi", Icons.Default.Campaign),
            AdminTaskItem(AppDestination.SETTINGS, "Cấu hình doanh nghiệp và ứng dụng", Icons.Default.Settings)
        )
    )
).also { groups ->
    check(groups.flatMap { it.items }.map { it.destination } == adminTaskDestinations) {
        "Danh mục Tác vụ không khớp với hợp đồng điều hướng Admin"
    }
}

@Composable
fun AdminTasksScreen(onOpen: (AppDestination) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text("Tác vụ", style = MaterialTheme.typography.headlineSmall)
            Text("Mở nhanh các chức năng quản lý", style = MaterialTheme.typography.bodyMedium)
        }
        items(adminTaskGroups, key = { it.title }) { group ->
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = AppSpacing.xLarge, top = AppSpacing.large, end = AppSpacing.xLarge, bottom = AppSpacing.small)
                    )
                    group.items.forEachIndexed { index, item ->
                        ListItem(
                            headlineContent = { Text(item.destination.title) },
                            supportingContent = { Text(item.description) },
                            leadingContent = { Icon(item.icon, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppTouchTarget.minimum)
                                .clickable(role = Role.Button) { onOpen(item.destination) }
                        )
                        if (index < group.items.lastIndex) HorizontalDivider(Modifier.padding(horizontal = AppSpacing.xLarge))
                    }
                }
            }
        }
    }
}
