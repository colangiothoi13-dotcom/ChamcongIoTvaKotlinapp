package vn.chamcong.iot.ui.schedule

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.domain.weekDates
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.retryAttendanceRange
import vn.chamcong.iot.ui.workitems.WorkItemEditorDialog
import vn.chamcong.iot.ui.removeWorkSchedule
import kotlinx.coroutines.delay
import vn.chamcong.iot.model.Attendance
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val adminScheduleZone = ZoneId.of("Asia/Ho_Chi_Minh")

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ScheduleScreen(state: MainUiState, vm: MainViewModel, initialAssignmentRequest: Int = 0, onAssignmentRequestHandled: () -> Unit = {}) {
    var monthMode by remember { mutableStateOf(false) }
    var assignment by remember { mutableStateOf<AssignmentTarget?>(null) }
    var bulkAssignment by remember { mutableStateOf(false) }
    var workSchedule by remember { mutableStateOf<WorkSchedule?>(null) }
    LaunchedEffect(initialAssignmentRequest) {
        if (initialAssignmentRequest > 0 && vm.hasAdminAccess()) {
            vm.clearError()
            bulkAssignment = true
            onAssignmentRequestHandled()
        }
    }
    val dates = weekDates(state.selectedWeekStart)
    val activeEmployees = state.operationalEmployees.filter { it.active }
    val attendance = state.attendanceForSummaries
    LaunchedEffect(state.selectedWeekStart, monthMode) {
        val month = YearMonth.from(state.selectedWeekStart)
        val start = if (monthMode) month.atDay(1) else dates.first()
        val end = if (monthMode) month.atEndOfMonth() else dates.last()
        vm.loadAttendanceRange(start, end, force = true)
    }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(60_000L)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        AdminPageHeader("Lịch làm việc", "Phân ca và theo dõi lịch làm việc theo tuần.")
        AdminSectionCard {
            Text("Lịch làm việc", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹ Tuần trước") }
                Text("Tuần ${state.selectedWeekStart} – ${dates.last()}", modifier = Modifier.padding(top = AppSpacing.medium))
                TextButton(onClick = { vm.moveWeek(1) }) { Text("Tuần sau ›") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                TextButton(onClick = { vm.selectWeek(LocalDate.now()) }) {
                    Text("Tuần này", maxLines = 1, softWrap = false)
                }
                Button(onClick = { vm.clearError(); bulkAssignment = true }, enabled = !state.saving, shape = MaterialTheme.shapes.small) {
                    Text("Phân ca", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { vm.clearError(); assignment = AssignmentTarget(null, dates.first(), true) }, enabled = !state.saving, shape = MaterialTheme.shapes.small) {
                    Text("Phân ca phòng ban", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { vm.clearError(); vm.copyPreviousWeek {} }, enabled = !state.saving, shape = MaterialTheme.shapes.small) {
                    Text("Sao chép tuần trước", maxLines = 1, softWrap = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                FilterChip(selected = !monthMode, onClick = { monthMode = false }, label = { Text("Tuần") })
                FilterChip(selected = monthMode, onClick = { monthMode = true }, label = { Text("Tháng") })
            }
            if (state.attendanceHistoryLoading) Text("Đang tải lịch và lượt chấm trong khoảng đang xem…")
            state.attendanceHistoryError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.retryAttendanceRange() }) { Text("Tải lại dữ liệu") }
            }
            if (state.attendanceHistoryTruncated) {
                Text("Chưa tải đủ lượt chấm trong khoảng đang xem.", color = MaterialTheme.colorScheme.error)
            }
            if (monthMode) {
                MonthScheduleGrid(state, YearMonth.from(state.selectedWeekStart)) { date ->
                    vm.clearError()
                    assignment = AssignmentTarget(null, date, false)
                }
            } else {
                WeeklyScheduleGrid(state, dates, activeEmployees, attendance, now) { employee, date ->
                    vm.clearError()
                    assignment = AssignmentTarget(employee, date, false)
                }
            }
            Text("Chọn ô lịch để sửa phân ca. Tăng ca 18:00–22:00 được quản lý tại Ca làm hoặc Đơn từ.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Giao công việc theo ca", style = MaterialTheme.typography.titleMedium)
        val period = YearMonth.from(state.selectedWeekStart)
        val rangeStart = if (monthMode) period.atDay(1) else dates.first()
        val rangeEnd = if (monthMode) period.atEndOfMonth() else dates.last()
        val availableSchedules = state.calculationSchedules.filter { schedule ->
            val date = runCatching { LocalDate.parse(schedule.date) }.getOrNull()
            schedule.id.isNotBlank() && date != null && date >= rangeStart && date <= rangeEnd &&
                activeEmployees.any { it.id == schedule.employeeId }
        }.sortedWith(compareBy({ it.date }, { it.employeeName }))
        if (availableSchedules.isEmpty()) Text("Phân ca cho nhân viên trước để giao công việc theo ca.")
        availableSchedules.forEach { schedule ->
            AdminSectionCard {
                Text("${schedule.employeeName} · ${schedule.date} · ${schedule.shiftName}")
                TextButton(onClick = { vm.clearError(); workSchedule = schedule }, enabled = !state.saving) {
                    Text("Giao công việc")
                }
            }
        }
    }
    workSchedule?.let { schedule ->
        WorkItemEditorDialog(state, vm, onDismiss = { workSchedule = null }, initialSchedule = schedule)
    }
    assignment?.let { target ->
        ScheduleAssignmentDialog(state, target, vm) { assignment = null }
    }
    if (bulkAssignment) WeeklyAssignmentDialog(state, vm) { bulkAssignment = false }
}

private data class AssignmentTarget(val employee: vn.chamcong.iot.model.Employee?, val date: LocalDate, val departmentMode: Boolean)

@Composable
private fun WeeklyScheduleGrid(
    state: MainUiState,
    dates: List<LocalDate>,
    employees: List<vn.chamcong.iot.model.Employee>,
    attendance: List<Attendance>,
    now: Instant,
    onCell: (vn.chamcong.iot.model.Employee, LocalDate) -> Unit
) {
    Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        Row {
            Text("Nhân viên", Modifier.width(150.dp).padding(AppSpacing.small))
            dates.forEach { date -> Text("${date.dayOfWeek.name.take(3)}\n${date.dayOfMonth}/${date.monthValue}", Modifier.width(92.dp).padding(AppSpacing.small), style = MaterialTheme.typography.labelSmall) }
        }
        employees.forEach { employee ->
            Row {
                Text("${employee.code}\n${employee.fullName}", Modifier.width(150.dp).padding(AppSpacing.small), style = MaterialTheme.typography.bodySmall)
                dates.forEach { date ->
                    val schedule = state.effectiveSchedules.firstOrNull { it.employeeId == employee.id && it.date == date.toString() }
                    OutlinedCard(
                        onClick = { onCell(employee, date) },
                        enabled = !state.saving,
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .width(92.dp)
                            .padding(AppSpacing.xSmall)
                            .heightIn(min = AppTouchTarget.minimum)
                    ) {
                        Column(Modifier.padding(AppSpacing.small)) {
                            val assignedShifts = schedule?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
                                .mapNotNull { shiftId -> state.calculationShifts.firstOrNull { it.id == shiftId } }
                            val assignedNames = schedule?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
                                .mapNotNull { shiftId -> state.calculationShifts.firstOrNull { it.id == shiftId }?.name?.takeIf(String::isNotBlank) }
                            Text(assignedNames.joinToString(" + ").ifBlank { schedule?.shiftName ?: "Chưa phân" }, style = MaterialTheme.typography.labelSmall)
                            if (!state.attendanceHistoryLoading && state.attendanceHistoryError == null && !state.attendanceHistoryTruncated) assignedShifts.forEach { shift ->
                                val status = scheduleShiftStatus(
                                    employeeId = employee.id,
                                    date = date,
                                    shift = shift,
                                    attendance = attendance,
                                    zoneId = adminScheduleZone,
                                    now = now
                                )
                                Text(
                                    text = status.label,
                                    color = scheduleStatusTextColor(status.tone),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2
                                )
                            }
                            if (schedule != null && schedule.overtimeHours > 0) Text("+${schedule.overtimeHours} giờ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (employees.isEmpty()) Text("Chưa có nhân viên đang làm")
    }
}

@Composable
private fun scheduleStatusTextColor(tone: ScheduleStatusTone) = when (tone) {
    ScheduleStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ScheduleStatusTone.ACTIVE -> MaterialTheme.colorScheme.primary
    ScheduleStatusTone.SUCCESS -> MaterialTheme.colorScheme.secondary
    ScheduleStatusTone.WARNING -> MaterialTheme.colorScheme.tertiary
    ScheduleStatusTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun MonthScheduleGrid(state: MainUiState, month: YearMonth, onDay: (LocalDate) -> Unit) {
    val operationalIds = state.operationalEmployees.mapTo(mutableSetOf()) { it.id }
    val first = month.atDay(1)
    val days = (0 until month.lengthOfMonth()).map { first.plusDays(it.toLong()) }
    val cells: List<LocalDate?> = List(first.dayOfWeek.value - 1) { null } + days
    val weekWidth = AppTouchTarget.minimum * 7f + AppTouchTarget.gap * 6f
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
        Text("Tháng ${month.monthValue}/${month.year}", style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
        ) {
            cells.chunked(7).forEach { week ->
                Row(
                    Modifier.width(weekWidth),
                    horizontalArrangement = Arrangement.spacedBy(AppTouchTarget.gap)
                ) {
                    week.forEach { date ->
                        if (date == null) {
                            androidx.compose.foundation.layout.Spacer(Modifier.size(AppTouchTarget.minimum))
                        } else {
                            val canAssign = date.dayOfWeek != DayOfWeek.SUNDAY
                            val count = state.effectiveSchedules.count { it.date == date.toString() && it.employeeId in operationalIds }
                            OutlinedCard(
                                onClick = { if (canAssign) onDay(date) },
                                enabled = canAssign && !state.saving,
                                shape = MaterialTheme.shapes.small,
                                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.size(AppTouchTarget.minimum)
                            ) {
                                Column(Modifier.padding(AppSpacing.xSmall)) {
                                    Text(date.dayOfMonth.toString(), color = if (canAssign) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (canAssign) "$count ca" else "CN", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                    repeat(7 - week.size) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(AppTouchTarget.minimum))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleAssignmentDialog(state: MainUiState, target: AssignmentTarget, vm: MainViewModel, onDismiss: () -> Unit) {
    var selectedEmployee by remember(target.date, target.employee?.id) { mutableStateOf(target.employee ?: state.operationalEmployees.firstOrNull { it.active }) }
    val existing = if (target.departmentMode) null else state.calculationSchedules.firstOrNull { it.employeeId == selectedEmployee?.id && it.date == target.date.toString() }
    var removingSchedule by remember { mutableStateOf<WorkSchedule?>(null) }
    val assignableShifts = assignableScheduleShifts(state.calculationShifts + vn.chamcong.iot.domain.defaultShiftTemplates().map { it.resolve() })
    var selectedShiftIds by remember(target.date, selectedEmployee?.id, assignableShifts, existing?.shiftIds, existing?.shiftId) {
        val existingIds = existing?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
            .filter { id -> assignableShifts.any { it.id == id } }
        mutableStateOf(existingIds.ifEmpty { listOfNotNull(assignableShifts.firstOrNull()?.id) })
    }
    var selectedDepartment by remember(target.date, target.employee?.id) { mutableStateOf(state.operationalEmployees.map { it.department }.firstOrNull { it.isNotBlank() }.orEmpty()) }
    var overrideHours by remember(target.date, selectedEmployee?.id) { mutableStateOf(existing?.workedHoursOverride?.toString().orEmpty()) }
    var adjustmentNote by remember(target.date, selectedEmployee?.id) { mutableStateOf(existing?.adjustmentNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(if (target.departmentMode) "Phân ca cho phòng ban" else "Phân ca cho nhân viên") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text(if (target.departmentMode || selectedEmployee == null) "Ngày: ${target.date}" else "${selectedEmployee?.code} • ${selectedEmployee?.fullName}\nNgày: ${target.date}")
                if (target.departmentMode) {
                    Text("Phòng ban")
                    state.operationalEmployees.map { it.department }.filter(String::isNotBlank).distinct().forEach { department ->
                        FilterChip(selected = selectedDepartment == department, onClick = { selectedDepartment = department }, label = { Text(department) }, enabled = !state.saving)
                    }
                } else if (target.employee == null) {
                    Text("Nhân viên")
                    state.operationalEmployees.filter { it.active }.forEach { candidate ->
                        FilterChip(
                            selected = selectedEmployee?.id == candidate.id,
                            onClick = { selectedEmployee = candidate },
                            label = { Text("${candidate.code} • ${candidate.fullName}") },
                            enabled = !state.saving
                        )
                    }
                }
                Text("Ca (chọn tối đa một ca sáng và một ca chiều)")
                if (target.departmentMode) {
                    Text("Ca đã phân thuộc loại khác, điều chỉnh giờ và ghi chú sẽ được giữ lại.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (assignableShifts.isEmpty()) {
                    Text("Chưa có ca chính để phân")
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        assignableShifts.forEach { shift ->
                            FilterChip(
                                selected = shift.id in selectedShiftIds,
                                enabled = !state.saving,
                                onClick = {
                                    selectedShiftIds = if (shift.id in selectedShiftIds) {
                                        selectedShiftIds - shift.id
                                    } else {
                                        selectedShiftIds.filterNot { id -> assignableShifts.firstOrNull { it.id == id }?.category == shift.category } + shift.id
                                    }
                                },
                                label = {
                                    Text(
                                        "${scheduleShiftLabel(shift)} • ${shift.startTime}–${shift.endTime}",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                }
                Text("Tăng ca 18:00–22:00 do nhân viên gửi đơn và Admin duyệt trong mục đơn từ.")
                if (!target.departmentMode) {
                    if (existing != null) {
                        Text("Lưu phân ca thay bằng các ca được chọn; giờ điều chỉnh và ghi chú đang có được điền sẵn.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !state.saving, onClick = { vm.clearError(); removingSchedule = existing }) { Text("Bỏ lịch ngày này") }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = overrideHours,
                        onValueChange = { value ->
                            val normalized = value.replace(',', '.')
                            if (normalized.all { it.isDigit() || it == '.' } && normalized.count { it == '.' } <= 1) overrideHours = normalized
                        },
                        label = { Text("Điều chỉnh giờ làm (không bắt buộc)") },
                        supportingText = { Text("Dùng khi quên chấm/mất mạng; tối đa 24 giờ") },
                        singleLine = true,
                        enabled = !state.saving
                    )
                    if (overrideHours.isNotBlank()) {
                        androidx.compose.material3.OutlinedTextField(
                            value = adjustmentNote,
                            onValueChange = { adjustmentNote = it },
                            label = { Text("Lý do điều chỉnh") },
                            singleLine = true,
                            enabled = !state.saving
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val selectedShifts = selectedShiftIds.mapNotNull { id -> assignableShifts.firstOrNull { it.id == id } }
                    .sortedBy(WorkShift::startTime)
                val shift = selectedShifts.firstOrNull() ?: return@Button
                if (target.departmentMode) {
                    vm.assignShiftToDepartment(selectedDepartment, listOf(target.date.toString()), selectedShifts, 0) { onDismiss() }
                } else if (selectedEmployee != null) {
                    vm.assignShift((existing ?: WorkSchedule()).copy(employeeId = selectedEmployee!!.id, employeeName = selectedEmployee!!.fullName, department = selectedEmployee!!.department, shiftId = shift.id, shiftIds = selectedShifts.map { it.id }, shiftName = selectedShifts.joinToString(" + ") { it.name }, date = target.date.toString(), workedHoursOverride = overrideHours.replace(',', '.').toDoubleOrNull(), adjustmentNote = adjustmentNote.trim())) { onDismiss() }
                }
            }, enabled = !state.saving && selectedShiftIds.isNotEmpty() && (target.departmentMode && selectedDepartment.isNotBlank() || !target.departmentMode && selectedEmployee != null && (overrideHours.isBlank() || overrideHours.replace(',', '.').toDoubleOrNull()?.let { it in 0.0..24.0 } == true && adjustmentNote.isNotBlank()))) { Text("Lưu phân ca") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
    removingSchedule?.let { schedule ->
        AlertDialog(onDismissRequest = { if (!state.saving) removingSchedule = null },
            title = { Text("Bỏ lịch làm việc ngày này?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("${schedule.employeeName} • ${schedule.date} • ${schedule.shiftName}")
                    Text("Công đang tính từ lịch có thể thay đổi. Lượt quét gốc và lịch sử lương được giữ.")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(enabled = !state.saving, onClick = {
                vm.removeWorkSchedule(schedule) { removingSchedule = null; onDismiss() }
            }) { Text(if (state.saving) "Đang lưu…" else "Bỏ lịch") } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { removingSchedule = null }) { Text("Hủy") } })
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ScheduleScreenPreview() {
    PreviewStateScreen { state, vm ->
        ScheduleScreen(state.copy(weeklyScheduleRequestsLoadedWeekStart = state.selectedWeekStart), vm)
    }
}
