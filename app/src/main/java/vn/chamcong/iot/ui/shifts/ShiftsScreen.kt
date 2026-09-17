package vn.chamcong.iot.ui.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate

@Composable
fun ShiftsScreen(state: MainUiState, vm: MainViewModel) {
    var editor by remember { mutableStateOf<WorkShift?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quản lý ca làm", style = MaterialTheme.typography.titleLarge)
        Text("Phân lịch có sẵn: Ca sáng 08:00–12:00, Ca chiều 13:00–17:00, Ca bổ sung/tăng ca nhập giờ mỗi lần. Các ca cũ vẫn được giữ.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            vm.clearError()
            editor = WorkShift(name = "Ca sáng", effectiveFrom = LocalDate.now().toString())
        }) { Text("Thêm ca") }
        if (state.shifts.isEmpty()) {
            Text("Chưa có ca. Hãy tạo ca sáng, ca tối hoặc ca bổ sung.")
        } else {
            state.shifts.forEach { shift ->
                ShiftRow(shift) {
                    vm.clearError()
                    // Template snapshots must keep the times used by previously assigned schedules.
                    editor = if (shift.id.startsWith("weekly_v1_")) shift.copy(id = "") else shift
                }
            }
        }
    }
    editor?.let { shift ->
        ShiftEditorDialog(
            initial = shift,
            state = state,
            onDismiss = { if (!state.saving) editor = null },
            onSave = { value -> vm.saveShift(value) { editor = null } }
        )
    }
}

@Composable
private fun ShiftRow(shift: WorkShift, onEdit: () -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shift.name, style = MaterialTheme.typography.titleMedium)
            Text("${categoryLabel(shift.category)} • ${shift.startTime}–${shift.endTime}")
            Text("Cho phép sớm ${shift.allowEarlyMinutes} phút • Đi trễ ${shift.lateGraceMinutes} phút • Về sớm ${shift.earlyLeaveAllowedMinutes} phút", style = MaterialTheme.typography.bodySmall)
            Text("Nghỉ: ${shift.breakStartTime ?: "-"}–${shift.breakEndTime ?: "-"} • ${if (shift.countsOvertime) "Có tính tăng ca" else "Không tính tăng ca"}", style = MaterialTheme.typography.bodySmall)
            Text("Áp dụng từ ${shift.effectiveFrom}${shift.effectiveTo?.let { " đến $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onEdit) { Text(if (shift.id.startsWith("weekly_v1_")) "Tạo bản tùy chỉnh" else "Chỉnh sửa") }
        }
    }
}

@Composable
private fun ShiftEditorDialog(
    initial: WorkShift,
    state: MainUiState,
    onDismiss: () -> Unit,
    onSave: (WorkShift) -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var start by remember(initial.id) { mutableStateOf(initial.startTime) }
    var end by remember(initial.id) { mutableStateOf(initial.endTime) }
    var early by remember(initial.id) { mutableStateOf(initial.allowEarlyMinutes.toString()) }
    var late by remember(initial.id) { mutableStateOf(initial.lateGraceMinutes.toString()) }
    var earlyLeave by remember(initial.id) { mutableStateOf(initial.earlyLeaveAllowedMinutes.toString()) }
    var breakStart by remember(initial.id) { mutableStateOf(initial.breakStartTime.orEmpty()) }
    var breakEnd by remember(initial.id) { mutableStateOf(initial.breakEndTime.orEmpty()) }
    var effectiveFrom by remember(initial.id) { mutableStateOf(initial.effectiveFrom) }
    var effectiveTo by remember(initial.id) { mutableStateOf(initial.effectiveTo.orEmpty()) }
    var countsOvertime by remember(initial.id) { mutableStateOf(initial.countsOvertime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Thêm ca làm" else "Chỉnh sửa ca") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Loại ca")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShiftCategory.entries.forEach { item ->
                        FilterChip(
                            selected = category == item.name,
                            onClick = { category = item.name },
                            label = { Text(categoryLabel(item.name)) }
                        )
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Tên ca") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("Giờ bắt đầu (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text("Giờ kết thúc (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(early, { if (it.all(Char::isDigit)) early = it }, label = { Text("Cho phép chấm sớm (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(late, { if (it.all(Char::isDigit)) late = it }, label = { Text("Cho phép đi trễ (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(earlyLeave, { if (it.all(Char::isDigit)) earlyLeave = it }, label = { Text("Cho phép về sớm (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(breakStart, { breakStart = it }, label = { Text("Bắt đầu nghỉ (HH:mm, không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(breakEnd, { breakEnd = it }, label = { Text("Kết thúc nghỉ (HH:mm, không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(effectiveFrom, { effectiveFrom = it }, label = { Text("Ngày áp dụng (yyyy-MM-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(effectiveTo, { effectiveTo = it }, label = { Text("Ngày kết thúc (không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = countsOvertime, onCheckedChange = { countsOvertime = it })
                    Text("Ca này được tính tăng ca")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(initial.copy(
                        name = name.trim(), category = category, startTime = start.trim(), endTime = end.trim(),
                        allowEarlyMinutes = early.toIntOrNull() ?: -1,
                        lateGraceMinutes = late.toIntOrNull() ?: -1,
                        earlyLeaveAllowedMinutes = earlyLeave.toIntOrNull() ?: -1,
                        breakStartTime = breakStart.trim().ifBlank { null },
                        breakEndTime = breakEnd.trim().ifBlank { null },
                        countsOvertime = countsOvertime,
                        effectiveFrom = effectiveFrom.trim(),
                        effectiveTo = effectiveTo.trim().ifBlank { null }
                    ))
                },
                enabled = !state.saving && name.isNotBlank() && effectiveFrom.isNotBlank()
            ) { Text("Lưu ca") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

private fun categoryLabel(category: String): String = when (category) {
    ShiftCategory.MORNING.name -> "Ca sáng"
    ShiftCategory.EVENING.name -> "Ca chiều / tối"
    ShiftCategory.SUPPLEMENTARY.name -> "Ca bổ sung"
    else -> category
}
