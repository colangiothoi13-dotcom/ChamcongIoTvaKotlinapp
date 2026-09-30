package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.AppSpacing
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun OffScheduleReviewDialog(
    row: Attendance,
    attendance: List<Attendance>,
    shifts: List<WorkShift>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (shiftId: String, decision: String, reason: String) -> Unit
) {
    val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    val scanDate = row.timestamp.toDate().toInstant().atZone(zone).toLocalDate()
    val scanFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale("vi", "VN"))
    val sameDayScans = remember(row.employeeId, row.timestamp, attendance) {
        attendance.filter {
            it.employeeId == row.employeeId &&
                it.type == "UNSCHEDULED" &&
                it.timestamp.toDate().toInstant().atZone(zone).toLocalDate() == scanDate
        }.sortedBy { it.timestamp.toDate().time }
    }
    val options = remember(shifts) {
        shifts.filter { it.active && it.category in setOf(ShiftCategory.MORNING.name, ShiftCategory.EVENING.name) }
            .sortedWith(compareBy<WorkShift> { it.startTime }.thenBy { it.name })
    }
    var selectedShiftId by remember(row.id, options) { mutableStateOf(options.firstOrNull()?.id.orEmpty()) }
    var decision by remember(row.id) { mutableStateOf("APPROVE") }
    var reason by remember(row.id) { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedShift = options.firstOrNull { it.id == selectedShiftId }
    val decisionTitle = if (decision == "APPROVE") "Duyệt ca ngoài lịch" else "Từ chối ca ngoài lịch"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xử lý chấm công ngoài lịch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${row.employeeName.ifBlank { row.employeeId }} • ${scanDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")
                Text("Lượt quét trong ngày: ${sameDayScans.joinToString { scan ->
                    scan.timestamp.toDate().toInstant().atZone(zone).format(scanFormatter)
                }.ifBlank { "không tìm thấy thêm" }}", style = MaterialTheme.typography.bodySmall)
                Text("Nếu duyệt, hệ thống gán các lượt phù hợp vào ca đã chọn rồi tự tính công. Thiếu lượt vào hoặc ra vẫn được 0 giờ cho ca đó.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { menuExpanded = true }, enabled = options.isNotEmpty()) {
                        Text(selectedShift?.let { "${it.name} (${it.startTime}–${it.endTime})" } ?: "Chọn ca")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        options.forEach { shift ->
                            DropdownMenuItem(
                                text = { Text("${shift.name} (${shift.startTime}–${shift.endTime})") },
                                onClick = { selectedShiftId = shift.id; menuExpanded = false }
                            )
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(selected = decision == "APPROVE", onClick = { decision = "APPROVE" }, label = { Text("Duyệt") })
                    FilterChip(selected = decision == "REJECT", onClick = { decision = "REJECT" }, label = { Text("Từ chối") })
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lý do xử lý") },
                    minLines = 2
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedShift?.let { onSubmit(it.id, decision, reason.trim()) } },
                enabled = !busy && selectedShift != null && reason.isNotBlank()
            ) { Text(decisionTitle) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Hủy") } }
    )
}
