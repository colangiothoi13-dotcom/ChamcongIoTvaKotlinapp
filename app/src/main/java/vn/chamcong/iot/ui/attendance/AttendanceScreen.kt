package vn.chamcong.iot.ui.attendance

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.AttendanceList
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import com.google.firebase.Timestamp
import java.time.LocalDate
import java.util.Date
import vn.chamcong.iot.domain.assignAttendanceScheduleDates
import vn.chamcong.iot.domain.employeeDaySummary
import vn.chamcong.iot.domain.parseAttendanceDateRange
import vn.chamcong.iot.model.Attendance

@Composable
fun AttendanceScreen(state: MainUiState, vm: MainViewModel) {
    var target by remember { mutableStateOf<AttendanceAdjustmentTarget?>(null) }
    var offScheduleTarget by remember { mutableStateOf<Attendance?>(null) }
    var employeeMenu by remember { mutableStateOf(false) }
    var departmentMenu by remember { mutableStateOf(false) }
    val statuses = listOf("Tất cả" to null, "Đúng giờ" to "NORMAL", "Đi trễ" to "LATE", "Về sớm" to "EARLY_LEAVE")
    val types = listOf("Tất cả loại" to null, "Vào ca" to "CHECK_IN", "Ra ca" to "CHECK_OUT")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        AttendanceDateRangeControls(state, vm)
        if (state.attendanceDatePreset == "SINGLE" || state.attendanceDateFilter.isNotBlank()) {
        OutlinedTextField(
            value = state.attendanceDateFilter,
            onValueChange = vm::setAttendanceDateFilter,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ng\u00e0y l\u1ecdc (yyyy-MM-dd)") },
            singleLine = true,
            isError = state.attendanceDateFilter.isNotBlank() && runCatching { LocalDate.parse(state.attendanceDateFilter.trim()) }.isFailure,
            supportingText = {
                if (state.attendanceDateFilter.isNotBlank() && runCatching { LocalDate.parse(state.attendanceDateFilter.trim()) }.isFailure) {
                    Text("Nh\u1eadp ng\u00e0y theo d\u1ea1ng yyyy-MM-dd")
                }
            }
        )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            androidx.compose.foundation.layout.Box {
                FilterChip(
                    selected = state.attendanceDepartmentFilter != null,
                    onClick = { departmentMenu = true },
                    label = { Text(state.attendanceDepartmentFilter ?: "T\u1ea5t c\u1ea3 ph\u00f2ng ban") }
                )
                DropdownMenu(expanded = departmentMenu, onDismissRequest = { departmentMenu = false }) {
                    DropdownMenuItem(text = { Text("T\u1ea5t c\u1ea3 ph\u00f2ng ban") }, onClick = {
                        vm.setAttendanceDepartmentFilter(null)
                        departmentMenu = false
                    })
                    state.employees.map { it.department.trim() }.filter(String::isNotBlank).distinct().sorted().forEach { department ->
                        DropdownMenuItem(text = { Text(department) }, onClick = {
                            vm.setAttendanceDepartmentFilter(department)
                            departmentMenu = false
                        })
                    }
                }
            }
            androidx.compose.foundation.layout.Box {
                val employeeName = state.employees.firstOrNull { it.id == state.attendanceEmployeeFilter }?.fullName
                FilterChip(
                    selected = employeeName != null,
                    onClick = { employeeMenu = true },
                    label = { Text(employeeName ?: "T\u1ea5t c\u1ea3 nh\u00e2n vi\u00ean") }
                )
                DropdownMenu(expanded = employeeMenu, onDismissRequest = { employeeMenu = false }) {
                    DropdownMenuItem(text = { Text("T\u1ea5t c\u1ea3 nh\u00e2n vi\u00ean") }, onClick = {
                        vm.setAttendanceEmployeeFilter(null)
                        employeeMenu = false
                    })
                    state.employees.sortedBy { it.fullName }.forEach { employee ->
                        DropdownMenuItem(text = { Text(employee.fullName.ifBlank { employee.code }) }, onClick = {
                            vm.setAttendanceEmployeeFilter(employee.id)
                            employeeMenu = false
                        })
                    }
                }
            }
        }
        Text("Lịch sử chấm công", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            statuses.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceStatusFilter == value,
                    onClick = { vm.setAttendanceFilters(value, state.attendanceTypeFilter) },
                    label = { Text(label) }
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            types.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceTypeFilter == value,
                    onClick = { vm.setAttendanceFilters(state.attendanceStatusFilter, value) },
                    label = { Text(label) }
                )
            }
        }
        val unresolvedReviews = state.offScheduleAttendanceReviews.filter { it.status in setOf("PENDING", "FAILED") }
        if (unresolvedReviews.isNotEmpty()) {
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                    Text("Xử lý chấm ngoài lịch", style = MaterialTheme.typography.titleSmall)
                    unresolvedReviews.take(5).forEach { review ->
                        val stateLabel = if (review.status == "FAILED") "Thất bại" else "Đang xử lý"
                        Text(
                            "$stateLabel • ${review.employeeName} • ${review.scheduleDate} • ${review.shiftName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        review.failureReason?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text("${state.visibleAttendance.size} lượt chấm", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = AppSpacing.xSmall))
        AttendanceList(state.visibleAttendance, Modifier.weight(1f),
            adjustmentEnabled = !state.saving,
            onAdjust = if (vm.hasAdminAccess()) { row ->
                vm.clearError()
                target = attendanceAdjustmentTarget(row, state)
            } else null,
            onReviewUnscheduled = if (vm.hasAdminAccess()) { row ->
                vm.clearError()
                offScheduleTarget = row
            } else null)
    }
    target?.let { selected ->
        AttendanceAdjustmentDialog(selected, state,
            onDismiss = { if (!state.saving) target = null },
            onSubmit = { adjustment ->
                if (vm.hasAdminAccess() && !state.saving) vm.adjustAttendance(adjustment) { target = null }
            })
    }
    offScheduleTarget?.let { selected ->
        OffScheduleReviewDialog(
            row = selected,
            attendance = state.attendance,
            shifts = state.shifts,
            busy = state.saving,
            error = state.error,
            onDismiss = { if (!state.saving) offScheduleTarget = null },
            onSubmit = { shiftId, decision, reason ->
                if (vm.hasAdminAccess() && !state.saving) vm.reviewOffScheduleAttendance(
                    employeeId = selected.employeeId,
                    employeeName = selected.employeeName,
                    scheduleDate = selected.timestamp.toDate().toInstant().atZone(attendanceZone).toLocalDate().toString(),
                    shiftId = shiftId,
                    decision = decision,
                    reason = reason
                ) { offScheduleTarget = null }
            }
        )
    }
}

@Composable
private fun AttendanceDateRangeControls(state: MainUiState, vm: MainViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        listOf(
            "Tất cả" to null,
            "Hôm nay" to "TODAY",
            "Hôm qua" to "YESTERDAY",
            "Tuần này" to "THIS_WEEK",
            "Tháng này" to "THIS_MONTH",
            "Ngày cụ thể" to "SINGLE",
            "Khoảng tùy chọn" to "CUSTOM"
        ).forEach { (label, preset) ->
            FilterChip(
                selected = state.attendanceDatePreset == preset,
                onClick = { vm.setAttendanceDatePreset(preset) },
                label = { Text(label) }
            )
        }
    }
    if (state.attendanceDatePreset == "CUSTOM") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            OutlinedTextField(
                value = state.attendanceRangeStart,
                onValueChange = vm::setAttendanceRangeStart,
                modifier = Modifier.weight(1f),
                label = { Text("Từ ngày (yyyy-MM-dd)") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.attendanceRangeEnd,
                onValueChange = vm::setAttendanceRangeEnd,
                modifier = Modifier.weight(1f),
                label = { Text("Đến ngày (yyyy-MM-dd)") },
                singleLine = true
            )
        }
        if (parseAttendanceDateRange(state.attendanceRangeStart, state.attendanceRangeEnd) == null) {
            Text(
                "Khoảng ngày không hợp lệ hoặc chưa đủ hai đầu mút",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

internal fun attendanceAdjustmentTarget(row: Attendance, state: MainUiState): AttendanceAdjustmentTarget? {
    val date = attendanceAdjustmentDate(row)?.let(LocalDate::parse) ?: return null
    if (row.employeeId.isBlank()) return null
    val schedule = state.schedules.firstOrNull { it.employeeId == row.employeeId && it.date == date.toString() }
    val shift = state.shifts.firstOrNull { it.id == (row.shiftId ?: schedule?.shiftId) }
    val summary = employeeDaySummary(
        employeeId = row.employeeId, date = date,
        attendance = assignAttendanceScheduleDates(state.attendance, state.schedules, state.shifts, attendanceZone),
        schedule = schedule, shift = shift, approvedLeave = false, zoneId = attendanceZone,
        adjustments = state.attendanceAdjustments
    )
    return AttendanceAdjustmentTarget(
        employeeId = row.employeeId,
        employeeName = row.employeeName.ifBlank {
            state.employees.firstOrNull { it.id == row.employeeId }?.fullName?.takeIf(String::isNotBlank) ?: row.employeeId
        },
        scheduleDate = date.toString(),
        currentCheckIn = summary.checkIn?.let { Timestamp(Date.from(it)) },
        currentCheckOut = summary.checkOut?.let { Timestamp(Date.from(it)) },
        currentWorkedHours = summary.workedHours
    )
}
