package vn.chamcong.iot.ui.requests

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.requestStatusLabel
import vn.chamcong.iot.ui.requestTypeLabel
import vn.chamcong.iot.ui.overtime.AdminOvertimeRequestSection
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun RequestsScreen(state: MainUiState, vm: MainViewModel) {
    var rejecting by remember { mutableStateOf<LeaveRequest?>(null) }
    val filters = listOf(null to "Tất cả", RequestStatus.PENDING.name to "Chờ duyệt", RequestStatus.APPROVED.name to "Đã duyệt", RequestStatus.REJECTED.name to "Từ chối", RequestStatus.CANCELLED.name to "Đã hủy")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item { Text("Đơn từ", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                filters.forEach { (value, label) -> FilterChip(selected = state.selectedRequestFilter == value, onClick = { vm.setRequestFilter(value) }, label = { Text(label) }) }
            }
        }
        if (state.visibleLeaveRequests.isEmpty()) item { Text("Chưa có đơn từ trong nhóm này") }
        items(state.visibleLeaveRequests, key = { it.id }) { request ->
            RequestCard(
                request = request,
                state = state,
                onApprove = { vm.reviewRequest(request.id, RequestStatus.APPROVED, "") {} },
                onReject = { rejecting = request }
            )
        }
        item { AdminOvertimeRequestSection(state, vm) }
    }
    rejecting?.let { request ->
        var note by remember(request.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.saving) rejecting = null },
            title = { Text("Từ chối đơn từ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("${request.employeeName.ifBlank { request.employeeId }} • ${request.startDate} – ${request.endDate}")
                    OutlinedTextField(note, { note = it }, label = { Text("Lý do từ chối") }, modifier = Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { vm.reviewRequest(request.id, RequestStatus.REJECTED, note) { rejecting = null } }, enabled = !state.saving && note.isNotBlank()) { Text("Từ chối") }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }, enabled = !state.saving) { Text("Hủy") } }
        )
    }
}

@Composable
private fun RequestCard(request: LeaveRequest, state: MainUiState, onApprove: () -> Unit, onReject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("${request.employeeName.ifBlank { request.employeeId }} • ${requestTypeLabel(request.type)}", style = MaterialTheme.typography.titleMedium)
            Text("${request.startDate} – ${request.endDate} • ${request.reason}")
            if (request.type == "LEAVE") {
                request.leaveShiftsByDate?.toSortedMap()?.forEach { (date, shiftIds) ->
                    val names = shiftIds.map { id -> state.shifts.firstOrNull { it.id == id }?.let { "${it.name} ${it.startTime}–${it.endTime}" } ?: id }
                    Text("Ca nghỉ $date: ${names.joinToString()}", style = MaterialTheme.typography.bodySmall)
                } ?: Text("Phạm vi: tất cả ca được phân trong khoảng ngày (đơn cũ).", style = MaterialTheme.typography.bodySmall)
            }
            request.attachmentUrl?.takeIf(String::isNotBlank)?.let { Text("Tệp đính kèm: $it", style = MaterialTheme.typography.bodySmall) }
            Text("Trạng thái: ${requestStatusLabel(request.status)}")
            if (request.reviewerName != null) Text("Người duyệt: ${request.reviewerName} • ${request.reviewedAt?.let { formatTimestamp(it.toDate().time) } ?: ""}", style = MaterialTheme.typography.bodySmall)
            request.reviewNote?.let { Text("Ghi chú: $it", style = MaterialTheme.typography.bodySmall) }
            if (request.status == RequestStatus.PENDING.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Button(onClick = onApprove, enabled = !state.saving) { Text("Duyệt") }
                    TextButton(onClick = onReject, enabled = !state.saving) { Text("Từ chối") }
                }
            }
        }
    }
}

private fun formatTimestamp(value: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(value)
