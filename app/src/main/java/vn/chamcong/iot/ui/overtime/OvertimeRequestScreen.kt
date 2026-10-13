package vn.chamcong.iot.ui.overtime

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.AdminMetricCard
import vn.chamcong.iot.ui.AdminSectionCard
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import vn.chamcong.iot.domain.SUPPLEMENTARY_ZONE_ID
import vn.chamcong.iot.domain.requestReviewNote
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.requests.AdminRequestSearchField
import vn.chamcong.iot.ui.requests.AdminRequestDepartmentField
import vn.chamcong.iot.ui.requests.adminRequestMatchesDepartment
import vn.chamcong.iot.ui.requests.AdminRequestStatusBadge
import vn.chamcong.iot.ui.requests.AdminRequestStatusTone
import vn.chamcong.iot.ui.requests.AdminRequestSummaryCard
import vn.chamcong.iot.ui.requests.adminRequestDateLabel
import vn.chamcong.iot.ui.requests.adminRequestMatchesQuery

private val overtimeFilters = listOf(
    null to "Tất cả",
    OvertimeRequestStatus.PENDING.name to "Chờ duyệt",
    OvertimeRequestStatus.APPROVED.name to "Đã duyệt",
    OvertimeRequestStatus.REJECTED.name to "Từ chối"
)

@Composable
fun EmployeeOvertimeRequestSection(state: MainUiState, vm: MainViewModel) {
    val today = LocalDate.now(SUPPLEMENTARY_ZONE_ID)
    var workDate by remember { mutableStateOf(today.toString()) }
    var reason by remember { mutableStateOf("") }
    val validCalendarDate = isValidOvertimeWorkDate(workDate, today)
    val openForSubmission = isOpenOvertimeSubmissionDate(workDate)
    val validDate = validCalendarDate && openForSubmission

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Text("Đăng ký ca tăng ca", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Khung giờ cố định: 18:00–22:00")
                Text(
                    "Bạn chỉ có thể đăng ký cho hôm nay hoặc một ngày trong tương lai.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = workDate,
                    onValueChange = { workDate = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ngày tăng ca (yyyy-MM-dd)") },
                    singleLine = true,
                    enabled = !state.saving,
                    isError = workDate.isNotBlank() && !validDate,
                    supportingText = {
                        if (!validCalendarDate) Text("Nhập ngày hợp lệ từ hôm nay trở đi")
                        else if (!openForSubmission) Text("Đơn cho hôm nay phải gửi trước 18:00")
                    }
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lý do tăng ca") },
                    minLines = 3,
                    enabled = !state.saving,
                    supportingText = { if (reason.isBlank()) Text("Vui lòng nhập lý do đăng ký tăng ca") }
                )
                Button(
                    onClick = { vm.submitOvertimeRequest(workDate.trim(), reason.trim()) { reason = "" } },
                    enabled = validDate && reason.isNotBlank() && !state.saving
                ) {
                    Text("Gửi đăng ký tăng ca")
                }
            }
        }

        Text("Lịch sử đăng ký tăng ca", style = MaterialTheme.typography.titleMedium)
        if (state.employeeOvertimeRequests.isEmpty()) {
            Text("Bạn chưa có đăng ký tăng ca")
        } else {
            state.employeeOvertimeRequests.forEach { request ->
                EmployeeOvertimeRequestCard(request)
            }
        }
    }
}

@Composable
fun AdminOvertimeRequestSection(state: MainUiState, vm: MainViewModel) {
    AdminOvertimeRequestsList(state, vm, header = {})
}

@Composable
internal fun AdminOvertimeRequestsList(
    state: MainUiState,
    vm: MainViewModel,
    header: @Composable () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRequest by remember { mutableStateOf<OvertimeRequest?>(null) }
    val employeesById = remember(state.employees) { state.employees.associateBy { it.id } }
    val operationalIds = state.operationalEmployees.mapTo(mutableSetOf()) { it.id }
    val scopedRequests = state.overtimeRequests.filter { it.employeeId in operationalIds }
        .sortedWith(compareBy<OvertimeRequest> { it.status != OvertimeRequestStatus.PENDING.name }
            .thenByDescending { it.createdAt ?: Instant.MIN }
            .thenByDescending { it.workDate }.thenBy { it.id })
    val matchingRequests = scopedRequests.filter { request ->
        val employee = employeesById[request.employeeId]
        adminRequestMatchesDepartment(department, employee, request.department, state.departments) &&
            adminRequestMatchesQuery(query, request.employeeName, request.employeeId,
            employee?.fullName.orEmpty(), employee?.code.orEmpty(), request.department,
            employee?.department.orEmpty())
    }
    val visibleRequests = matchingRequests.filter { statusFilter == null || it.status == statusFilter }
    val pendingCount = scopedRequests.count { it.status == OvertimeRequestStatus.PENDING.name }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        item { header() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Đơn đã gửi", scopedRequests.size.toString(), "Trong nhóm đơn", Modifier.weight(1f))
                    AdminMetricCard("Chờ duyệt", pendingCount.toString(), "Cần Admin xử lý", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Đã duyệt", scopedRequests.count { it.status == OvertimeRequestStatus.APPROVED.name }.toString(), "Đã có quyết định", Modifier.weight(1f))
                    AdminMetricCard("Từ chối", scopedRequests.count { it.status == OvertimeRequestStatus.REJECTED.name }.toString(), "Có phản hồi cho nhân viên", Modifier.weight(1f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Đăng ký tăng ca", style = MaterialTheme.typography.titleMedium)
                Text("${visibleRequests.size}/${scopedRequests.size} đơn • $pendingCount chờ duyệt",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdminRequestSearchField(query) { query = it }
                AdminRequestDepartmentField(state.departments, department) { department = it }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                items(overtimeFilters, key = { it.second }) { (value, label) ->
                    val count = matchingRequests.count { value == null || it.status == value }
                    FilterChip(
                        modifier = Modifier.heightIn(min = AppTouchTarget.minimum),
                        selected = statusFilter == value,
                        onClick = { statusFilter = value },
                        label = { Text("$label ($count)") },
                        enabled = !state.saving
                    )
                }
            }
        }
        if (visibleRequests.isEmpty()) item {
            Text(if (operationalIds.isEmpty() && state.overtimeRequests.isNotEmpty()) {
                "Chưa có hồ sơ nhân viên đang hoạt động để đối chiếu đăng ký"
            } else if (query.isNotBlank()) "Không có đăng ký tăng ca khớp tìm kiếm"
            else "Chưa có đăng ký tăng ca trong nhóm này",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visibleRequests, key = { it.id }) { request ->
            val employee = employeesById[request.employeeId]
            val code = employee?.code?.takeIf(String::isNotBlank) ?: request.employeeId
            val department = request.department.ifBlank { employee?.department.orEmpty() }.ifBlank { "Chưa có phòng ban" }
            AdminRequestSummaryCard(
                title = request.employeeName.ifBlank { employee?.fullName.orEmpty() }.ifBlank { request.employeeId },
                subtitle = "$code • $department",
                summary = "${adminRequestDateLabel(request.workDate)} • ${overtimeWindowLabel(request)}",
                status = overtimeStatusLabel(request.status),
                tone = adminOvertimeStatusTone(request.status),
                onClick = { vm.clearError(); selectedRequest = request },
                enabled = !state.saving
            )
        }
    }
    selectedRequest?.let { selected ->
        val current = state.overtimeRequests.firstOrNull { it.id == selected.id }
        val canReview = current != null && current.status == OvertimeRequestStatus.PENDING.name && current.employeeId in operationalIds
        AdminOvertimeRequestDetailDialog(current ?: selected, canReview, state, vm) { selectedRequest = null }
    }
}
@Composable
private fun EmployeeOvertimeRequestCard(request: OvertimeRequest) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(request.workDate, style = MaterialTheme.typography.titleMedium)
            Text("Khung giờ: ${overtimeWindowLabel(request)}")
            Text("Trạng thái: ${overtimeStatusLabel(request.status)}")
            request.rejectionReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text("Lý do từ chối: $reason", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AdminOvertimeRequestDetailDialog(
    request: OvertimeRequest,
    canReview: Boolean,
    state: MainUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var rejecting by remember(request.id) { mutableStateOf(false) }
    var approving by remember(request.id) { mutableStateOf(false) }
    var reason by remember(request.id) { mutableStateOf("") }
    var approvalNote by remember(request.id) { mutableStateOf("") }
    val reviewEnabled = canReview && !state.saving && vm.hasAdminAccess()
    val employee = state.employees.firstOrNull { it.id == request.employeeId }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Chi tiết đăng ký tăng ca") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(request.employeeName.ifBlank { employee?.fullName.orEmpty() }.ifBlank { request.employeeId },
                    style = MaterialTheme.typography.titleMedium)
                Text("Mã NV: ${employee?.code?.takeIf(String::isNotBlank) ?: request.employeeId}")
                Text("Phòng ban: ${request.department.ifBlank { employee?.department.orEmpty() }.ifBlank { "Chưa cập nhật" }}")
                AdminRequestStatusBadge(overtimeStatusLabel(request.status), adminOvertimeStatusTone(request.status))
                Text("Ngày tăng ca: ${adminRequestDateLabel(request.workDate)}")
                Text("Khung giờ: ${overtimeWindowLabel(request)}")
                Text("Lý do đăng ký:", style = MaterialTheme.typography.labelLarge)
                Text(request.reason.ifBlank { "Chưa ghi nhận lý do" })
                request.createdAt?.let { Text("Gửi lúc: ${adminOvertimeTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
                request.reviewerName?.takeIf(String::isNotBlank)?.let {
                    Text("Người duyệt: $it", style = MaterialTheme.typography.bodySmall)
                } ?: request.reviewerId?.takeIf(String::isNotBlank)?.let {
                    Text("Người duyệt: $it", style = MaterialTheme.typography.bodySmall)
                }
                request.reviewedAt?.let { Text("Xử lý lúc: ${adminOvertimeTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
                request.rejectionReason?.takeIf(String::isNotBlank)?.let {
                    Text("Lý do từ chối:", style = MaterialTheme.typography.labelLarge)
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (request.status == OvertimeRequestStatus.PENDING.name && !canReview) {
                    Text("Đơn hoặc hồ sơ nhân viên chưa sẵn sàng để xử lý. Vui lòng đóng và tải lại danh sách.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (rejecting && canReview) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Lý do từ chối") },
                        minLines = 2,
                        isError = reason.trim().length > 1000,
                        supportingText = { Text("${reason.trim().length}/1000") },
                        enabled = reviewEnabled
                    )
                }
                if (approving && canReview) {
                    OutlinedTextField(value = approvalNote, onValueChange = { approvalNote = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Ghi chú duyệt (tùy chọn)") }, minLines = 2,
                        enabled = reviewEnabled, isError = approvalNote.trim().length > 1000,
                        supportingText = { Text("Ghi chú gửi trong thông báo kết quả. ${approvalNote.trim().length}/1000") })
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canReview) Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                if (rejecting) {
                    TextButton(onClick = { rejecting = false; vm.clearError() }, enabled = reviewEnabled) { Text("Quay lại") }
                    Button(onClick = {
                        vm.reviewOvertimeRequest(request.id, OvertimeRequestStatus.REJECTED, requestReviewNote(reason, required = true), onDismiss)
                    }, enabled = reviewEnabled && reason.isNotBlank() && reason.trim().length <= 1000) { Text("Từ chối") }
                } else if (approving) {
                    TextButton(onClick = { approving = false; vm.clearError() }, enabled = reviewEnabled) { Text("Quay lại") }
                    Button(onClick = {
                        vm.reviewOvertimeRequest(request.id, OvertimeRequestStatus.APPROVED, requestReviewNote(approvalNote, required = false), onDismiss)
                    }, enabled = reviewEnabled && approvalNote.trim().length <= 1000) { Text(if (state.saving) "Đang lưu…" else "Xác nhận duyệt") }
                } else {
                    TextButton(onClick = { rejecting = true; vm.clearError() }, enabled = reviewEnabled) { Text("Từ chối") }
                    Button(onClick = { approving = true; vm.clearError() }, enabled = reviewEnabled) { Text("Duyệt") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Đóng") } }
    )
}

private fun adminOvertimeStatusTone(status: String): AdminRequestStatusTone = when (status) {
    OvertimeRequestStatus.PENDING.name -> AdminRequestStatusTone.PENDING
    OvertimeRequestStatus.APPROVED.name -> AdminRequestStatusTone.APPROVED
    OvertimeRequestStatus.REJECTED.name -> AdminRequestStatusTone.REVISION
    else -> AdminRequestStatusTone.NEUTRAL
}

private fun adminOvertimeTimestamp(value: Instant): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(SUPPLEMENTARY_ZONE_ID).format(value)
@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OvertimeRequestScreenPreview() {
    PreviewStateScreen { state, vm -> EmployeeOvertimeRequestSection(state, vm) }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AdminOvertimeRequestPreview() {
    PreviewStateScreen { state, vm -> AdminOvertimeRequestSection(state, vm) }
}
