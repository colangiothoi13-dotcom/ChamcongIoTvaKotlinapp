package vn.chamcong.iot.ui.overtime

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import vn.chamcong.iot.domain.SUPPLEMENTARY_ZONE_ID
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel

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
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf<OvertimeRequest?>(null) }
    val visibleRequests = state.overtimeRequests.filter { request ->
        statusFilter == null || request.status == statusFilter
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Text("Đăng ký tăng ca", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ca cố định 18:00–22:00 • Đơn chờ duyệt vẫn hiển thị sau ngày làm việc.",
            style = MaterialTheme.typography.bodySmall
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            items(overtimeFilters, key = { it.second }) { (value, label) ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = value },
                    label = { Text(label) }
                )
            }
        }
        if (visibleRequests.isEmpty()) {
            Text("Chưa có đăng ký tăng ca trong nhóm này")
        } else {
            visibleRequests.forEach { request ->
                AdminOvertimeRequestCard(
                    request = request,
                    saving = state.saving,
                    onApprove = {
                        vm.reviewOvertimeRequest(
                            request.id,
                            OvertimeRequestStatus.APPROVED,
                            ""
                        ) {}
                    },
                    onReject = { rejecting = request }
                )
            }
        }
    }

    rejecting?.let { request ->
        var reason by remember(request.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.saving) rejecting = null },
            title = { Text("Từ chối đăng ký tăng ca") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("${request.employeeName.ifBlank { request.employeeId }} • ${request.workDate}")
                    Text("Khung giờ: ${overtimeWindowLabel(request)}")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Lý do từ chối") },
                        minLines = 2
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.reviewOvertimeRequest(
                            request.id,
                            OvertimeRequestStatus.REJECTED,
                            reason.trim()
                        ) { rejecting = null }
                    },
                    enabled = !state.saving && reason.isNotBlank()
                ) {
                    Text("Từ chối")
                }
            },
            dismissButton = {
                TextButton(onClick = { rejecting = null }, enabled = !state.saving) {
                    Text("Hủy")
                }
            }
        )
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
private fun AdminOvertimeRequestCard(
    request: OvertimeRequest,
    saving: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(
                request.employeeName.ifBlank { request.employeeId },
                style = MaterialTheme.typography.titleMedium
            )
            Text("${request.workDate} • ${overtimeWindowLabel(request)}")
            Text("Trạng thái: ${overtimeStatusLabel(request.status)}")
            request.rejectionReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text("Lý do từ chối: $reason", style = MaterialTheme.typography.bodySmall)
            }
            if (request.status == OvertimeRequestStatus.PENDING.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Button(onClick = onApprove, enabled = !saving) { Text("Duyệt") }
                    TextButton(onClick = onReject, enabled = !saving) { Text("Từ chối") }
                }
            }
        }
    }
}
