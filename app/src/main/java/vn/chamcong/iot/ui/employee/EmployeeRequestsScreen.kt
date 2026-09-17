package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.overtime.EmployeeOvertimeRequestSection
import java.time.LocalDate

@Composable
fun EmployeeRequestsScreen(state: MainUiState, vm: MainViewModel) {
    var showForm by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(RequestType.LEAVE) }
    var startDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var endDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    val supportedTypes = listOf(RequestType.LEAVE, RequestType.LATE, RequestType.EARLY_LEAVE, RequestType.ATTENDANCE_ADJUSTMENT, RequestType.REMOTE, RequestType.SHIFT_CHANGE)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đơn từ của tôi", style = MaterialTheme.typography.titleLarge)
                TextButton({ showForm = !showForm }) { Text(if (showForm) "Đóng form" else "Tạo đơn") }
            }
        }
        if (showForm) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tạo đơn mới", style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(supportedTypes, key = { it.name }) { candidate ->
                                FilterChip(selected = type == candidate, onClick = { type = candidate }, label = { Text(candidate.toVietnamese()) })
                            }
                        }
                        OutlinedTextField(startDate, { startDate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ngày bắt đầu (yyyy-MM-dd)") }, singleLine = true)
                        OutlinedTextField(endDate, { endDate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ngày kết thúc (yyyy-MM-dd)") }, singleLine = true)
                        OutlinedTextField(reason, { reason = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Lý do") }, minLines = 3)
                        Button(
                            onClick = { vm.submitEmployeeRequest(type, startDate, endDate, reason) { showForm = false; reason = "" } },
                            enabled = !state.saving && reason.isNotBlank()
                        ) { Text("Gửi đơn") }
                    }
                }
            }
        }
        item { Text("Lịch sử xử lý", style = MaterialTheme.typography.titleMedium) }
        if (state.employeeRequests.isEmpty()) item { Text("Bạn chưa có đơn từ") }
        items(state.employeeRequests, key = { it.id }) { request -> EmployeeRequestCard(request) }
        item { HorizontalDivider() }
        item { EmployeeOvertimeRequestSection(state, vm) }
    }
}

@Composable
private fun EmployeeRequestCard(request: LeaveRequest) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(request.type.toVietnamese(), style = MaterialTheme.typography.titleMedium)
            Text("${request.startDate} – ${request.endDate}")
            Text(request.reason)
            Text("Trạng thái: ${request.status.toVietnamese()}")
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
}

private fun String.toVietnamese(): String = when (this) {
    RequestStatus.PENDING.name -> "Chờ duyệt"
    RequestStatus.APPROVED.name -> "Đã duyệt"
    RequestStatus.REJECTED.name -> "Từ chối"
    else -> this
}
