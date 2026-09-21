package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.ui.AppSpacing
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
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.domain.*
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus

@Composable
internal fun WeeklyScheduleWarning(state: MainUiState) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = Instant.now(); delay(1000) }
    }
    val weekStart = mondayOfWeek(state.selectedWeekStart)
    val requests = state.weeklyScheduleRequests.filter { it.weekStart == weekStart.toString() }
    val missingCount = state.employees.count { employee -> employee.active && requests.none { it.employeeId == employee.id } }
    val pendingCount = requests.count { it.status == WeeklyScheduleRequestStatus.PENDING }
    val revisionCount = requests.count { it.status == WeeklyScheduleRequestStatus.NEEDS_REVISION }
    val employeeDeadline = weekStart.minusDays(2).atTime(12, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    val adminDeadline = weekStart.minusDays(2).atTime(17, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    val overdueEmployee = now.isAfter(employeeDeadline)
    val overdueAdmin = now.isAfter(adminDeadline)
    Text("Nhân viên gửi trước thứ Bảy ${weekStart.minusDays(2)} 12:00; Admin duyệt trước 17:00 (giờ Việt Nam).", style = MaterialTheme.typography.bodySmall)
    if (missingCount > 0) {
        Text("${if (overdueEmployee) "Quá hạn gửi" else "Chưa đăng ký"}: $missingCount nhân viên.",
            color = if (overdueEmployee) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    } else Text("Tất cả nhân viên đang hoạt động đã gửi đăng ký.", style = MaterialTheme.typography.bodySmall)
    if (pendingCount > 0) {
        Text("Đơn đang chờ Admin duyệt: $pendingCount${if (overdueAdmin) " • ĐÃ QUÁ HẠN" else ""}.",
            color = if (overdueAdmin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (revisionCount > 0) Text("Đơn cần nhân viên chỉnh sửa: $revisionCount.", color = MaterialTheme.colorScheme.error)
    Text("Đơn quá hạn vẫn giữ trạng thái chờ xử lý; hệ thống không tự duyệt.", style = MaterialTheme.typography.bodySmall)
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
    val unavailableEmployees = unavailableWeeklyEmployeeIds(state.employees, employees)
    val selectionValidation = resolved.getOrNull()?.let { shift ->
        runCatching { weeklyAssignmentPayload(state.employees, employees, week, dates, shift, "preview") }
    }
    val validSelection = selectionValidation?.isSuccess == true
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Phân ca tuần cho nhân viên") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${weekDates(week).first()} – ${weekDates(week).last()} (Thứ Hai – Thứ Bảy)")
                Text("Chọn nhân viên (${employees.size})")
                if (unavailableEmployees.isNotEmpty()) {
                    Text("${unavailableEmployees.size} nhân viên đã chọn không còn hoạt động hoặc không còn trong danh sách. Bỏ các lựa chọn này để tiếp tục.",
                        color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !state.saving, onClick = { employees = employees - unavailableEmployees }) {
                        Text("Bỏ nhân viên không còn khả dụng")
                    }
                }
                state.employees.filter { it.active }.forEach { employee ->
                    SelectionRow("${employee.code} • ${employee.fullName}", employee.id in employees, !state.saving) {
                        employees = if (it) employees + employee.id else employees - employee.id
                    }
                }
                if (state.employees.none { it.active }) Text("Chưa có nhân viên đang làm")
                Text("Chọn ngày (${dates.size})")
                weekDates(week).forEachIndexed { index, date ->
                    SelectionRow("Thứ ${index + 2} • $date", date in dates, !state.saving) {
                        dates = if (it) dates + date else dates - date
                    }
                }
                Text("Ca chính")
                Text("Tăng ca 18:00–22:00 do nhân viên gửi đơn và Admin duyệt trong mục đơn từ.")
                templates.forEach { item ->
                    FilterChip(selected = template == item, enabled = !state.saving, onClick = { template = item },
                        label = { Text("${item.name} ${item.startTime?.let { "$it–${item.endTime}" } ?: "(nhập giờ)"}") })
                }
                if (template.startTime == null) {
                    OutlinedTextField(start, { start = it }, enabled = !state.saving, singleLine = true,
                        label = { Text("Giờ bắt đầu (HH:mm)") }, isError = validation != null)
                    OutlinedTextField(end, { end = it }, enabled = !state.saving, singleLine = true,
                        label = { Text("Giờ kết thúc (HH:mm)") }, isError = validation != null)
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Text("${employees.size * dates.size} lịch sẽ được lưu. Lịch cùng nhân viên/ngày sẽ được cập nhật; mỗi ngày chỉ có một ca.")
                selectionValidation?.exceptionOrNull()?.localizedMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
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
    Row(Modifier.fillMaxWidth().heightIn(min = AppTouchTarget.minimum).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = AppSpacing.small))
    }
}
