package vn.chamcong.iot.ui.workitems

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.google.firebase.Timestamp
import vn.chamcong.iot.domain.shiftWindow
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemDraft
import vn.chamcong.iot.model.WorkItemHistory
import vn.chamcong.iot.model.WorkItemPriority
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.ui.*
import java.time.LocalDate
import java.util.Date

@Composable
fun WorkItemEditorDialog(
    state: MainUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    item: WorkItem? = null,
    initialSchedule: WorkSchedule? = null,
    onSaved: (String) -> Unit = {}
) {
    var baseline by remember(item?.id) { mutableStateOf(item) }
    val scheduleAtOpen = remember(item?.id, initialSchedule?.id) {
        initialSchedule ?: state.calculationSchedules.firstOrNull { it.id == item?.relatedScheduleId }
    }
    val windowAtOpen = remember(scheduleAtOpen?.id) {
        val shift = state.calculationShifts.firstOrNull { it.id == (item?.relatedShiftId ?: scheduleAtOpen?.shiftId) }
        val date = scheduleAtOpen?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (shift != null && date != null) runCatching { shiftWindow(date, shift, workZone) }.getOrNull() else null
    }
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var description by remember(item?.id) { mutableStateOf(item?.description.orEmpty()) }
    var requiredResult by remember(item?.id) { mutableStateOf(item?.requiredResult.orEmpty()) }
    var assigneeId by remember(item?.id, initialSchedule?.id) { mutableStateOf(item?.assigneeId ?: initialSchedule?.employeeId.orEmpty()) }
    var assigneeQuery by remember(item?.id) { mutableStateOf("") }
    var start by remember(item?.id, initialSchedule?.id) {
        mutableStateOf(workTimeLabel(item?.startAt ?: windowAtOpen?.start?.let { Timestamp(Date.from(it)) } ?: Timestamp.now()))
    }
    var deadline by remember(item?.id, initialSchedule?.id) {
        mutableStateOf(workTimeLabel(item?.deadline ?: windowAtOpen?.end?.let { Timestamp(Date.from(it)) }
            ?: Timestamp(Date.from(java.time.Instant.now().plusSeconds(8 * 3600)))))
    }
    var priority by remember(item?.id) { mutableStateOf(item?.priority ?: WorkItemPriority.NORMAL.name) }
    var scheduleId by remember(item?.id, initialSchedule?.id) { mutableStateOf(item?.relatedScheduleId ?: initialSchedule?.id) }
    var shiftId by remember(item?.id, initialSchedule?.id) { mutableStateOf(item?.relatedShiftId ?: initialSchedule?.shiftId) }
    val newest = state.workItems.firstOrNull { it.id == item?.id }
    val stale = newest != null && newest.version != baseline?.version
    val employees = state.employees.filter { it.active || it.id == assigneeId }
    val assignee = employees.firstOrNull { it.id == assigneeId }
    val assigneeName = assignee?.fullName ?: scheduleAtOpen?.takeIf { it.employeeId == assigneeId }?.employeeName
        ?: baseline?.assigneeName?.takeIf { baseline?.assigneeId == assigneeId }.orEmpty()
    val schedules = (state.calculationSchedules + listOfNotNull(scheduleAtOpen)).distinctBy { it.id }
        .filter { it.employeeId == assigneeId && it.id.isNotBlank() }.sortedByDescending { it.date }
    val selectedSchedule = schedules.firstOrNull { it.id == scheduleId }
    val shiftOptions = selectedSchedule?.let { (it.shiftIds.ifEmpty { listOf(it.shiftId) }).distinct() }.orEmpty()
    val startAt = parseWorkTime(start)
    val deadlineAt = parseWorkTime(deadline)
    val validTimes = startAt != null && deadlineAt != null && deadlineAt > startAt
    val validLink = scheduleId == null && shiftId == null || selectedSchedule != null && shiftId in shiftOptions
    val canSave = title.isNotBlank() && description.isNotBlank() && requiredResult.isNotBlank() &&
        assigneeId.isNotBlank() && assigneeName.isNotBlank() && validTimes && validLink && !stale && baseline?.status != "COMPLETED"

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(if (item == null) "Tạo và giao công việc" else "Điều chỉnh công việc") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Text("Tên, mô tả, yêu cầu kết quả, người thực hiện và thời hạn là các thông tin bắt buộc.", style = MaterialTheme.typography.bodySmall)
                if (stale) {
                    Text("Công việc đã được cập nhật ở nơi khác. Nội dung bạn đang nhập được giữ lại.", color = MaterialTheme.colorScheme.error)
                    newest?.let { latest ->
                        Text("Bản mới: ${latest.assigneeName} • Hạn ${workTimeLabel(latest.deadline)} • ${workStatusLabel(latest.status)}")
                        workHistoryChanges(WorkItemHistory(before = baseline, after = latest)).forEach { change ->
                            Text(change, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { baseline = latest }, enabled = !state.saving && latest.status != "COMPLETED") {
                            Text("Dùng phiên bản mới, giữ nội dung nhập")
                        }
                    }
                }
                if (baseline?.status == "PENDING_REVIEW") {
                    Text("Thay đổi việc đang chờ duyệt sẽ đưa việc về Đang thực hiện để nhân viên gửi lại kết quả.", style = MaterialTheme.typography.bodySmall)
                }
                if (baseline != null && assigneeId != baseline?.assigneeId) {
                    Text("Đổi người thực hiện sẽ giao lại việc và xóa báo cáo, phản hồi hiện tại.", color = MaterialTheme.colorScheme.error)
                }
                WorkTextField(title, { title = it }, "Tên công việc", state.saving, singleLine = true)
                WorkTextField(description, { description = it }, "Mô tả công việc", state.saving)
                WorkTextField(requiredResult, { requiredResult = it }, "Yêu cầu kết quả / minh chứng", state.saving)
                OutlinedTextField(
                    value = assigneeQuery, onValueChange = { assigneeQuery = it }, label = { Text("Tìm nhân viên theo tên hoặc mã") },
                    enabled = !state.saving, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                WorkChoiceField(
                    label = "Người thực hiện", value = assigneeName.ifBlank { "Chọn nhân viên" }, enabled = !state.saving,
                    choices = employees.filter { "${it.fullName} ${it.code}".contains(assigneeQuery, ignoreCase = true) }
                        .map { it.id to "${it.fullName} • ${it.code.ifBlank { it.id }}" },
                    onSelect = { next -> if (next != assigneeId) { assigneeId = next; scheduleId = null; shiftId = null } }
                )
                if (employees.isEmpty()) Text("Chưa có nhân viên có thể nhận việc.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = start, onValueChange = { start = it }, label = { Text("Bắt đầu (dd/MM/yyyy HH:mm)") },
                    supportingText = { Text("Ví dụ: 08/10/2026 08:00 • giờ Việt Nam") },
                    isError = startAt == null, singleLine = true, enabled = !state.saving, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = deadline, onValueChange = { deadline = it }, label = { Text("Hạn hoàn thành (dd/MM/yyyy HH:mm)") },
                    supportingText = { Text(if (!validTimes) "Hạn phải hợp lệ và sau thời điểm bắt đầu." else "Kết quả cần được quản lý duyệt để hoàn thành.") },
                    isError = !validTimes, singleLine = true, enabled = !state.saving, modifier = Modifier.fillMaxWidth()
                )
                WorkChoiceField("Mức ưu tiên", workPriorityLabel(priority), !state.saving,
                    WorkItemPriority.entries.map { it.name to workPriorityLabel(it.name) }, { priority = it })
                WorkChoiceField(
                    "Ca làm liên quan (không bắt buộc)",
                    selectedSchedule?.let { "${it.date} • ${it.shiftName}" } ?: if (scheduleId == null) "Không gắn ca" else "Ca chưa tải được",
                    !state.saving,
                    listOf("" to "Không gắn ca") + schedules.map { it.id to "${it.date} • ${it.shiftName}" },
                    { next -> scheduleId = next.takeIf(String::isNotBlank); shiftId = schedules.firstOrNull { it.id == next }?.shiftId }
                )
                if (selectedSchedule != null) {
                    WorkChoiceField("Chọn ca trong ngày", state.calculationShifts.firstOrNull { it.id == shiftId }?.name ?: shiftId.orEmpty(),
                        !state.saving, shiftOptions.map { id -> id to (state.calculationShifts.firstOrNull { it.id == id }?.let { "${it.name} • ${it.startTime}–${it.endTime}" } ?: id) },
                        { shiftId = it })
                }
                if (!validLink) Text("Chọn lại ca phù hợp với nhân viên, hoặc bỏ liên kết ca.", color = MaterialTheme.colorScheme.error)
                Text("Vân tay ghi nhận có mặt tại thiết bị. Báo cáo kết quả và bước duyệt xác nhận hoàn thành công việc.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val draft = WorkItemDraft(title = title.trim(), description = description.trim(), requiredResult = requiredResult.trim(),
                    assigneeId = assigneeId, assigneeName = assigneeName, startAt = startAt!!, deadline = deadlineAt!!,
                    priority = priority, relatedScheduleId = scheduleId, relatedShiftId = shiftId)
                baseline?.let { original ->
                    vm.updateWorkItem(original, draft) { onSaved(original.id); onDismiss() }
                } ?: vm.createWorkItem(draft) { id -> onSaved(id); onDismiss() }
            }, enabled = !state.saving && canSave) { Text(if (state.saving) "Đang lưu…" else if (item == null) "Giao việc" else "Lưu thay đổi") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Composable
internal fun WorkTextField(value: String, onChange: (String) -> Unit, label: String, saving: Boolean, singleLine: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, enabled = !saving, modifier = Modifier.fillMaxWidth())
}
