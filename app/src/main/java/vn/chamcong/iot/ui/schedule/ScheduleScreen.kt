package vn.chamcong.iot.ui.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.domain.weekDates
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ScheduleScreen(state: MainUiState, vm: MainViewModel) {
    var monthMode by remember { mutableStateOf(false) }
    var assignment by remember { mutableStateOf<AssignmentTarget?>(null) }
    var bulkAssignment by remember { mutableStateOf(false) }
    val dates = weekDates(state.selectedWeekStart)
    val activeEmployees = state.employees.filter { it.active }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lịch phân ca", style = MaterialTheme.typography.titleLarge)
        WeeklyScheduleWarning(state)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹ Tuần trước") }
            Text("Tuần ${state.selectedWeekStart} – ${dates.last()}", modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { vm.moveWeek(1) }) { Text("Tuần sau ›") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.selectWeek(LocalDate.now()) }) { Text("Tuần này") }
            Button(onClick = { vm.clearError(); bulkAssignment = true }, enabled = !state.saving) { Text("Phân cho nhân viên") }
            Button(onClick = { vm.clearError(); assignment = AssignmentTarget(null, dates.first(), true) }) { Text("Phân cho phòng ban") }
            Button(onClick = { vm.clearError(); vm.copyPreviousWeek {} }) { Text("Sao chép tuần trước") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !monthMode, onClick = { monthMode = false }, label = { Text("Tuần") })
            FilterChip(selected = monthMode, onClick = { monthMode = true }, label = { Text("Tháng") })
        }
        if (monthMode) {
            MonthScheduleGrid(state, YearMonth.from(state.selectedWeekStart)) { date ->
                vm.clearError()
                assignment = AssignmentTarget(null, date, false)
            }
        } else {
            WeeklyScheduleGrid(state, dates, activeEmployees) { employee, date ->
                vm.clearError()
                assignment = AssignmentTarget(employee, date, false)
            }
        }
    }
    assignment?.let { target ->
        ScheduleAssignmentDialog(state, target, vm) { assignment = null }
    }
    if (bulkAssignment) WeeklyAssignmentDialog(state, vm) { bulkAssignment = false }
}

private data class AssignmentTarget(val employee: vn.chamcong.iot.model.Employee?, val date: LocalDate, val departmentMode: Boolean)

@Composable
private fun WeeklyScheduleGrid(state: MainUiState, dates: List<LocalDate>, employees: List<vn.chamcong.iot.model.Employee>, onCell: (vn.chamcong.iot.model.Employee, LocalDate) -> Unit) {
    Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Nhân viên", Modifier.width(150.dp).padding(8.dp))
            dates.forEach { date -> Text("${date.dayOfWeek.name.take(3)}\n${date.dayOfMonth}/${date.monthValue}", Modifier.width(92.dp).padding(8.dp), style = MaterialTheme.typography.labelSmall) }
        }
        employees.forEach { employee ->
            Row {
                Text("${employee.code}\n${employee.fullName}", Modifier.width(150.dp).padding(8.dp), style = MaterialTheme.typography.bodySmall)
                dates.forEach { date ->
                    val schedule = state.schedules.firstOrNull { it.employeeId == employee.id && it.date == date.toString() }
                    Card(onClick = { onCell(employee, date) }, modifier = Modifier.width(92.dp).padding(2.dp)) {
                        Column(Modifier.padding(6.dp)) {
                            Text(schedule?.shiftName ?: "Chưa phân", style = MaterialTheme.typography.labelSmall)
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
private fun MonthScheduleGrid(state: MainUiState, month: YearMonth, onDay: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val days = (0 until month.lengthOfMonth()).map { first.plusDays(it.toLong()) }
    val cells: List<LocalDate?> = List(first.dayOfWeek.value - 1) { null } + days
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tháng ${month.monthValue}/${month.year}", style = MaterialTheme.typography.titleMedium)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    if (date == null) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    } else {
                        val count = state.schedules.count { it.date == date.toString() }
                        Card(onClick = { onDay(date) }, modifier = Modifier.weight(1f)) { Column(Modifier.padding(6.dp)) { Text(date.dayOfMonth.toString()); Text("$count ca", style = MaterialTheme.typography.labelSmall) } }
                    }
                }
                repeat(7 - week.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ScheduleAssignmentDialog(state: MainUiState, target: AssignmentTarget, vm: MainViewModel, onDismiss: () -> Unit) {
    val existing = state.schedules.firstOrNull { it.employeeId == target.employee?.id && it.date == target.date.toString() }
    var selectedShift by remember(target.date, target.employee?.id) { mutableStateOf(state.shifts.firstOrNull { it.id == existing?.shiftId } ?: state.shifts.firstOrNull()) }
    var selectedOvertime by remember(target.date, target.employee?.id) { mutableStateOf(existing?.overtimeHours ?: 0) }
    var selectedDepartment by remember(target.date, target.employee?.id) { mutableStateOf(state.employees.map { it.department }.firstOrNull { it.isNotBlank() }.orEmpty()) }
    var selectedEmployee by remember(target.date, target.employee?.id) { mutableStateOf(target.employee ?: state.employees.firstOrNull { it.active }) }
    var overrideHours by remember(target.date, target.employee?.id) { mutableStateOf(existing?.workedHoursOverride?.toString().orEmpty()) }
    var adjustmentNote by remember(target.date, target.employee?.id) { mutableStateOf(existing?.adjustmentNote.orEmpty()) }
    var menuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.departmentMode) "Phân ca cho phòng ban" else "Phân ca cho nhân viên") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text("Ca")
                OutlinedButton(onClick = { menuExpanded = true }, enabled = state.shifts.isNotEmpty()) { Text(selectedShift?.let(::scheduleShiftLabel) ?: "Chưa có ca") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    state.shifts.forEach { shift -> DropdownMenuItem(text = { Text(scheduleShiftLabel(shift)) }, onClick = { selectedShift = shift; menuExpanded = false }) }
                }
                Text("Tăng ca")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (0..3).forEach { hours -> FilterChip(selected = selectedOvertime == hours, onClick = { selectedOvertime = hours }, label = { Text("$hours giờ") }) }
                }
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
                val shift = selectedShift ?: return@Button
                if (target.departmentMode) {
                    vm.assignShiftToDepartment(selectedDepartment, listOf(target.date.toString()), shift, selectedOvertime) { onDismiss() }
                } else if (selectedEmployee != null) {
                    vm.assignShift(WorkSchedule(employeeId = selectedEmployee!!.id, employeeName = selectedEmployee!!.fullName, department = selectedEmployee!!.department, shiftId = shift.id, shiftName = shift.name, date = target.date.toString(), overtimeHours = selectedOvertime, workedHoursOverride = overrideHours.replace(',', '.').toDoubleOrNull(), adjustmentNote = adjustmentNote.trim())) { onDismiss() }
                }
            }, enabled = !state.saving && selectedShift != null && (target.departmentMode && selectedDepartment.isNotBlank() || !target.departmentMode && selectedEmployee != null && (overrideHours.isBlank() || overrideHours.replace(',', '.').toDoubleOrNull()?.let { it in 0.0..24.0 } == true && adjustmentNote.isNotBlank()))) { Text("Lưu phân ca") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}
