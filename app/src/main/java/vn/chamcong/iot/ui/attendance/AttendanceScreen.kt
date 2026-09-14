package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.AttendanceList
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel

@Composable
fun AttendanceScreen(state: MainUiState, vm: MainViewModel) {
    val statuses = listOf("Tất cả" to null, "Đúng giờ" to "NORMAL", "Đi trễ" to "LATE", "Về sớm" to "EARLY_LEAVE")
    val types = listOf("Tất cả loại" to null, "Vào ca" to "CHECK_IN", "Ra ca" to "CHECK_OUT")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lịch sử chấm công", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            statuses.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceStatusFilter == value,
                    onClick = { vm.setAttendanceFilters(value, state.attendanceTypeFilter) },
                    label = { Text(label) }
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceTypeFilter == value,
                    onClick = { vm.setAttendanceFilters(state.attendanceStatusFilter, value) },
                    label = { Text(label) }
                )
            }
        }
        Text("${state.visibleAttendance.size} lượt chấm", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        AttendanceList(state.visibleAttendance, Modifier.weight(1f))
    }
}
