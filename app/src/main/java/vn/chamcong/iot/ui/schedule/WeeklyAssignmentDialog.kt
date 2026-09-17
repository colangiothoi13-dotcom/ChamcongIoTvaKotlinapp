package vn.chamcong.iot.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vn.chamcong.iot.domain.*
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.Instant
import java.time.LocalDate

@Composable
internal fun WeeklyScheduleWarning(state: MainUiState) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = Instant.now(); delay(1000) }
    }
    val status = weeklyScheduleStatus(state.selectedWeekStart, state.employees, state.schedules, now)
    val deadline = mondayOfWeek(state.selectedWeekStart).minusDays(1)
    Text("Hạn đăng ký: Chủ nhật $deadline, 17:00 (giờ Việt Nam). Admin vẫn có thể cập nhật bất kỳ lúc nào.", style = MaterialTheme.typography.bodySmall)
    if (status.missingEmployeeIds.isNotEmpty()) {
        Text("${if (status.overdue) "Quá hạn" else "Chưa hoàn tất"}: ${status.missingEmployeeIds.size} nhân viên chưa có lịch nào trong tuần.",
            color = MaterialTheme.colorScheme.error)
    } else Text("Mỗi nhân viên đang làm đã có lịch trong tuần.", style = MaterialTheme.typography.bodySmall)
    Text("Kiểm tra các ngày cần làm trong bảng; ngày trống không được coi là ngày nghỉ đã duyệt.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun WeeklyAssignmentDialog(state: MainUiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val week = state.selectedWeekStart
    var employees by remember(week) { mutableStateOf(emptySet<String>()) }
    var dates by remember(week) { mutableStateOf(emptySet<LocalDate>()) }
    val templates = remember { defaultShiftTemplates() }
    var template by remember(week) { mutableStateOf(templates.first()) }
    var start by remember(template) { mutableStateOf("") }
    var end by remember(template) { mutableStateOf("") }
    val resolved = runCatching { template.resolve(start, end) }
    val validation = resolved.exceptionOrNull()?.localizedMessage
    val validSelection = resolved.getOrNull()?.let { shift ->
        runCatching { weeklyAssignmentPayload(state.employees, employees, week, dates, shift, "preview") }.isSuccess
    } == true
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Phân ca tuần cho nhân viên") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${weekDates(week).first()} – ${weekDates(week).last()} (Thứ hai – Chủ nhật)")
                Text("Chọn nhân viên (${employees.size})")
                state.employees.filter { it.active }.forEach { employee ->
                    SelectionRow("${employee.code} • ${employee.fullName}", employee.id in employees, !state.saving) {
                        employees = if (it) employees + employee.id else employees - employee.id
                    }
                }
                if (state.employees.none { it.active }) Text("Chưa có nhân viên đang làm")
                Text("Chọn ngày (${dates.size})")
                weekDates(week).forEachIndexed { index, date ->
                    SelectionRow("${if (index == 6) "Chủ nhật" else "Thứ ${index + 2}"} • $date", date in dates, !state.saving) {
                        dates = if (it) dates + date else dates - date
                    }
                }
                Text("Ca mặc định")
                templates.forEach { item ->
                    FilterChip(selected = template == item, enabled = !state.saving, onClick = { template = item },
                        label = { Text("${item.name} ${item.startTime?.let { "$it–${item.endTime}" } ?: "(nhập giờ)"}") })
                }
                if (template.startTime == null) {
                    OutlinedTextField(start, { start = it }, enabled = !state.saving, singleLine = true,
                        label = { Text("Giờ bắt đầu (HH:mm)") }, isError = validation != null)
                    OutlinedTextField(end, { end = it }, enabled = !state.saving, singleLine = true,
                        label = { Text("Giờ kết thúc (HH:mm)") }, isError = validation != null)
                    Text("Giờ kết thúc sớm hơn giờ bắt đầu: ca qua đêm, kết thúc ngày hôm sau.")
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Text("${employees.size * dates.size} lịch sẽ được lưu. Lịch cùng nhân viên/ngày sẽ được cập nhật; mỗi ngày chỉ có một ca.")
                if (state.saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Đang lưu phân ca…") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = !state.saving && validSelection, onClick = {
            vm.assignWeeklyShift(employees, week, dates, template, start, end, onDismiss)
        }) { Text("Lưu phân ca") } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun SelectionRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
