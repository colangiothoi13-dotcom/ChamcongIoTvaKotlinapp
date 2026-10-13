package vn.chamcong.iot.ui.presence

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import vn.chamcong.iot.ui.AdminMetricCard
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInspectionMode
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
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.CalculationLoadingNotice
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale

@Composable
fun PresenceScreen(state: MainUiState, vm: MainViewModel) {
    val inPreview = LocalInspectionMode.current
    val date = state.selectedPresenceDate
    LaunchedEffect(date) {
        if (!inPreview) vm.loadAttendanceRange(date, date, force = true)
    }
    var selected by remember { mutableStateOf<PresenceStatus?>(null) }
    val ready = inPreview || state.hasCompleteCalculationRange(date, date)
    val records = if (ready) state.presenceRecords else emptyList()
    val visible = records.filter { selected == null || it.status == selected }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item { AdminPageHeader("Có mặt", "Tình trạng nhân viên theo ca đang diễn ra; cập nhật cùng dữ liệu thiết bị.") }
        item { CalculationLoadingNotice(state, vm) }
        item {
            AdminSectionCard {
                Text("Ngày theo dõi", style = MaterialTheme.typography.titleMedium)
                Text(state.selectedPresenceDate.toString(), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { vm.selectPresenceDate(state.selectedPresenceDate.minusDays(1)) }) { Text("‹ Ngày trước") }
                    TextButton(onClick = { vm.selectPresenceDate(state.selectedPresenceDate.plusDays(1)) }) { Text("Ngày sau ›") }
                }
                TextButton(onClick = { vm.loadAttendanceRange(date, date, force = true) }, enabled = !state.attendanceHistoryLoading) {
                    Text("Làm mới")
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
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Đang có mặt", if (ready) records.count { it.status == PresenceStatus.PRESENT }.toString() else "—", "Đã vào và chưa ra ca", Modifier.weight(1f))
                    AdminMetricCard("Đã rời ca", if (ready) records.count { it.status == PresenceStatus.LEFT }.toString() else "—", "Có lượt ra hợp lệ", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Nghỉ phép", if (ready) records.count { it.status == PresenceStatus.ON_LEAVE }.toString() else "—", "Ca được duyệt nghỉ", Modifier.weight(1f))
                    AdminMetricCard("Cần kiểm tra", if (ready) records.count { it.status in setOf(PresenceStatus.ABNORMAL, PresenceStatus.MISSING_CHECK_OUT) }.toString() else "—", "Kiểm tra lượt chấm", Modifier.weight(1f))
                }
            }
        }
        item { Text("Nhân viên theo ca", style = MaterialTheme.typography.titleMedium) }
        if (ready && visible.isEmpty()) item { AdminSectionCard { Text("Không có nhân viên trong nhóm này", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(visible, key = { it.employee.id }) { PresenceCard(it) }
    }
}

@Composable
private fun PresenceCard(record: PresenceRecord) {
    AdminSectionCard {
            Text("${record.employee.code} • ${record.employee.fullName}", style = MaterialTheme.typography.titleMedium)
            Text(record.employee.department.ifBlank { "Chưa có phòng ban" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(statusLabel(record.status), color = statusColor(record.status))
            record.latestAttendance?.let { attendance ->
                val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(attendance.timestamp.toDate())
                Text("Lượt gần nhất: $time • ${attendance.deviceId}", style = MaterialTheme.typography.bodySmall)
            }
    }
}

private fun statusLabel(status: PresenceStatus): String = when (status) {
    PresenceStatus.PRESENT -> "Đang có mặt"
    PresenceStatus.NOT_CHECKED_IN -> "Chưa chấm vào"
    PresenceStatus.ON_LEAVE -> "Nghỉ phép"
    PresenceStatus.LEFT -> "Đã rời ca"
    PresenceStatus.MISSING_CHECK_OUT -> "Thiếu chấm ra"
    PresenceStatus.ABNORMAL -> "Cần kiểm tra"
}

@Composable
private fun statusColor(status: PresenceStatus) = when (status) {
    PresenceStatus.PRESENT -> MaterialTheme.colorScheme.primary
    PresenceStatus.ON_LEAVE -> MaterialTheme.colorScheme.secondary
    PresenceStatus.LEFT -> MaterialTheme.colorScheme.onSurfaceVariant
    PresenceStatus.MISSING_CHECK_OUT, PresenceStatus.ABNORMAL -> MaterialTheme.colorScheme.error
    PresenceStatus.NOT_CHECKED_IN -> MaterialTheme.colorScheme.outline
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PresenceScreenPreview() {
    PreviewStateScreen { state, vm -> PresenceScreen(state, vm) }
}
