package vn.chamcong.iot.ui.employee

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.domain.scheduledShifts
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.overtime.EmployeeOvertimeRequestSection
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun EmployeeRequestsScreen(state: MainUiState, vm: MainViewModel) {
    var showForm by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(RequestType.LEAVE) }
    var startDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var endDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var requestDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    var proposedCheckIn by remember { mutableStateOf("") }
    var proposedCheckOut by remember { mutableStateOf("") }
    var requestedShiftId by remember { mutableStateOf<String?>(null) }
    var leaveSelection by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var shiftMenuExpanded by remember { mutableStateOf(false) }
    val supportedTypes = listOf(RequestType.LEAVE, RequestType.LATE, RequestType.EARLY_LEAVE, RequestType.ATTENDANCE_ADJUSTMENT, RequestType.REMOTE, RequestType.SHIFT_CHANGE)
    val singleDateRequest = type == RequestType.ATTENDANCE_ADJUSTMENT || type == RequestType.SHIFT_CHANGE
    val validRequestDate = !singleDateRequest || runCatching { LocalDate.parse(requestDate.trim()) }.isSuccess
    val activeChangeShifts = remember(state.shifts) {
        state.shifts.filter { it.active && canAssignScheduleShift(it) }
    }
    val validLeaveRange = runCatching {
        val first = LocalDate.parse(startDate.trim())
        val last = LocalDate.parse(endDate.trim())
        require(!last.isBefore(first) && java.time.temporal.ChronoUnit.DAYS.between(first, last) < 31)
        generateSequence(first) { date -> date.plusDays(1).takeUnless { it.isAfter(last) } }.toList()
    }.getOrNull()
    val schedulesByDate = remember(state.employeeSchedules) { state.employeeSchedules.associateBy { it.date } }
    val shiftsById = remember(state.shifts) { state.shifts.associateBy { it.id } }
    val leaveOptions = validLeaveRange.orEmpty().mapNotNull { date ->
        val schedule = schedulesByDate[date.toString()] ?: return@mapNotNull null
        val assigned = scheduledShifts(schedule, shiftsById)
        if (assigned.isEmpty()) null else date to assigned
    }
    val selectedLeaveScope = leaveOptions.mapNotNull { (date, assigned) ->
        val selected = leaveSelection[date.toString()].orEmpty().intersect(assigned.map { it.id }.toSet())
        date.toString().takeIf { selected.isNotEmpty() }?.let { it to selected.sorted() }
    }.toMap()
    val leaveScopeValid = validLeaveRange != null && selectedLeaveScope.isNotEmpty() &&
        selectedLeaveScope.all { (date, ids) -> ids.all { id -> leaveOptions.firstOrNull { it.first.toString() == date }?.second?.any { it.id == id } == true } }
    val selectedShift = activeChangeShifts.firstOrNull { it.id == requestedShiftId }
    val adjustmentTimesValid =
        (proposedCheckIn.isNotBlank() || proposedCheckOut.isNotBlank()) &&
            isValidOptionalTime(proposedCheckIn) && isValidOptionalTime(proposedCheckOut)
    val formValid = when (type) {
        RequestType.LEAVE -> leaveScopeValid
        RequestType.ATTENDANCE_ADJUSTMENT -> validRequestDate && adjustmentTimesValid
        RequestType.SHIFT_CHANGE -> validRequestDate && selectedShift != null
        else -> true
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đơn từ của tôi", style = MaterialTheme.typography.titleLarge)
                TextButton({ showForm = !showForm }) { Text(if (showForm) "Đóng form" else "Tạo đơn") }
            }
        }
        if (showForm) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("Tạo đơn mới", style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            items(supportedTypes, key = { it.name }) { candidate ->
                                FilterChip(
                                    selected = type == candidate,
                                    onClick = {
                                        if (!singleDateRequest && (candidate == RequestType.SHIFT_CHANGE || candidate == RequestType.ATTENDANCE_ADJUSTMENT)) {
                                            requestDate = startDate
                                        }
                                        type = candidate
                                    },
                                    label = { Text(candidate.toVietnamese()) }
                                )
                            }
                        }
                        if (singleDateRequest) {
                            OutlinedTextField(
                                value = requestDate,
                                onValueChange = { requestDate = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (type == RequestType.SHIFT_CHANGE) "Ngày muốn đổi ca (yyyy-MM-dd)"
                                        else "Ngày cần điều chỉnh (yyyy-MM-dd)"
                                    )
                                },
                                singleLine = true,
                                isError = !validRequestDate,
                                supportingText = { if (!validRequestDate) Text("Nhập ngày hợp lệ theo dạng yyyy-MM-dd") }
                            )
                        } else {
                            OutlinedTextField(startDate, { startDate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ngày bắt đầu (yyyy-MM-dd)") }, singleLine = true)
                            OutlinedTextField(endDate, { endDate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ngày kết thúc (yyyy-MM-dd)") }, singleLine = true)
                        }
                        if (type == RequestType.LEAVE) {
                            Text("Chọn từng ca đã được phân. Chọn tất cả ca trong ngày để xin nghỉ cả ngày; có thể chọn nhiều ngày.", style = MaterialTheme.typography.bodySmall)
                            if (validLeaveRange == null) {
                                Text("Khoảng ngày không hợp lệ hoặc vượt quá 31 ngày.", color = MaterialTheme.colorScheme.error)
                            } else if (leaveOptions.isEmpty()) {
                                Text("Không có lịch ca đã phân trong khoảng ngày này.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                leaveOptions.forEach { (date, assigned) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(date.toString(), style = MaterialTheme.typography.titleSmall)
                                            TextButton(onClick = {
                                                val next = leaveSelection.toMutableMap()
                                                val current = next[date.toString()].orEmpty()
                                                next[date.toString()] = if (current.size == assigned.size) emptySet() else assigned.map { it.id }.toSet()
                                                if (next[date.toString()].isNullOrEmpty()) next.remove(date.toString())
                                                leaveSelection = next
                                            }) {
                                                Text(if (leaveSelection[date.toString()].orEmpty().size == assigned.size) "Bỏ chọn" else "Chọn cả ngày")
                                            }
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                            items(assigned, key = { it.id }) { shift ->
                                                val selected = shift.id in leaveSelection[date.toString()].orEmpty()
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        val next = leaveSelection.toMutableMap()
                                                        val current = next[date.toString()].orEmpty().toMutableSet()
                                                        if (selected) current.remove(shift.id) else current.add(shift.id)
                                                        if (current.isEmpty()) next.remove(date.toString()) else next[date.toString()] = current
                                                        leaveSelection = next
                                                    },
                                                    label = { Text("${shift.name} ${shift.startTime}–${shift.endTime}") }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (validLeaveRange != null && selectedLeaveScope.isEmpty()) {
                                Text("Chọn ít nhất một ca nghỉ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (type == RequestType.ATTENDANCE_ADJUSTMENT) {
                            Text("Nhập giờ theo HH:mm; điền ít nhất giờ vào hoặc giờ ra.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = proposedCheckIn,
                                onValueChange = { proposedCheckIn = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Giờ vào đề xuất (HH:mm)") },
                                singleLine = true,
                                isError = !isValidOptionalTime(proposedCheckIn)
                            )
                            OutlinedTextField(
                                value = proposedCheckOut,
                                onValueChange = { proposedCheckOut = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Giờ ra đề xuất (HH:mm)") },
                                singleLine = true,
                                isError = !isValidOptionalTime(proposedCheckOut)
                            )
                            if (!adjustmentTimesValid) {
                                Text(
                                    "Nhập ít nhất một giờ hợp lệ theo dạng HH:mm.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (type == RequestType.SHIFT_CHANGE) {
                            if (activeChangeShifts.isEmpty()) {
                                Text(
                                    "Hiện chưa có ca làm đang hoạt động để chọn.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Box {
                                    OutlinedButton(
                                        onClick = { shiftMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(selectedShift?.let(::shiftChoiceLabel) ?: "Chọn ca muốn đổi sang")
                                    }
                                    DropdownMenu(
                                        expanded = shiftMenuExpanded,
                                        onDismissRequest = { shiftMenuExpanded = false }
                                    ) {
                                        activeChangeShifts.forEach { shift ->
                                            DropdownMenuItem(
                                                text = { Text(shiftChoiceLabel(shift)) },
                                                onClick = {
                                                    requestedShiftId = shift.id
                                                    shiftMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(reason, { reason = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Lý do") }, minLines = 3)
                        Button(
                            onClick = {
                                val requestStartDate = if (singleDateRequest) requestDate.trim() else startDate.trim()
                                val requestEndDate = if (singleDateRequest) requestDate.trim() else endDate.trim()
                                vm.submitEmployeeRequest(
                                    type,
                                    requestStartDate,
                                    requestEndDate,
                                    reason.trim(),
                                    proposedCheckIn.trim().takeIf { type == RequestType.ATTENDANCE_ADJUSTMENT && it.isNotBlank() },
                                    proposedCheckOut.trim().takeIf { type == RequestType.ATTENDANCE_ADJUSTMENT && it.isNotBlank() },
                                    requestedShiftId.takeIf { type == RequestType.SHIFT_CHANGE },
                                    leaveShiftsByDate = selectedLeaveScope.takeIf { type == RequestType.LEAVE }
                                ) {
                                    showForm = false
                                    reason = ""
                                    proposedCheckIn = ""
                                    proposedCheckOut = ""
                                }
                            },
                            enabled = !state.saving && reason.isNotBlank() && formValid
                        ) { Text("Gửi đơn") }
                    }
                }
            }
        }
        item { Text("Lịch sử xử lý", style = MaterialTheme.typography.titleMedium) }
        if (state.employeeRequests.isEmpty()) item { Text("Bạn chưa có đơn từ") }
        items(state.employeeRequests, key = { it.id }) { request -> EmployeeRequestCard(request, state.shifts, vm, state.saving) }
        item { HorizontalDivider() }
        item { EmployeeOvertimeRequestSection(state, vm) }
    }
}

@Composable
private fun EmployeeRequestCard(request: LeaveRequest, shifts: List<WorkShift>, vm: MainViewModel, saving: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(request.type.toVietnamese(), style = MaterialTheme.typography.titleMedium)
            Text("${request.startDate} – ${request.endDate}")
            Text(request.reason)
            Text("Trạng thái: ${request.status.toVietnamese()}")
            if (request.type == RequestType.LEAVE.name) {
                val scope = request.leaveShiftsByDate
                if (scope == null) Text("Phạm vi: tất cả ca được phân trong khoảng ngày (đơn cũ).", style = MaterialTheme.typography.bodySmall)
                else scope.toSortedMap().forEach { (date, ids) ->
                    val names = ids.map { id -> shifts.firstOrNull { it.id == id }?.name ?: id }
                    Text("$date: ${names.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                if (request.status == RequestStatus.PENDING.name) {
                    OutlinedButton(onClick = { vm.cancelEmployeeLeaveRequest(request.id) }, enabled = !saving) {
                        Text("Hủy đơn đang chờ duyệt")
                    }
                }
            }
            request.reviewNote?.takeIf(String::isNotBlank)?.let { Text("Ghi chú: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun RequestType.toVietnamese(): String = when (this) {
    RequestType.LEAVE -> "Nghỉ phép"
    RequestType.LATE -> "Đi muộn"
    RequestType.EARLY_LEAVE -> "Về sớm"
    RequestType.REMOTE -> "Ngoài văn phòng"
    RequestType.ATTENDANCE_ADJUSTMENT -> "Sửa chấm công"
    RequestType.SHIFT_CHANGE -> "Đổi ca"
    RequestType.FINGERPRINT_SUPPORT -> "Hỗ trợ vân tay"
}

private fun isValidOptionalTime(value: String): Boolean {
    val time = value.trim()
    if (time.isEmpty()) return true
    if (!time.matches(Regex("\\d{2}:\\d{2}"))) return false
    return runCatching { LocalTime.parse(time) }.isSuccess
}

private fun shiftChoiceLabel(shift: WorkShift): String =
    "${shift.name} • ${shift.startTime}–${shift.endTime}"

private fun String.toVietnamese(): String = when (this) {
    RequestStatus.PENDING.name -> "Chờ duyệt"
    RequestStatus.APPROVED.name -> "Đã duyệt"
    RequestStatus.REJECTED.name -> "Từ chối"
    RequestStatus.CANCELLED.name -> "Đã hủy"
    else -> this
}
