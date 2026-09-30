package vn.chamcong.iot.ui.presence

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.PresenceRecord
import vn.chamcong.iot.model.PresenceStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale

@Composable
fun PresenceScreen(state: MainUiState, vm: MainViewModel) {
    var selected by remember { mutableStateOf<PresenceStatus?>(null) }
    val records = state.presenceRecords
    val visible = records.filter { selected == null || it.status == selected }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Text("Theo dõi trạng thái có mặt", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.selectPresenceDate(state.selectedPresenceDate.minusDays(1)) }) { Text("‹ Ngày trước") }
            Text(state.selectedPresenceDate.toString(), modifier = Modifier.padding(top = AppSpacing.medium))
            TextButton(onClick = { vm.selectPresenceDate(state.selectedPresenceDate.plusDays(1)) }) { Text("Ngày sau ›") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { selected = null },
                label = { Text("Tất cả ${records.size}", maxLines = 1, softWrap = false) }
            )
            PresenceStatus.entries.forEach { status ->
                val count = records.count { it.status == status }
                FilterChip(
                    selected = selected == status,
                    onClick = { selected = status },
                    label = { Text("${statusLabel(status)} $count", maxLines = 1, softWrap = false) }
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            if (visible.isEmpty()) item { Text("Không có nhân viên trong nhóm này") }
            items(visible, key = { it.employee.id }) { PresenceCard(it) }
        }
    }
}

@Composable
private fun PresenceCard(record: PresenceRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text("${record.employee.code} • ${record.employee.fullName}", style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(record.status), color = statusColor(record.status))
            record.latestAttendance?.let { attendance ->
                val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(attendance.timestamp.toDate())
                Text("Lượt gần nhất: $time • ${attendance.deviceId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusLabel(status: PresenceStatus): String = when (status) {
    PresenceStatus.PRESENT -> "Đã vào công ty"
    PresenceStatus.NOT_CHECKED_IN -> "Chưa đến"
    PresenceStatus.ON_LEAVE -> "Đang nghỉ phép"
    PresenceStatus.LEFT -> "Đã ra về"
    PresenceStatus.MISSING_CHECK_OUT -> "Chưa chấm ra"
    PresenceStatus.ABNORMAL -> "Có mặt bất thường"
}

@Composable
private fun statusColor(status: PresenceStatus) = when (status) {
    PresenceStatus.PRESENT -> MaterialTheme.colorScheme.primary
    PresenceStatus.ON_LEAVE -> MaterialTheme.colorScheme.secondary
    PresenceStatus.LEFT -> MaterialTheme.colorScheme.onSurfaceVariant
    PresenceStatus.MISSING_CHECK_OUT, PresenceStatus.ABNORMAL -> MaterialTheme.colorScheme.error
    PresenceStatus.NOT_CHECKED_IN -> MaterialTheme.colorScheme.outline
}
