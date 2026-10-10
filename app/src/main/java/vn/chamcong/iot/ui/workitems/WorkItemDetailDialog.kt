package vn.chamcong.iot.ui.workitems

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.chamcong.iot.domain.MAX_WORK_ITEM_ATTACHMENTS
import vn.chamcong.iot.domain.isWorkItemOverdue
import vn.chamcong.iot.domain.wasWorkItemCompletedLate
import vn.chamcong.iot.model.UserRole
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.ui.*
import java.time.LocalDate

@Composable
internal fun WorkItemDetailDialog(state: MainUiState, vm: MainViewModel, item: WorkItem, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val admin = state.userProfile?.role == UserRole.ADMIN.name
    var baseline by remember(item.id) { mutableStateOf(item) }
    var report by remember(item.id) { mutableStateOf(item.resultReport) }
    var feedback by remember(item.id) { mutableStateOf("") }
    var retainedAttachments by remember(item.id) { mutableStateOf(item.resultAttachments) }
    var pendingAttachments by remember(item.id) { mutableStateOf(emptyList<PendingWorkItemAttachment>()) }
    var attachmentError by remember(item.id) { mutableStateOf<String?>(null) }
    var checkingAttachments by remember(item.id) { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val stale = baseline.version != item.version
    val employeeMayAct = !admin && item.assigneeId == state.currentEmployee?.id
    val busy = state.saving || checkingAttachments
    val attachmentCount = retainedAttachments.size + pendingAttachments.size
    val hasReport = report.isNotBlank() || attachmentCount > 0
    val selectAttachments: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            checkingAttachments = true
            attachmentError = null
            scope.launch {
                try {
                    val selections = withContext(Dispatchers.IO) {
                        uris.distinct().map { uri -> runCatching { readPendingWorkItemAttachment(resolver, uri) } }
                    }
                    val additions = mutableListOf<PendingWorkItemAttachment>()
                    val errors = mutableListOf<String>()
                    selections.forEach { selection ->
                        selection.fold(
                            onSuccess = { attachment ->
                                if (pendingAttachments.none { it.uri == attachment.uri } && additions.none { it.uri == attachment.uri }) {
                                    if (retainedAttachments.size + pendingAttachments.size + additions.size >= MAX_WORK_ITEM_ATTACHMENTS) {
                                        errors += "Tối đa $MAX_WORK_ITEM_ATTACHMENTS tệp. Bỏ bớt tệp trước khi chọn thêm."
                                    } else additions += attachment
                                }
                            },
                            onFailure = { errors += it.message ?: "Không đọc được tệp đã chọn. Vui lòng thử lại." }
                        )
                    }
                    pendingAttachments = pendingAttachments + additions
                    attachmentError = errors.distinct().joinToString("\n").takeIf { it.isNotBlank() }
                } finally {
                    checkingAttachments = false
                }
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), selectAttachments)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), selectAttachments)
    fun launchPicker(images: Boolean) {
        attachmentError = null
        runCatching {
            if (images) imagePicker.launch(arrayOf("image/*")) else filePicker.launch(arrayOf("*/*"))
        }.onFailure { attachmentError = "Không mở được trình chọn tệp. Vui lòng thử lại." }
    }
    val now by currentWorkTime()
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Chi tiết công việc") },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Text("${workStatusLabel(item.status)} • Ưu tiên ${workPriorityLabel(item.priority)}", style = MaterialTheme.typography.titleMedium)
                Text("Người giao: ${item.assignedByName}")
                Text("Người thực hiện: ${item.assigneeName}")
                Text("Bắt đầu: ${workTimeLabel(item.startAt)}")
                Text("Hạn hoàn thành: ${workTimeLabel(item.deadline)}")
                when {
                    isWorkItemOverdue(item, now) -> Text("Đang quá hạn", color = MaterialTheme.colorScheme.error)
                    wasWorkItemCompletedLate(item) -> Text("Hoàn thành muộn lúc ${workTimeLabel(item.completedAt)}", color = MaterialTheme.colorScheme.error)
                    item.status == "COMPLETED" -> Text("Hoàn thành đúng hạn lúc ${workTimeLabel(item.completedAt)}")
                }
                WorkDetailSection("Mô tả", item.description)
                WorkDetailSection("Yêu cầu kết quả", item.requiredResult)
                if (item.managerFeedback.isNotBlank()) WorkDetailSection("Phản hồi của quản lý", item.managerFeedback)
                if (item.reworkCount > 0) Text("Đã yêu cầu làm lại: ${item.reworkCount} lần")
                if (stale) {
                    Text("Công việc đã thay đổi. Báo cáo, phản hồi và tệp mới chọn được giữ lại. Kiểm tra thông tin mới trước khi tiếp tục.", color = MaterialTheme.colorScheme.error)
                    if (item.resultReport != baseline.resultReport) WorkDetailSection("Báo cáo trong phiên bản mới", item.resultReport.ifBlank { "Trống" })
                    if (item.resultAttachments != baseline.resultAttachments) {
                        Text("Tệp đã gửi trong phiên bản mới", style = MaterialTheme.typography.titleSmall)
                        if (item.resultAttachments.isEmpty()) Text("Không có tệp.", style = MaterialTheme.typography.bodySmall)
                        WorkItemAttachmentList(item.resultAttachments, !busy, { vm.openWorkItemAttachment(item.id, it) })
                    }
                    TextButton(onClick = {
                        val removedIds = baseline.resultAttachments.map { it.id }.toSet() - retainedAttachments.map { it.id }.toSet()
                        retainedAttachments = item.resultAttachments.filterNot { it.id in removedIds }
                        baseline = item
                    }, enabled = !busy) { Text("Dùng phiên bản mới, giữ nội dung nhập") }
                }
                if (employeeMayAct && item.status == "ASSIGNED") {
                    Button(onClick = { vm.startWorkItem(baseline, onDismiss) }, enabled = !state.saving && !stale, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Đang lưu…" else "Bắt đầu thực hiện")
                    }
                }
                if (employeeMayAct && item.status == "IN_PROGRESS") {
                    WorkTextField(report, { report = it }, "Tiến độ và báo cáo kết quả", busy)
                    Text("Mô tả kết quả hoặc đính kèm ảnh, tài liệu, video làm minh chứng. Gửi duyệt để quản lý xác nhận.", style = MaterialTheme.typography.bodySmall)
                    Text("Tệp đính kèm ($attachmentCount/$MAX_WORK_ITEM_ATTACHMENTS)", style = MaterialTheme.typography.titleSmall)
                    Text("Tối đa 5 MB mỗi tệp. Tệp sẽ được gửi khi lưu tiến độ hoặc gửi kết quả.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        OutlinedButton(onClick = { launchPicker(true) }, enabled = !busy && !stale && attachmentCount < MAX_WORK_ITEM_ATTACHMENTS, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Thêm ảnh") }
                        OutlinedButton(onClick = { launchPicker(false) }, enabled = !busy && !stale && attachmentCount < MAX_WORK_ITEM_ATTACHMENTS, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Thêm tệp") }
                    }
                    WorkItemAttachmentList(
                        retainedAttachments,
                        enabled = !busy && !stale,
                        onOpen = { vm.openWorkItemAttachment(item.id, it) },
                        onRemove = { removed ->
                            retainedAttachments = retainedAttachments.filterNot { it.id == removed.id }
                            attachmentError = null
                        }
                    )
                    PendingWorkItemAttachmentList(pendingAttachments, enabled = !busy && !stale) { removed ->
                        pendingAttachments = pendingAttachments.filterNot { it.uri == removed.uri }
                        attachmentError = null
                    }
                    attachmentError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (checkingAttachments) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Đang đọc thông tin tệp…", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.saveWorkItemReport(baseline, report, retainedAttachments, pendingAttachments.map { it.uri }, false, onDismiss) }, enabled = !busy && !stale && hasReport, modifier = Modifier.fillMaxWidth()) {
                        Text("Lưu tiến độ")
                    }
                    Button(onClick = { vm.saveWorkItemReport(baseline, report, retainedAttachments, pendingAttachments.map { it.uri }, true, onDismiss) }, enabled = !busy && !stale && hasReport, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Đang lưu…" else "Gửi kết quả chờ duyệt")
                    }
                } else {
                    if (item.resultReport.isNotBlank()) WorkDetailSection("Báo cáo kết quả", item.resultReport)
                    if (item.resultAttachments.isNotEmpty()) {
                        Text("Tệp đính kèm", style = MaterialTheme.typography.titleSmall)
                        WorkItemAttachmentList(item.resultAttachments, !busy, { vm.openWorkItemAttachment(item.id, it) })
                    }
                }
                state.workItemAttachmentStatus?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (item.status == "PENDING_REVIEW" && !admin) {
                    Text("Đã gửi kết quả. Công việc đang chờ quản lý duyệt hoàn thành.", style = MaterialTheme.typography.bodyMedium)
                }
                if (admin && item.status == "PENDING_REVIEW") {
                    WorkTextField(feedback, { feedback = it }, "Phản hồi của quản lý", state.saving)
                    Text("Bắt buộc nhập phản hồi khi yêu cầu làm lại.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.reviewWorkItemResult(baseline, true, feedback, onDismiss) }, enabled = !state.saving && !stale, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Đang lưu…" else "Duyệt hoàn thành")
                    }
                    OutlinedButton(onClick = { vm.reviewWorkItemResult(baseline, false, feedback, onDismiss) }, enabled = !state.saving && !stale && feedback.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Yêu cầu làm lại")
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (admin && item.status != "COMPLETED") OutlinedButton(onClick = onEdit, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Sửa, đổi người hoặc thời hạn")
                }
                HorizontalDivider()
                WorkAttendanceEvidence(state, vm, item)
                HorizontalDivider()
                Text("Lịch sử cập nhật", style = MaterialTheme.typography.titleMedium)
                if (state.workItemHistoryLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Đang tải lịch sử…")
                }
                state.workItemHistoryError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.selectWorkItem(item.id) }) { Text("Tải lại lịch sử") }
                }
                if (!state.workItemHistoryLoading && state.workItemHistoryError == null && state.workItemHistory.isEmpty()) Text("Chưa có lịch sử cập nhật.")
                }
                }
                items(state.workItemHistory.filter { it.workItemId == item.id }.sortedByDescending { it.version }, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            Text(workActionLabel(entry.action), style = MaterialTheme.typography.titleSmall)
                            Text("${entry.actorName.ifBlank { entry.actorId }} • ${workTimeLabel(entry.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            workHistoryChanges(entry).forEach { change -> Text(change, style = MaterialTheme.typography.bodyMedium) }
                            if (entry.after.resultAttachments.isNotEmpty() && entry.before?.resultAttachments != entry.after.resultAttachments) {
                                Text("Tệp của lần cập nhật này", style = MaterialTheme.typography.titleSmall)
                                WorkItemAttachmentList(entry.after.resultAttachments, !busy, { vm.openWorkItemAttachment(item.id, it) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Đóng") } }
    )
}

@Composable
private fun WorkDetailSection(label: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(content, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WorkAttendanceEvidence(state: MainUiState, vm: MainViewModel, item: WorkItem) {
    Text("Có mặt trong ca liên quan", style = MaterialTheme.typography.titleMedium)
    Text("Vân tay chứng minh có mặt tại thiết bị; báo cáo và bước duyệt chứng minh hoàn thành công việc.", style = MaterialTheme.typography.bodySmall)
    if (item.relatedScheduleId == null) {
        Text("Công việc này không gắn ca làm.")
        return
    }
    val schedule = (state.calculationSchedules + state.employeeSchedules).firstOrNull { it.id == item.relatedScheduleId }
    val date = (item.relatedScheduleDate ?: schedule?.date)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val shift = state.calculationShifts.firstOrNull { it.id == item.relatedShiftId }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(item.id, date, item.assigneeId) {
        if (date != null && !inPreview) vm.loadAttendanceRange(date, date, item.assigneeId)
    }
    Text("${schedule?.employeeName ?: item.assigneeName} • ${date ?: "Ngày ca chưa có"} • ${shift?.name ?: "Ca đã liên kết"}")
    shift?.let { Text("Giờ ca: ${it.startTime}–${it.endTime}", style = MaterialTheme.typography.bodySmall) }
    if (date == null) {
        Text("Chưa có thông tin ngày ca để tải dữ liệu có mặt.", color = MaterialTheme.colorScheme.error)
        return
    }
    if (state.attendanceHistoryLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Đang tải dữ liệu có mặt của ca…")
        return
    }
    state.attendanceHistoryError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { vm.loadAttendanceRange(date, date, item.assigneeId, force = true) }) { Text("Thử lại dữ liệu có mặt") }
        return
    }
    if (!state.hasCompleteCalculationRange(date, date, item.assigneeId)) {
        Text("Dữ liệu có mặt của ca chưa đầy đủ.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { vm.loadAttendanceRange(date, date, item.assigneeId, force = true) }) { Text("Tải dữ liệu ca") }
        return
    }
    val rows = if (state.userProfile?.role == UserRole.ADMIN.name) state.historicalAttendanceForSummaries else state.employeeAttendanceForSummaries
    val schedules = (state.calculationSchedules + state.employeeSchedules).distinctBy { it.id }
    val shifts = state.calculationShifts
    val scans = remember(rows, schedules, shifts, item.assigneeId, date, item.relatedScheduleId, item.relatedShiftId) {
        workItemAttendanceEvidence(item, rows, schedules, shifts)
    }
    if (scans.isEmpty()) Text("Chưa ghi nhận lượt quét hợp lệ thuộc ca này. Điều này không xác nhận công việc đã hoặc chưa hoàn thành.")
    else scans.forEach { row ->
        Text("${if (row.type == "CHECK_IN") "Vào ca" else if (row.type == "CHECK_OUT") "Ra ca" else row.type} • ${workTimeLabel(row.timestamp)} • Thiết bị ${row.deviceId.ifBlank { "Chưa xác định" }}")
    }
}
