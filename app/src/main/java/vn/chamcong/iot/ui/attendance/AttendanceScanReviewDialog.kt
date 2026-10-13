package vn.chamcong.iot.ui.attendance

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.attendanceStatusLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttendanceScanReviewDialog(
    row: Attendance,
    source: Attendance,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (AttendanceClassificationOverride) -> Unit,
    previousOverride: AttendanceClassificationOverride? = null
) {
    val initial = initialAttendanceClassificationSelection(row, previousOverride)
    var choice by remember(source.id, previousOverride?.id) { mutableStateOf(initial.type) }
    var status by remember(source.id, previousOverride?.id) { mutableStateOf(initial.status) }
    var reason by remember(source.id) { mutableStateOf("") }
    var validationError by remember(source.id) { mutableStateOf<String?>(null) }
    val date = attendanceAdjustmentDate(row)
    val changed = choice != (previousOverride?.correctedType ?: source.type) || status != (previousOverride?.correctedStatus ?: source.status)
    fun submit(type: String, newStatus: String) {
        runCatching { attendanceClassificationDraft(row, source, previousOverride, type, newStatus, reason) }
            .onSuccess(onSubmit).onFailure { validationError = it.message }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sửa phân loại lượt chấm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${row.employeeName.ifBlank { row.employeeId }} • ${date.orEmpty()}")
                Text("Gốc: ${classificationTypeLabel(source.type)} · ${attendanceStatusLabel(source.status)}", style = MaterialTheme.typography.bodyMedium)
                Text("Hiện tại: ${classificationTypeLabel(previousOverride?.correctedType ?: row.type)} · ${attendanceStatusLabel(previousOverride?.correctedStatus ?: row.status)}", style = MaterialTheme.typography.bodyMedium)
                Text("Mỗi lần sửa được lưu cùng người thực hiện và lý do. Lượt quét gốc được giữ nguyên.", style = MaterialTheme.typography.bodyMedium)
                if (choice == "DISCARDED") Text("Chọn Vào ca hoặc Ra ca để khôi phục lượt đã loại.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(choice == "CHECK_IN", { choice = "CHECK_IN"; if (status == "DISCARDED") status = "NORMAL"; validationError = null }, label = { Text("Vào ca") }, enabled = !busy)
                    FilterChip(choice == "CHECK_OUT", { choice = "CHECK_OUT"; if (status == "DISCARDED") status = "NORMAL"; validationError = null }, label = { Text("Ra ca") }, enabled = !busy)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    listOf("NORMAL" to "Bình thường", "ON_TIME" to "Đúng giờ", "LATE" to "Đi trễ", "EARLY_LEAVE" to "Về sớm").forEach { (value, label) ->
                        FilterChip(status == value, { status = value; validationError = null }, label = { Text(label) }, enabled = !busy && choice != "DISCARDED")
                    }
                }
                OutlinedTextField(reason, { reason = it.take(500); validationError = null }, Modifier.fillMaxWidth(),
                    label = { Text("Lý do xử lý (bắt buộc)") }, enabled = !busy)
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && date != null && reason.isNotBlank() && changed, onClick = { submit(choice, status) }) {
                Text(if (busy) "Đang lưu…" else "Lưu phân loại")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy && date != null && reason.isNotBlank() && previousOverride?.correctedType != "DISCARDED", onClick = { submit("DISCARDED", "DISCARDED") }) {
                Text("Loại lượt quét", color = MaterialTheme.colorScheme.error)
            }
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AttendanceScanReviewDialogPreview() {
    PreviewStateScreen { state, vm -> state.attendance.first().let { AttendanceScanReviewDialog(it, it, busy = false, error = null, onDismiss = {}, onSubmit = {}) } }
}
