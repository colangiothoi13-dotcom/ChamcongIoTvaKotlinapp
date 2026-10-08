package vn.chamcong.iot.ui.requests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.visibleOutsideRetiredList
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.requestStatusLabel
import vn.chamcong.iot.ui.requestTypeLabel
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun AdminOtherRequestsList(state: MainUiState, vm: MainViewModel, header: @Composable () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val employeesById = state.employees.associateBy { it.id }
    val hiddenRetiredIds = state.employees.filterNot { it.visibleOutsideRetiredList() }.mapTo(mutableSetOf()) { it.id }
    val allRequests = state.leaveRequests.filter { it.employeeId !in hiddenRetiredIds }
    val visibleRequests = allRequests.filter { request ->
        val employee = employeesById[request.employeeId]
        (state.selectedRequestFilter.isNullOrBlank() || request.status == state.selectedRequestFilter) &&
            adminRequestMatchesQuery(search, request.employeeName, employee?.fullName.orEmpty(),
                request.employeeId, employee?.code.orEmpty(), request.department, employee?.department.orEmpty())
    }.sortedWith(compareBy<LeaveRequest> { it.status != RequestStatus.PENDING.name }
        .thenByDescending { it.createdAt.seconds }.thenBy { it.id })
    val pendingCount = allRequests.count { it.status == RequestStatus.PENDING.name }
    val filters = listOf(null to "Tất cả", RequestStatus.PENDING.name to "Chờ duyệt",
        RequestStatus.APPROVED.name to "Đã duyệt", RequestStatus.REJECTED.name to "Từ chối",
        RequestStatus.CANCELLED.name to "Đã hủy")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        item(key = "other_header") { header() }
        item(key = "other_summary") {
            Text("${allRequests.size} đơn • $pendingCount chờ duyệt", style = MaterialTheme.typography.titleSmall)
        }
        item(key = "other_search") { AdminRequestSearchField(search) { search = it } }
        item(key = "other_filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                filters.forEach { (value, label) ->
                    FilterChip(
                        selected = state.selectedRequestFilter == value,
                        onClick = { vm.setRequestFilter(value) },
                        label = { Text(label) },
                        enabled = !state.saving
                    )
                }
            }
        }
        item(key = "other_result_count") {
            Text("${visibleRequests.size} kết quả • Chạm đơn để xem chi tiết",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visibleRequests.isEmpty()) {
            item(key = "other_empty") {
                Text(if (allRequests.isEmpty()) "Chưa có đơn từ" else "Không có đơn phù hợp với tìm kiếm và bộ lọc.",
                    modifier = Modifier.padding(vertical = AppSpacing.medium))
            }
        }
        items(visibleRequests, key = { "other_request_${it.id}" }) { request ->
            val employee = employeesById[request.employeeId]
            val employeeCode = employee?.code?.takeIf(String::isNotBlank) ?: request.employeeId
            val department = request.department.ifBlank { employee?.department.orEmpty() }.ifBlank { "Chưa có phòng ban" }
            val dateRange = if (request.startDate == request.endDate) adminRequestDateLabel(request.startDate)
                else "${adminRequestDateLabel(request.startDate)} – ${adminRequestDateLabel(request.endDate)}"
            AdminRequestSummaryCard(
                title = request.employeeName.ifBlank { employee?.fullName.orEmpty().ifBlank { employeeCode } },
                subtitle = "$employeeCode • $department",
                summary = "${requestTypeLabel(request.type)} • $dateRange",
                status = requestStatusLabel(request.status),
                tone = when (request.status) {
                    RequestStatus.PENDING.name -> AdminRequestStatusTone.PENDING
                    RequestStatus.APPROVED.name -> AdminRequestStatusTone.APPROVED
                    RequestStatus.REJECTED.name -> AdminRequestStatusTone.REVISION
                    else -> AdminRequestStatusTone.NEUTRAL
                },
                onClick = { vm.clearError(); selectedId = request.id },
                enabled = !state.saving
            )
        }
    }
    selectedId?.let { id ->
        allRequests.firstOrNull { it.id == id }?.let { request ->
            OtherRequestDetailDialog(request, state, vm) { selectedId = null }
        }
    }
}

@Composable
private fun OtherRequestDetailDialog(request: LeaveRequest, state: MainUiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var rejecting by remember(request.id) { mutableStateOf(false) }
    var note by rememberSaveable(request.id) { mutableStateOf("") }
    val canReview = request.status == RequestStatus.PENDING.name && !state.saving
    val employee = state.employees.firstOrNull { it.id == request.employeeId }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Chi tiết đơn") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                Text(request.employeeName.ifBlank { employee?.fullName.orEmpty().ifBlank { request.employeeId } },
                    style = MaterialTheme.typography.titleMedium)
                Text("${employee?.code?.takeIf(String::isNotBlank) ?: request.employeeId} • ${request.department.ifBlank { employee?.department.orEmpty() }}")
                Text(requestTypeLabel(request.type), style = MaterialTheme.typography.titleSmall)
                Text("${adminRequestDateLabel(request.startDate)} – ${adminRequestDateLabel(request.endDate)}")
                Text("Trạng thái: ${requestStatusLabel(request.status)}")
                Text("Lý do: ${request.reason}")
                if (request.type == "LEAVE") {
                    request.leaveShiftsByDate?.toSortedMap()?.forEach { (date, shiftIds) ->
                        val names = shiftIds.map { id -> state.shifts.firstOrNull { it.id == id }
                            ?.let { "${it.name} ${it.startTime}–${it.endTime}" } ?: id }
                        Text("${adminRequestDateLabel(date)}: ${names.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    } ?: Text("Phạm vi: tất cả ca được phân trong khoảng ngày (đơn cũ).", style = MaterialTheme.typography.bodySmall)
                }
                request.proposedCheckIn?.takeIf(String::isNotBlank)?.let { Text("Giờ vào đề xuất: $it") }
                request.proposedCheckOut?.takeIf(String::isNotBlank)?.let { Text("Giờ ra đề xuất: $it") }
                request.requestedShiftId?.takeIf(String::isNotBlank)?.let { id ->
                    val shiftName = request.requestedShiftName?.takeIf(String::isNotBlank)
                        ?: state.shifts.firstOrNull { it.id == id }?.name ?: id
                    Text("Ca đề xuất: $shiftName")
                }
                request.attachmentUrl?.takeIf(String::isNotBlank)?.let { Text("Tệp đính kèm: $it", style = MaterialTheme.typography.bodySmall) }
                request.reviewerName?.let { name ->
                    Text("Người duyệt: $name", style = MaterialTheme.typography.bodySmall)
                }
                request.reviewedAt?.let { timestamp ->
                    Text("Lúc: ${formatRequestTimestamp(timestamp.toDate().time)}", style = MaterialTheme.typography.bodySmall)
                }
                request.reviewNote?.takeIf(String::isNotBlank)?.let { Text("Phản hồi: $it", style = MaterialTheme.typography.bodySmall) }
                if (rejecting && request.status == RequestStatus.PENDING.name) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Lý do từ chối") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = canReview
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (request.status == RequestStatus.PENDING.name) {
                if (rejecting) {
                    Button(
                        onClick = { vm.reviewRequest(request.id, RequestStatus.REJECTED, note.trim(), onDismiss) },
                        enabled = canReview && note.isNotBlank()
                    ) { Text("Từ chối") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        TextButton(onClick = { vm.clearError(); rejecting = true }, enabled = canReview) { Text("Từ chối") }
                        Button(onClick = { vm.reviewRequest(request.id, RequestStatus.APPROVED, "", onDismiss) }, enabled = canReview) { Text("Duyệt") }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (rejecting) rejecting = false else onDismiss() }, enabled = !state.saving) {
                Text(if (rejecting) "Hủy" else "Đóng")
            }
        }
    )
}

private fun formatRequestTimestamp(value: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }.format(value)
