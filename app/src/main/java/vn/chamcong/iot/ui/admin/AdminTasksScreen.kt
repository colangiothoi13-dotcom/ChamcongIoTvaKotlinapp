package vn.chamcong.iot.ui.admin

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.AppColorTokens
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.AppDestination
import vn.chamcong.iot.ui.adminTaskDestinations
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard

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
        "Làm việc",
        listOf(
            AdminTaskItem(AppDestination.DASHBOARD, "Tình hình công việc và chấm công trong một màn hình", Icons.Default.Dashboard),
            AdminTaskItem(AppDestination.WORK_ITEMS, "Giao việc, theo dõi tiến độ và duyệt kết quả", Icons.Default.Assignment),
            AdminTaskItem(AppDestination.ATTENDANCE, "Xem và xử lý lượt chấm công", Icons.Default.Fingerprint),
            AdminTaskItem(AppDestination.PRESENCE, "Theo dõi tình hình nhân viên theo từng ca làm", Icons.Default.HowToReg)
        )
    ),
    AdminTaskGroup(
        "Nhân sự",
        listOf(
            AdminTaskItem(AppDestination.EMPLOYEES, "Hồ sơ, tài khoản, vân tay và khuôn mặt", Icons.Default.Groups),
            AdminTaskItem(AppDestination.SCHEDULE, "Phân ca và theo dõi lịch làm việc theo tuần", Icons.Default.Event),
            AdminTaskItem(AppDestination.SHIFTS, "Hai ca mặc định và phân tăng ca cho nhân viên", Icons.Default.Schedule),
            AdminTaskItem(AppDestination.REQUESTS, "Duyệt đăng ký tuần, nghỉ phép và tăng ca", Icons.Default.Description),
            AdminTaskItem(AppDestination.SHIFT_MANAGEMENT, "Thao tác nhanh: phân ca làm việc", Icons.Default.CalendarMonth)
        )
    ),
    AdminTaskGroup(
        "Tổng hợp",
        listOf(
            AdminTaskItem(AppDestination.PAYROLL, "Tính lương, giờ làm và tăng ca", Icons.Default.Payments),
            AdminTaskItem(AppDestination.REPORTS, "Báo cáo chấm công, nghỉ phép và thiết bị", Icons.Default.Assessment),
            AdminTaskItem(AppDestination.MONTHLY_TIMESHEET, "Chi tiết bảng công theo nhân viên và ngày", Icons.Default.CalendarMonth),
            AdminTaskItem(AppDestination.PERFORMANCE, "Theo dõi hiệu suất và kết quả công việc", Icons.Default.Insights)
        )
    ),
    AdminTaskGroup(
        "Quản trị",
        listOf(
            AdminTaskItem(AppDestination.CAMERA, "Xem camera và đăng ký khuôn mặt từ mục Nhân viên", Icons.Default.CameraAlt),
            AdminTaskItem(AppDestination.DEVICES, "Theo dõi trạng thái và điều khiển thiết bị từ xa", Icons.Default.Devices),
            AdminTaskItem(AppDestination.DEPARTMENTS, "Tạo, chỉnh sửa và bật/tắt phòng ban", Icons.Default.Business),
            AdminTaskItem(AppDestination.ANNOUNCEMENTS, "Gửi thông báo và xem lịch sử gửi", Icons.Default.Campaign),
            AdminTaskItem(AppDestination.EMPLOYEE_RESOURCES, "Quản lý lịch họp, khen thưởng và tài liệu", Icons.Default.Folder),
            AdminTaskItem(AppDestination.AUDIT, "Lịch sử thao tác quản trị của hệ thống", Icons.Default.History)
        )
    )
).also { groups ->
    check(groups.flatMap { it.items }.map { it.destination } == adminTaskDestinations) {
        "Danh mục Tiện ích không khớp với hợp đồng điều hướng Admin"
    }
}

@Composable
fun AdminTasksScreen(onOpen: (AppDestination) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            AdminPageHeader("Danh mục", "Các nhóm chức năng giống thanh điều hướng trên web.")
        }
        items(adminTaskGroups, key = { it.title }) { group ->
            AdminSectionCard {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    group.items.forEachIndexed { index, item ->
                        AdminTaskRow(
                            item = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppTouchTarget.minimum)
                                .clickable(role = Role.Button) { onOpen(item.destination) }
                        )
                        if (index < group.items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
            }
        }
    }
}

@Composable
private fun AdminTaskRow(item: AdminTaskItem, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.primaryContainer
    Row(
        modifier = modifier.padding(vertical = AppSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.small,
            color = background
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(AppSpacing.large))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(item.destination.title, style = MaterialTheme.typography.titleMedium)
            Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(AppSpacing.small))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Mở ${item.destination.title}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AdminTasksScreenPreview() {
    PreviewStateScreen { state, vm -> AdminTasksScreen(onOpen = {}) }
}
