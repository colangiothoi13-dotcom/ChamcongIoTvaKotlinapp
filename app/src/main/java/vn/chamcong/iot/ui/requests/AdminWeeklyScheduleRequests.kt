package vn.chamcong.iot.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.weekDates
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.schedule.weeklyScheduleRequestsPresentation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val weeklyShortDate = DateTimeFormatter.ofPattern("dd/MM")
private val weeklyFullDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
internal fun AdminWeeklyScheduleRequestsList(
    state: MainUiState,
    vm: MainViewModel,
    header: @Composable () -> Unit
) {
    val weekStart = state.selectedWeekStart
    val presentation = weeklyScheduleRequestsPresentation(state)
    val employeesById = remember(state.employees) { state.employees.associateBy { it.id } }
    var query by rememberSaveable(weekStart) { mutableStateOf("") }
    var statusFilter by rememberSaveable(weekStart) { mutableStateOf<WeeklyScheduleRequestStatus?>(null) }
    var aggregateExpanded by rememberSaveable(weekStart) { mutableStateOf(false) }
    var detailRequestId by remember(weekStart) { mutableStateOf<String?>(null) }
    var bulkWeek by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(weekStart) {
        if (bulkWeek != weekStart) bulkWeek = null
    }
    val visibleRequests = presentation.requests.filter { request ->
        (statusFilter == null || request.status == statusFilter) &&
            adminRequestMatchesQuery(
                query, request.employeeName, employeesById[request.employeeId]?.code.orEmpty(),
                request.department, request.employeeId,
                employeesById[request.employeeId]?.fullName.orEmpty(),
                employeesById[request.employeeId]?.department.orEmpty()
            )
    }
    val approvedCount = presentation.requests.count { it.status == WeeklyScheduleRequestStatus.APPROVED }
    val deadline = weekStart.minusDays(2).atTime(17, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    val overdue = Instant.now().isAfter(deadline)
    val shiftCounts = remember(presentation.requests) { weeklyShiftCounts(presentation.requests) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
    ) {
        item(key = "weekly-header") { header() }
        item(key = "weekly-week") {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.moveWeek(-1) }, enabled = !state.saving) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Xem tuần trước")
                    }
                    Text(
                        "Tuần " + weekStart.format(weeklyShortDate) + " – " + weekDates(weekStart).last().format(weeklyFullDate),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { vm.moveWeek(1) }, enabled = !state.saving) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Xem tuần sau")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = vm::selectWeeklyRegistrationWeek, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                        Text("Tuần kế tiếp", textAlign = TextAlign.Center)
                    }
                    if (presentation.ready) {
                        TextButton(onClick = { aggregateExpanded = !aggregateExpanded }, modifier = Modifier.weight(1f)) {
                            Text(if (aggregateExpanded) "Thu gọn" else "Tổng hợp", textAlign = TextAlign.Center)
                        }
                    }
                }
                if (presentation.ready) {
                    Text(
                        presentation.requests.size.toString() + " đã gửi • " + presentation.pendingRequests.size + " chờ duyệt" +
                            if (state.operationalEmployees.any { it.active }) " • " + presentation.missingEmployees.size + " chưa gửi" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (overdue && presentation.pendingRequests.isNotEmpty()) {
                        Text("Quá hạn duyệt thứ Bảy 17:00; đơn vẫn chờ xử lý.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!presentation.ready) {
            item(key = "weekly-loading") {
                val error = state.weeklyScheduleRequestsError
                if (error != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("Không tải được đăng ký lịch tuần.", color = MaterialTheme.colorScheme.error)
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::retryWeeklyScheduleRequests, enabled = !state.saving) { Text("Thử lại") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Đang tải đăng ký lịch tuần…")
                    }
                }
            }
        } else {
            if (aggregateExpanded) {
                item(key = "weekly-deadline") {
                    Text("Hạn gửi: thứ Bảy 12:00 • Hạn duyệt: 17:00 (giờ Việt Nam).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (presentation.missingEmployees.isNotEmpty()) {
                    item(key = "weekly-missing-title") {
                        Text("Chưa đăng ký (" + presentation.missingEmployees.size + ")", style = MaterialTheme.typography.titleSmall)
                    }
                    items(presentation.missingEmployees, key = { "missing:" + it.id }, contentType = { "weekly-missing" }) { employee ->
                        Text(listOf(employee.code, employee.fullName).filter(String::isNotBlank).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                } else if (presentation.allActiveEmployeesSubmitted) {
                    item(key = "weekly-all-submitted") {
                        Text("Tất cả nhân viên đang hoạt động đã gửi đăng ký.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (shiftCounts.isNotEmpty()) {
                    item(key = "weekly-shift-title") {
                        Text("Số người theo ca đã gửi/duyệt", style = MaterialTheme.typography.titleSmall)
                    }
                    items(shiftCounts, key = { "shift:" + it.date + ":" + it.shiftId }, contentType = { "weekly-shift-count" }) { count ->
                        val shiftName = state.shifts.firstOrNull { it.id == count.shiftId }?.name ?: count.shiftId
                        Text(adminRequestDateLabel(count.date) + " • " + shiftName + ": " + count.count + " người",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "weekly-search") { AdminRequestSearchField(query, { query = it }) }
            item(key = "weekly-status") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("Tất cả " + presentation.requests.size) })
                    listOf(WeeklyScheduleRequestStatus.PENDING, WeeklyScheduleRequestStatus.APPROVED, WeeklyScheduleRequestStatus.NEEDS_REVISION).forEach { status ->
                        val count = when (status) {
                            WeeklyScheduleRequestStatus.PENDING -> presentation.pendingRequests.size
                            WeeklyScheduleRequestStatus.APPROVED -> approvedCount
                            WeeklyScheduleRequestStatus.NEEDS_REVISION -> presentation.revisionCount
                        }
                        FilterChip(selected = statusFilter == status, onClick = { statusFilter = status }, label = { Text(weeklyRequestStatusLabel(status) + " " + count) })
                    }
                }
            }
            if (presentation.reviewablePendingRequests.isNotEmpty()) {
                item(key = "weekly-bulk") {
                    OutlinedButton(
                        onClick = { vm.clearError(); bulkWeek = weekStart },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Duyệt toàn bộ " + presentation.reviewablePendingRequests.size + " đơn chờ của tuần") }
                }
            }
            item(key = "weekly-result-count") {
                Text("Hiển thị " + visibleRequests.size + "/" + presentation.requests.size + " đăng ký",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (visibleRequests.isEmpty()) {
                item(key = "weekly-empty") {
                    Text(if (presentation.showEmpty) "Chưa có đăng ký lịch tuần này." else "Không có đăng ký khớp bộ lọc.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visibleRequests, key = { "request:" + it.id }, contentType = { "weekly-request" }) { request ->
                val employeeCode = employeesById[request.employeeId]?.code.orEmpty()
                val subtitle = listOf(employeeCode, request.department).filter(String::isNotBlank).joinToString(" • ")
                val days = request.shiftsByDate.values.count { it.isNotEmpty() }
                val shifts = request.shiftsByDate.values.sumOf { it.distinct().size }
                AdminRequestSummaryCard(
                    title = request.employeeName.ifBlank { employeesById[request.employeeId]?.fullName ?: "Nhân viên" },
                    subtitle = subtitle.ifBlank { "Chưa có thông tin phòng ban" },
                    summary = days.toString() + " ngày • " + shifts + " ca",
                    status = weeklyRequestStatusLabel(request.status),
                    tone = weeklyRequestStatusTone(request.status),
                    onClick = { vm.clearError(); detailRequestId = request.id },
                    enabled = !state.saving
                )
            }
        }
    }
    detailRequestId?.let { requestId ->
        WeeklyScheduleRequestDetailsDialog(requestId, state, vm) { detailRequestId = null }
    }
    bulkWeek?.takeIf { it == weekStart }?.let { confirmedWeek ->
        AlertDialog(
            onDismissRequest = { if (!state.saving) bulkWeek = null },
            title = { Text("Duyệt toàn bộ đăng ký tuần?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("Tuần " + confirmedWeek.format(weeklyShortDate) + " – " + weekDates(confirmedWeek).last().format(weeklyFullDate))
                    Text("Duyệt " + presentation.reviewablePendingRequests.size + " đơn chờ đủ điều kiện của toàn bộ tuần, gồm cả đơn đang ẩn bởi tìm kiếm hoặc bộ lọc. Các ca sẽ được chuyển thành lịch làm việc.")
                    if (!presentation.ready) Text("Cần tải đủ đăng ký của đúng tuần trước khi duyệt.", color = MaterialTheme.colorScheme.error)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.approveWeeklySchedulesForWeek(confirmedWeek.toString()) { bulkWeek = null } },
                    enabled = !state.saving && state.selectedWeekStart == confirmedWeek &&
                        presentation.ready && presentation.reviewablePendingRequests.isNotEmpty()
                ) { Text(if (state.saving) "Đang duyệt…" else "Duyệt toàn bộ") }
            },
            dismissButton = { TextButton(onClick = { bulkWeek = null }, enabled = !state.saving) { Text("Hủy") } }
        )
    }
}

@Composable
private fun WeeklyScheduleRequestDetailsDialog(
    requestId: String,
    state: MainUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var note by remember(requestId) { mutableStateOf("") }
    val presentation = weeklyScheduleRequestsPresentation(state)
    val request = presentation.requests.firstOrNull { it.id == requestId }
    val canReview = presentation.reviewablePendingRequests.any { it.id == requestId }
    val employeeCode = request?.let { value -> state.employees.firstOrNull { it.id == value.employeeId }?.code }.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Chi tiết đăng ký tuần") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                if (request == null) {
                    Text("Đơn chưa sẵn sàng để xem. Vui lòng đóng và tải lại danh sách.", color = MaterialTheme.colorScheme.error)
                    state.weeklyScheduleRequestsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
                    Text(request.employeeName, style = MaterialTheme.typography.titleMedium)
                    listOf(employeeCode, request.department).filter(String::isNotBlank).joinToString(" • ")
                        .takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    AdminRequestStatusBadge(weeklyRequestStatusLabel(request.status), weeklyRequestStatusTone(request.status))
                    weekDates(state.selectedWeekStart).forEach { date ->
                        val shiftIds = request.shiftsByDate[date.toString()].orEmpty()
                        val labels = shiftIds.distinct().map { shiftId ->
                            state.shifts.firstOrNull { it.id == shiftId }?.let { it.name + " " + it.startTime + "–" + it.endTime } ?: shiftId
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                            Text("Thứ " + (date.dayOfWeek.value + 1) + " • " + date.format(weeklyShortDate), style = MaterialTheme.typography.labelLarge)
                            Text(labels.ifEmpty { listOf("Nghỉ") }.joinToString(" + "), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    request.reason.takeIf(String::isNotBlank)?.let { Text("Ghi chú nhân viên: " + it) }
                    request.reviewNote?.takeIf(String::isNotBlank)?.let { Text("Phản hồi: " + it) }
                    request.reviewerName?.takeIf(String::isNotBlank)?.let { Text("Người duyệt: " + it, style = MaterialTheme.typography.bodySmall) }
                    if (request.status == WeeklyScheduleRequestStatus.PENDING && !canReview) {
                        Text("Chưa thể xử lý: nhân viên không còn hoạt động hoặc hồ sơ chưa được tải.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canReview) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { if (it.length <= 1000) note = it },
                            label = { Text("Phản hồi / lý do yêu cầu sửa") },
                            supportingText = { Text(note.length.toString() + "/1000") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.saving,
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canReview) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    TextButton(
                        onClick = { vm.reviewWeeklyScheduleRequest(requestId, WeeklyScheduleRequestStatus.NEEDS_REVISION, note) { onDismiss() } },
                        enabled = !state.saving && note.isNotBlank()
                    ) { Text("Yêu cầu sửa") }
                    Button(
                        onClick = { vm.reviewWeeklyScheduleRequest(requestId, WeeklyScheduleRequestStatus.APPROVED, note) { onDismiss() } },
                        enabled = !state.saving
                    ) { Text(if (state.saving) "Đang lưu…" else "Duyệt") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Đóng") } }
    )
}

private fun weeklyRequestStatusLabel(status: WeeklyScheduleRequestStatus): String = when (status) {
    WeeklyScheduleRequestStatus.PENDING -> "Chờ duyệt"
    WeeklyScheduleRequestStatus.APPROVED -> "Đã duyệt"
    WeeklyScheduleRequestStatus.NEEDS_REVISION -> "Cần sửa"
}

private fun weeklyRequestStatusTone(status: WeeklyScheduleRequestStatus): AdminRequestStatusTone = when (status) {
    WeeklyScheduleRequestStatus.PENDING -> AdminRequestStatusTone.PENDING
    WeeklyScheduleRequestStatus.APPROVED -> AdminRequestStatusTone.APPROVED
    WeeklyScheduleRequestStatus.NEEDS_REVISION -> AdminRequestStatusTone.REVISION
}

private data class WeeklyShiftCount(val date: String, val shiftId: String, val count: Int)

private fun weeklyShiftCounts(requests: List<WeeklyScheduleRequest>): List<WeeklyShiftCount> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    requests.filter { it.status != WeeklyScheduleRequestStatus.NEEDS_REVISION }.forEach { request ->
        request.shiftsByDate.forEach { (date, shiftIds) ->
            shiftIds.distinct().forEach { shiftId ->
                val key = date to shiftId
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    }
    return counts.map { (key, count) -> WeeklyShiftCount(key.first, key.second, count) }
        .sortedWith(compareBy(WeeklyShiftCount::date, WeeklyShiftCount::shiftId))
}
