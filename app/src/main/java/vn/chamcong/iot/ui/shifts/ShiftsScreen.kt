package vn.chamcong.iot.ui.shifts

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import vn.chamcong.iot.ui.setSupplementaryShiftActive
import java.time.LocalDate

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ShiftsScreen(state: MainUiState, vm: MainViewModel) {
    var editor by remember { mutableStateOf<WorkShift?>(null) }
    var showOvertimeAssignment by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var toggleCandidate by remember { mutableStateOf<WorkShift?>(null) }
    val defaults = remember { defaultShiftTemplates().map { it.resolve() } }
    val displayedShifts = defaults.map { standard -> state.shifts.firstOrNull { it.id == standard.id } ?: standard } +
        state.shifts.filter { it.category == ShiftCategory.SUPPLEMENTARY.name }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
        AdminPageHeader("Ca làm", "Thiết lập thời gian và quy tắc từng ca.")
        }
        item {
        AdminSectionCard {
        Text("Ca làm & tăng ca", style = MaterialTheme.typography.titleMedium)
        Text("Ca chính mặc định 08:00–12:00 và 13:00–17:00; tăng ca cố định 18:00–22:00.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        Button(onClick = {
            vm.clearError()
            showOvertimeAssignment = true
        }, enabled = !state.saving, shape = MaterialTheme.shapes.small) { Text("Phân tăng ca") }
        OutlinedButton(onClick = { vm.clearError(); editor = WorkShift(name = "Ca bổ sung", category = ShiftCategory.SUPPLEMENTARY.name,
            startTime = "18:00", endTime = "22:00", missingCheckOutGraceMinutes = 120, countsOvertime = true,
            effectiveFrom = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString()) }, enabled = !state.saving, shape = MaterialTheme.shapes.small) {
            Text("Tạo ca bổ sung")
        }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Tìm tên ca làm") }, singleLine = true)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        }
        items(displayedShifts.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }, key = { it.id }) { shift ->
                ShiftRow(shift, state.saving, onToggle = { vm.clearError(); toggleCandidate = shift }) {
                    vm.clearError()
                    if (shift.category == ShiftCategory.SUPPLEMENTARY.name) editor = shift
                }
        }
        item { Text("Lịch tăng ca đã phân", style = MaterialTheme.typography.titleMedium) }
        val overtime = state.overtimeRequests.filter { it.status == "APPROVED" }.sortedByDescending { it.workDate }
        if (overtime.isEmpty()) item { AdminSectionCard { Text("Chưa có ca tăng ca đã duyệt.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(overtime, key = { "overtime:${it.id}" }) { request ->
            AdminSectionCard {
                Text(request.employeeName, style = MaterialTheme.typography.titleMedium)
                Text("${request.workDate} · 18:00–22:00")
                Text("Đã duyệt", color = MaterialTheme.colorScheme.primary)
                if (request.reason.isNotBlank()) Text(request.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    toggleCandidate?.let { shift ->
        AlertDialog(
            onDismissRequest = { if (!state.saving) toggleCandidate = null },
            title = { Text(if (shift.active) "Ngừng ca bổ sung" else "Kích hoạt ca bổ sung") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Cấu hình ${shift.name} sẽ đổi trạng thái hoạt động. Lịch sử công và lương được giữ.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                vm.setSupplementaryShiftActive(shift, !shift.active) { toggleCandidate = null }
            }, enabled = !state.saving, shape = MaterialTheme.shapes.small) { Text("Xác nhận") } },
            dismissButton = { TextButton(onClick = { toggleCandidate = null }, enabled = !state.saving) { Text("Hủy") } }
        )
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
        title = { Text("Phân ca tăng ca") },
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
                    label = { Text("Lý do phân tăng ca") }, enabled = !state.saving)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !state.saving && employees.any { it.id == employeeId } && date != null &&
                date.dayOfWeek.value in 1..6 && reason.isNotBlank() && !alreadyAssigned,
                onClick = { vm.assignOvertimeToEmployee(employeeId, workDate, reason) { onDismiss() } }) {
                Text("Phân ca 18:00–22:00")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
    }

    @Composable
    private fun ShiftRow(shift: WorkShift, saving: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    AdminSectionCard {
            Text(shift.name, style = MaterialTheme.typography.titleMedium)
            Text("${shiftCategoryLabel(shift.category)} • ${shift.startTime}–${shift.endTime}")
            Text("Cho phép sớm ${shift.allowEarlyMinutes} phút • Đi trễ ${shift.lateGraceMinutes} phút • Về sớm ${shift.earlyLeaveAllowedMinutes} phút", style = MaterialTheme.typography.bodySmall)
            Text("Nghỉ: ${shift.breakStartTime ?: "-"}–${shift.breakEndTime ?: "-"} • ${if (shift.countsOvertime) "Có tính tăng ca" else "Không tính tăng ca"}", style = MaterialTheme.typography.bodySmall)
            Text(if (shift.id.startsWith("weekly_v1_")) "Ca mặc định" else
                "Áp dụng từ ${shift.effectiveFrom}${shift.effectiveTo?.let { " đến $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall)
            if (shift.category == ShiftCategory.SUPPLEMENTARY.name) {
                Text(if (shift.active) "Đang hoạt động" else "Đã ngừng", style = MaterialTheme.typography.bodyMedium, color = if (shift.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    TextButton(onClick = onEdit, enabled = !saving) { Text("Sửa") }
                    TextButton(onClick = onToggle, enabled = !saving) { Text(if (shift.active) "Ngừng" else "Kích hoạt") }
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
    var missingGrace by remember(initial.id) { mutableStateOf(initial.missingCheckOutGraceMinutes.toString()) }
    var breakStart by remember(initial.id) { mutableStateOf(initial.breakStartTime.orEmpty()) }
    var breakEnd by remember(initial.id) { mutableStateOf(initial.breakEndTime.orEmpty()) }
    var effectiveFrom by remember(initial.id) { mutableStateOf(initial.effectiveFrom) }
    var effectiveTo by remember(initial.id) { mutableStateOf(initial.effectiveTo.orEmpty()) }
    var countsOvertime by remember(initial.id) { mutableStateOf(initial.countsOvertime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Tạo cấu hình ca bổ sung" else "Chỉnh cấu hình ca bổ sung") },
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
                OutlinedTextField(missingGrace, { if (it.all(Char::isDigit)) missingGrace = it }, label = { Text("Chờ thiếu chấm ra (phút)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
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
                        missingCheckOutGraceMinutes = missingGrace.toIntOrNull() ?: -1,
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
