package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.ui.AppSpacing
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.domain.weekDates
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import kotlinx.coroutines.delay
import vn.chamcong.iot.model.Attendance
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val adminScheduleZone = ZoneId.of("Asia/Ho_Chi_Minh")

@Composable
fun ScheduleScreen(state: MainUiState, vm: MainViewModel) {
    var monthMode by remember { mutableStateOf(false) }
    var assignment by remember { mutableStateOf<AssignmentTarget?>(null) }
    var bulkAssignment by remember { mutableStateOf(false) }
    var reviewRequest by remember { mutableStateOf<WeeklyScheduleRequest?>(null) }
    val dates = weekDates(state.selectedWeekStart)
    val activeEmployees = state.employees.filter { it.active }
    val attendance = state.attendanceForSummaries
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(60_000L)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Text("Lịch phân ca", style = MaterialTheme.typography.titleLarge)
        WeeklyScheduleWarning(state)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹ Tuần trước") }
            Text("Tuần ${state.selectedWeekStart} – ${dates.last()}", modifier = Modifier.padding(top = AppSpacing.medium))
            TextButton(onClick = { vm.moveWeek(1) }) { Text("Tuần sau ›") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Button(onClick = { vm.selectWeek(LocalDate.now()) }, modifier = Modifier.widthIn(min = 112.dp)) {
                Text("Tuần này", maxLines = 1, softWrap = false)
            }
            Button(onClick = { vm.clearError(); bulkAssignment = true }, enabled = !state.saving, modifier = Modifier.widthIn(min = 176.dp)) {
                Text("Phân cho nhân viên", maxLines = 1, softWrap = false)
            }
            Button(onClick = { vm.clearError(); assignment = AssignmentTarget(null, dates.first(), true) }, modifier = Modifier.widthIn(min = 184.dp)) {
                Text("Phân cho phòng ban", maxLines = 1, softWrap = false)
            }
            Button(onClick = { vm.clearError(); vm.copyPreviousWeek {} }, modifier = Modifier.widthIn(min = 172.dp)) {
                Text("Sao chép tuần trước", maxLines = 1, softWrap = false)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            FilterChip(selected = !monthMode, onClick = { monthMode = false }, label = { Text("Tuần") })
            FilterChip(selected = monthMode, onClick = { monthMode = true }, label = { Text("Tháng") })
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
        WeeklyScheduleRequestReviewSection(state, vm) { reviewRequest = it }
    }
    assignment?.let { target ->
        ScheduleAssignmentDialog(state, target, vm) { assignment = null }
    }
    if (bulkAssignment) WeeklyAssignmentDialog(state, vm) { bulkAssignment = false }
    reviewRequest?.let { request ->
        WeeklyScheduleRequestReviewDialog(request, state.saving, vm) { reviewRequest = null }
    }
}

@Composable
private fun WeeklyScheduleRequestReviewSection(
    state: MainUiState,
    vm: MainViewModel,
    onReview: (WeeklyScheduleRequest) -> Unit
) {
    val weekStart = state.selectedWeekStart
    val weekRequests = state.weeklyScheduleRequests.filter { it.weekStart == weekStart.toString() }
        .sortedWith(compareBy<WeeklyScheduleRequest> { it.status != WeeklyScheduleRequestStatus.PENDING }.thenBy { it.employeeName })
    val activeEmployees = state.employees.filter { it.active }
    val submittedEmployeeIds = weekRequests.map { it.employeeId }.toSet()
    val missingEmployees = activeEmployees.filter { it.id !in submittedEmployeeIds }
    val pending = weekRequests.filter { it.status == WeeklyScheduleRequestStatus.PENDING }
    val saturdayDeadline = weekStart.minusDays(2).atTime(17, 0).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    val overdue = java.time.Instant.now().isAfter(saturdayDeadline)
    val shiftsById = state.shifts.associateBy(WorkShift::id)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Đăng ký lịch tuần • ${weekStart}", style = MaterialTheme.typography.titleMedium)
            if (overdue && pending.isNotEmpty()) {
                Text("Đã quá hạn duyệt thứ Bảy 17:00. Các đơn vẫn đang chờ xử lý.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Hạn nhân viên gửi: thứ Bảy 12:00 • Admin duyệt trước 17:00.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (missingEmployees.isNotEmpty()) {
                Text(
                    "Chưa đăng ký: ${missingEmployees.joinToString { it.fullName }}",
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (activeEmployees.isNotEmpty()) {
                Text("Tất cả nhân viên đang hoạt động đã gửi đăng ký.", color = MaterialTheme.colorScheme.primary)
            }
            val shiftCounts = buildMap<String, Int> {
                weekRequests.filter { it.status != WeeklyScheduleRequestStatus.NEEDS_REVISION }.forEach { request ->
                    request.shiftsByDate.forEach { (date, ids) ->
                        ids.distinct().forEach { id ->
                            val key = "${date}_$id"
                            put(key, (get(key) ?: 0) + 1)
                        }
                    }
                }
            }
            if (shiftCounts.isNotEmpty()) {
                Text("Số người theo ca đã gửi/duyệt:", style = MaterialTheme.typography.labelLarge)
                shiftCounts.toSortedMap().forEach { (key, count) ->
                    val parts = key.split('_')
                    val date = parts.firstOrNull().orEmpty()
                    val shiftId = parts.drop(1).joinToString("_")
                    val shift = shiftsById[shiftId]
                    Text("$date • ${shift?.name ?: shiftId}: $count người", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (pending.isNotEmpty()) {
                Button(
                    onClick = { vm.approveWeeklySchedulesForWeek(weekStart.toString()) },
                    enabled = !state.saving
                ) { Text("Duyệt hàng loạt ${pending.size} đơn đang chờ") }
            }
            if (weekRequests.isEmpty()) {
                Text("Chưa có đăng ký lịch tuần này.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                weekRequests.forEach { request ->
                    val status = when (request.status) {
                        WeeklyScheduleRequestStatus.PENDING -> "Chờ duyệt"
                        WeeklyScheduleRequestStatus.NEEDS_REVISION -> "Cần sửa"
                        WeeklyScheduleRequestStatus.APPROVED -> "Đã duyệt"
                    }
                    Column(Modifier.fillMaxWidth().padding(top = AppSpacing.xSmall), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text("${request.employeeName} • ${request.department.ifBlank { "Chưa có phòng ban" }} — $status", fontWeight = FontWeight.Medium)
                        request.shiftsByDate.toSortedMap().forEach { (date, ids) ->
                            val labels = ids.map { id -> shiftsById[id]?.let { "${it.name} ${it.startTime}–${it.endTime}" } ?: id }
                            Text("$date: ${labels.ifEmpty { listOf("Nghỉ") }.joinToString(" + ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        request.reason.takeIf(String::isNotBlank)?.let { Text("Ghi chú nhân viên: $it", style = MaterialTheme.typography.bodySmall) }
                        request.reviewNote?.takeIf(String::isNotBlank)?.let { Text("Phản hồi: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (request.status == WeeklyScheduleRequestStatus.PENDING) {
                            TextButton(onClick = { onReview(request) }, enabled = !state.saving) { Text("Xử lý đơn") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyScheduleRequestReviewDialog(
    request: WeeklyScheduleRequest,
    saving: Boolean,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var note by remember(request.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xử lý đăng ký lịch tuần") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${request.employeeName} • tuần bắt đầu ${request.weekStart}")
                Text("Duyệt để chuyển các ca đăng ký thành lịch làm việc. Nếu cần sửa, ghi rõ lý do để nhân viên gửi lại.")
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 1000) note = it },
                    label = { Text("Phản hồi / lý do yêu cầu sửa") },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                TextButton(
                    onClick = {
                        vm.reviewWeeklyScheduleRequest(request.id, WeeklyScheduleRequestStatus.NEEDS_REVISION, note) { onDismiss() }
                    },
                    enabled = !saving && note.isNotBlank()
                ) { Text("Yêu cầu sửa") }
                Button(
                    onClick = { vm.reviewWeeklyScheduleRequest(request.id, WeeklyScheduleRequestStatus.APPROVED, note) { onDismiss() } },
                    enabled = !saving
                ) { Text("Duyệt") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Đóng") } }
    )
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
                    val schedule = state.schedules.firstOrNull { it.employeeId == employee.id && it.date == date.toString() }
                    Card(
                        onClick = { onCell(employee, date) },
                        modifier = Modifier
                            .width(92.dp)
                            .padding(AppSpacing.xSmall)
                            .heightIn(min = AppTouchTarget.minimum)
                    ) {
                        Column(Modifier.padding(AppSpacing.small)) {
                            val assignedShifts = schedule?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
                                .mapNotNull { shiftId -> state.shifts.firstOrNull { it.id == shiftId } }
                            val assignedNames = schedule?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
                                .mapNotNull { shiftId -> state.shifts.firstOrNull { it.id == shiftId }?.name?.takeIf(String::isNotBlank) }
                            Text(assignedNames.joinToString(" + ").ifBlank { schedule?.shiftName ?: "Chưa phân" }, style = MaterialTheme.typography.labelSmall)
                            assignedShifts.forEach { shift ->
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
                            val count = state.schedules.count { it.date == date.toString() }
                            Card(
                                onClick = { if (canAssign) onDay(date) },
                                enabled = canAssign,
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
    val existing = state.schedules.firstOrNull { it.employeeId == target.employee?.id && it.date == target.date.toString() }
    val assignableShifts = assignableScheduleShifts(state.shifts)
    var selectedShiftIds by remember(target.date, target.employee?.id, assignableShifts, existing?.shiftIds) {
        val existingIds = existing?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
            .filter { id -> assignableShifts.any { it.id == id } }
        mutableStateOf(existingIds.ifEmpty { listOfNotNull(assignableShifts.firstOrNull()?.id) })
    }
    var selectedDepartment by remember(target.date, target.employee?.id) { mutableStateOf(state.employees.map { it.department }.firstOrNull { it.isNotBlank() }.orEmpty()) }
    var selectedEmployee by remember(target.date, target.employee?.id) { mutableStateOf(target.employee ?: state.employees.firstOrNull { it.active }) }
    var overrideHours by remember(target.date, target.employee?.id) { mutableStateOf(existing?.workedHoursOverride?.toString().orEmpty()) }
    var adjustmentNote by remember(target.date, target.employee?.id) { mutableStateOf(existing?.adjustmentNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.departmentMode) "Phân ca cho phòng ban" else "Phân ca cho nhân viên") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text(if (target.departmentMode) "Ngày: ${target.date}" else "${target.employee?.code} • ${target.employee?.fullName}\nNgày: ${target.date}")
                if (target.departmentMode) {
                    Text("Phòng ban")
                    state.employees.map { it.department }.filter(String::isNotBlank).distinct().forEach { department ->
                        FilterChip(selected = selectedDepartment == department, onClick = { selectedDepartment = department }, label = { Text(department) })
                    }
                } else if (target.employee == null) {
                    Text("Nhân viên")
                    state.employees.filter { it.active }.forEach { candidate ->
                        FilterChip(
                            selected = selectedEmployee?.id == candidate.id,
                            onClick = { selectedEmployee = candidate },
                            label = { Text("${candidate.code} • ${candidate.fullName}") }
                        )
                    }
                }
                Text("Ca (chọn tối đa một ca sáng và một ca chiều)")
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
                                onClick = {
                                    selectedShiftIds = if (target.departmentMode) listOf(shift.id) else if (shift.id in selectedShiftIds) {
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
                    androidx.compose.material3.OutlinedTextField(
                        value = overrideHours,
                        onValueChange = { value ->
                            val normalized = value.replace(',', '.')
                            if (normalized.all { it.isDigit() || it == '.' } && normalized.count { it == '.' } <= 1) overrideHours = normalized
                        },
                        label = { Text("Điều chỉnh giờ làm (không bắt buộc)") },
                        supportingText = { Text("Dùng khi quên chấm/mất mạng; tối đa 24 giờ") },
                        singleLine = true
                    )
                    if (overrideHours.isNotBlank()) {
                        androidx.compose.material3.OutlinedTextField(
                            value = adjustmentNote,
                            onValueChange = { adjustmentNote = it },
                            label = { Text("Lý do điều chỉnh") },
                            singleLine = true
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
                    vm.assignShiftToDepartment(selectedDepartment, listOf(target.date.toString()), shift, 0) { onDismiss() }
                } else if (selectedEmployee != null) {
                    vm.assignShift(WorkSchedule(employeeId = selectedEmployee!!.id, employeeName = selectedEmployee!!.fullName, department = selectedEmployee!!.department, shiftId = shift.id, shiftIds = selectedShifts.map { it.id }, shiftName = selectedShifts.joinToString(" + ") { it.name }, date = target.date.toString(), overtimeHours = 0, workedHoursOverride = overrideHours.replace(',', '.').toDoubleOrNull(), adjustmentNote = adjustmentNote.trim())) { onDismiss() }
                }
            }, enabled = !state.saving && selectedShiftIds.isNotEmpty() && (target.departmentMode && selectedDepartment.isNotBlank() || !target.departmentMode && selectedEmployee != null && (overrideHours.isBlank() || overrideHours.replace(',', '.').toDoubleOrNull()?.let { it in 0.0..24.0 } == true && adjustmentNote.isNotBlank()))) { Text("Lưu phân ca") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}
