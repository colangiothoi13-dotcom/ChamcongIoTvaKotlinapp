package vn.chamcong.iot.ui.admin

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import vn.chamcong.iot.ui.AppDestination

private data class ShiftHubItem(
    val destination: AppDestination,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val shiftHubItems = listOf(
    ShiftHubItem(AppDestination.SHIFTS, "Quản lý ca sáng, ca chiều và ca bổ sung/tăng ca", Icons.Default.Schedule),
    ShiftHubItem(AppDestination.SCHEDULE, "Chọn nhiều nhân viên và ngày từ thứ hai đến chủ nhật để phân ca", Icons.Default.Event)
)

@Composable
fun ShiftManagementScreen(onOpen: (AppDestination) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text("Phân ca", style = MaterialTheme.typography.headlineSmall)
            Text("Quản lý ca làm và lịch làm việc theo tuần", style = MaterialTheme.typography.bodyMedium)
        }
        items(shiftHubItems, key = { it.destination.name }) { item ->
            Card(onClick = { onOpen(item.destination) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(item.destination.title) },
                    supportingContent = { Text(item.description) },
                    leadingContent = { Icon(item.icon, contentDescription = null) }
                )
            }
        }
    }
}
