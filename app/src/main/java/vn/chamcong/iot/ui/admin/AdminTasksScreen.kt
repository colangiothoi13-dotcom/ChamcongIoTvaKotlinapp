package vn.chamcong.iot.ui.admin

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
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        "Vận hành",
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
        "Nhân sự & lương",
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
            Text("Danh mục quản trị", style = MaterialTheme.typography.headlineLarge)
            Text("Chọn chức năng bạn muốn xử lý", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(adminTaskGroups, key = { it.title }) { group ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AppSpacing.xLarge, top = AppSpacing.large, end = AppSpacing.xLarge, bottom = AppSpacing.small)
                    )
                    group.items.forEachIndexed { index, item ->
                        AdminTaskRow(
                            item = item,
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

@Composable
private fun AdminTaskRow(item: AdminTaskItem, modifier: Modifier = Modifier) {
    val (tint, background) = taskColors(item.destination)
    Row(
        modifier = modifier.padding(horizontal = AppSpacing.large, vertical = AppSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = background
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(AppSpacing.large))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(item.destination.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(item.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun taskColors(destination: AppDestination): Pair<Color, Color> = when (destination) {
    AppDestination.PRESENCE -> AppColorTokens.green to Color(0xFFE4F7E9)
    AppDestination.DEVICES -> AppColorTokens.blue to Color(0xFFE1F1FF)
    AppDestination.SHIFT_MANAGEMENT, AppDestination.SCHEDULE -> AppColorTokens.green to Color(0xFFE4F7E9)
    AppDestination.SHIFTS -> AppColorTokens.orange to Color(0xFFFFF0D8)
    AppDestination.PAYROLL -> AppColorTokens.pink to Color(0xFFFFE5EF)
    AppDestination.MONTHLY_TIMESHEET -> AppColorTokens.purple to Color(0xFFF2E5FA)
    AppDestination.PERFORMANCE -> Color(0xFFF0B400) to Color(0xFFFFF7D9)
    AppDestination.REPORTS -> AppColorTokens.blue to Color(0xFFE1F1FF)
    AppDestination.AUDIT -> Color(0xFF667085) to Color(0xFFEEF0F3)
    AppDestination.DEPARTMENTS -> Color(0xFF4F7D8A) to Color(0xFFE3F0F2)
    AppDestination.ANNOUNCEMENTS -> AppColorTokens.orange to Color(0xFFFFF0D8)
    AppDestination.SETTINGS -> Color(0xFF667085) to Color(0xFFEEF0F3)
    else -> MaterialThemeFallbackColors.primary to MaterialThemeFallbackColors.container
}

private object MaterialThemeFallbackColors {
    val primary = Color(0xFF05AA59)
    val container = Color(0xFFE4F7E9)
}
