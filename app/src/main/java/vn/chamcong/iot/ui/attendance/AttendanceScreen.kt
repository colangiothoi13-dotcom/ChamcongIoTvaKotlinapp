package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
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
import vn.chamcong.iot.model.Attendance

@Composable
fun AttendanceScreen(state: MainUiState, vm: MainViewModel) {
    var target by remember { mutableStateOf<AttendanceAdjustmentTarget?>(null) }
    val statuses = listOf("Tất cả" to null, "Đúng giờ" to "NORMAL", "Đi trễ" to "LATE", "Về sớm" to "EARLY_LEAVE")
    val types = listOf("Tất cả loại" to null, "Vào ca" to "CHECK_IN", "Ra ca" to "CHECK_OUT")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lịch sử chấm công", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            statuses.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceStatusFilter == value,
                    onClick = { vm.setAttendanceFilters(value, state.attendanceTypeFilter) },
                    label = { Text(label) }
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { (label, value) ->
                FilterChip(
                    selected = state.attendanceTypeFilter == value,
                    onClick = { vm.setAttendanceFilters(state.attendanceStatusFilter, value) },
                    label = { Text(label) }
                )
            }
        }
        Text("${state.visibleAttendance.size} lượt chấm", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        AttendanceList(state.visibleAttendance, Modifier.weight(1f),
            adjustmentEnabled = !state.saving,
            onAdjust = if (vm.hasAdminAccess()) { row ->
                vm.clearError()
                target = attendanceAdjustmentTarget(row, state)
            } else null)
    }
    target?.let { selected ->
        AttendanceAdjustmentDialog(selected, state,
            onDismiss = { if (!state.saving) target = null },
            onSubmit = { adjustment ->
                if (vm.hasAdminAccess() && !state.saving) vm.adjustAttendance(adjustment) { target = null }
            })
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
