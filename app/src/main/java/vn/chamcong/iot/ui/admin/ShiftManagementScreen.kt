package vn.chamcong.iot.ui.admin

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
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
    ShiftHubItem(AppDestination.SHIFTS, "Ca sáng, ca chiều mặc định và phân tăng ca cho nhân viên", Icons.Default.Schedule),
    ShiftHubItem(AppDestination.SCHEDULE, "Chọn nhiều nhân viên và ngày từ thứ Hai đến thứ Bảy để phân ca", Icons.Default.Event)
)

@Composable
fun ShiftManagementScreen(
    onOpenWeeklyRegistrations: () -> Unit = {},
    onOpen: (AppDestination) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            AdminPageHeader("Phân ca làm việc", "Xếp lịch cho nhân viên hoặc phòng ban.")
        }
        item {
            OutlinedCard(onClick = onOpenWeeklyRegistrations, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(
                    headlineContent = { Text("Duyệt đăng ký tuần sau") },
                    supportingContent = { Text("Mở Đơn từ để xem và duyệt ca đăng ký cho tuần kế tiếp") },
                    leadingContent = { Icon(Icons.Default.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
            }
        }
        items(shiftHubItems, key = { it.destination.name }) { item ->
            OutlinedCard(onClick = { onOpen(item.destination) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(
                    headlineContent = { Text(item.destination.title) },
                    supportingContent = { Text(item.description) },
                    leadingContent = { Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ShiftManagementScreenPreview() {
    PreviewStateScreen { state, vm -> ShiftManagementScreen(onOpen = {}) }
}
