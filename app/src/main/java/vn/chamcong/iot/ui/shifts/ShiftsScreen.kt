package vn.chamcong.iot.ui.shifts

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
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
import vn.chamcong.iot.domain.defaultShiftTemplates
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate

@Composable
fun ShiftsScreen(state: MainUiState, vm: MainViewModel) {
    var editor by remember { mutableStateOf<WorkShift?>(null) }
    var showOvertimeAssignment by remember { mutableStateOf(false) }
    val defaults = remember { defaultShiftTemplates().map { it.resolve() } }
    val displayedShifts = defaults.map { standard -> state.shifts.firstOrNull { it.id == standard.id } ?: standard } +
        state.shifts.filter { it.category == ShiftCategory.SUPPLEMENTARY.name && it.active }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Text("Quản lý ca làm", style = MaterialTheme.typography.titleLarge)
        Text("Ca sáng và ca chiều có sẵn. Chỉ thêm ca tăng ca cho nhân viên khi cần; các ca cũ vẫn được giữ trong lịch sử.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            vm.clearError()
            showOvertimeAssignment = true
        }, enabled = !state.saving) { Text("Thêm ca tăng ca") }
        displayedShifts.forEach { shift ->
                ShiftRow(shift, state.saving) {
                    vm.clearError()
                    if (shift.category == ShiftCategory.SUPPLEMENTARY.name) editor = shift
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
    if (showOvertimeAssignment) {
        OvertimeAssignmentDialog(state, vm,
            onDismiss = { showOvertimeAssignment = false })
    }
}

@Composable
private fun OvertimeAssignmentDialog(state: MainUiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val employees = state.operationalEmployees.filter { it.active }.sortedBy { it.fullName }
    var employeeId by remember { mutableStateOf("") }
    var workDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    val date = runCatching { LocalDate.parse(workDate) }.getOrNull()
    val alreadyAssigned = state.overtimeRequests.any { it.employeeId == employeeId && it.workDate == workDate }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Phân ca tăng ca cho nhân viên") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Ca tăng ca cố định 18:00–22:00. Chọn nhân viên và ngày làm việc.")
                employees.forEach { employee ->
                    FilterChip(selected = employeeId == employee.id, onClick = { employeeId = employee.id }, enabled = !state.saving,
                        label = { Text("${employee.code} • ${employee.fullName}") })
                }
                if (employees.isEmpty()) Text("Chưa có nhân viên đang làm")
                OutlinedTextField(workDate, { workDate = it }, Modifier.fillMaxWidth(),
                    label = { Text("Ngày tăng ca (yyyy-MM-dd)") }, singleLine = true, enabled = !state.saving,
                    isError = date == null || date.dayOfWeek.value == 7)
                if (alreadyAssigned) Text("Nhân viên đã có ca tăng ca ngày này", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(reason, { reason = it.take(500) }, Modifier.fillMaxWidth(),
                    label = { Text("Lý do phân ca") }, enabled = !state.saving)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !state.saving && employees.any { it.id == employeeId } && date != null &&
                date.dayOfWeek.value in 1..6 && reason.isNotBlank() && !alreadyAssigned,
                onClick = { vm.assignOvertimeToEmployee(employeeId, workDate, reason) { onDismiss() } }) {
                Text("Phân ca")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Composable
private fun ShiftRow(shift: WorkShift, saving: Boolean, onEdit: () -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(shift.name, style = MaterialTheme.typography.titleMedium)
            Text("${shiftCategoryLabel(shift.category)} • ${shift.startTime}–${shift.endTime}")
            Text("Cho phép sớm ${shift.allowEarlyMinutes} phút • Đi trễ ${shift.lateGraceMinutes} phút • Về sớm ${shift.earlyLeaveAllowedMinutes} phút", style = MaterialTheme.typography.bodySmall)
            Text("Nghỉ: ${shift.breakStartTime ?: "-"}–${shift.breakEndTime ?: "-"} • ${if (shift.countsOvertime) "Có tính tăng ca" else "Không tính tăng ca"}", style = MaterialTheme.typography.bodySmall)
            Text(if (shift.id.startsWith("weekly_v1_")) "Ca mặc định" else
                "Áp dụng từ ${shift.effectiveFrom}${shift.effectiveTo?.let { " đến $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall)
            if (shift.category == ShiftCategory.SUPPLEMENTARY.name) {
                TextButton(onClick = onEdit, enabled = !saving) { Text("Chỉnh sửa") }
            }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Loại ca")
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    listOf(ShiftCategory.SUPPLEMENTARY).forEach { item ->
                        FilterChip(
                            selected = category == item.name,
                            onClick = {
                                category = item.name
                                name = shiftNameForCategoryChange(name, item.name)
                            },
                            label = { Text(shiftCategoryLabel(item.name)) },
                            enabled = !state.saving
                        )
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Tên ca") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(start, { start = it }, label = { Text("Giờ bắt đầu (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(
                    end,
                    { end = it },
                    label = { Text("Giờ kết thúc (HH:mm)") },
                    supportingText = { Text("Giờ kết thúc phải sau giờ bắt đầu; ca không qua ngày") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving
                )
                OutlinedTextField(early, { if (it.all(Char::isDigit)) early = it }, label = { Text("Cho phép chấm sớm (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(late, { if (it.all(Char::isDigit)) late = it }, label = { Text("Cho phép đi trễ (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(earlyLeave, { if (it.all(Char::isDigit)) earlyLeave = it }, label = { Text("Cho phép về sớm (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(breakStart, { breakStart = it }, label = { Text("Bắt đầu nghỉ (HH:mm, không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(breakEnd, { breakEnd = it }, label = { Text("Kết thúc nghỉ (HH:mm, không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(effectiveFrom, { effectiveFrom = it }, label = { Text("Ngày áp dụng (yyyy-MM-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                OutlinedTextField(effectiveTo, { effectiveTo = it }, label = { Text("Ngày kết thúc (không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = countsOvertime, onCheckedChange = { countsOvertime = it }, enabled = !state.saving)
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ShiftsScreenPreview() {
    PreviewStateScreen { state, vm -> ShiftsScreen(state, vm) }
}
