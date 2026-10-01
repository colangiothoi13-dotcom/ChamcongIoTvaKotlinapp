package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
internal fun AttendanceScanReviewDialog(
    row: Attendance,
    source: Attendance,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (AttendanceClassificationOverride) -> Unit
) {
    var choice by remember(source.id) { mutableStateOf(if (row.type == "CHECK_OUT") "CHECK_OUT" else "CHECK_IN") }
    var reason by remember(source.id) { mutableStateOf("") }
    val date = attendanceAdjustmentDate(row)
    fun correction(type: String) = AttendanceClassificationOverride(
        attendanceId = source.id,
        employeeId = source.employeeId,
        employeeName = source.employeeName.ifBlank { row.employeeName.ifBlank { source.employeeId } },
        scheduleDate = date.orEmpty(),
        sourceTimestamp = source.timestamp.toDate().toInstant(),
        sourceType = source.type,
        sourceStatus = source.status,
        sourceResolutionStatus = source.resolutionStatus,
        previousType = source.type,
        previousStatus = source.status,
        correctedType = type,
        correctedStatus = if (type == "DISCARDED") "DISCARDED"
            else row.status.takeIf { it in listOf("NORMAL", "ON_TIME", "LATE", "EARLY_LEAVE") } ?: "NORMAL",
        reason = reason.trim()
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Xác nhận hoặc xóa lượt quét") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${row.employeeName.ifBlank { row.employeeId }} • ${date.orEmpty()}")
                Text("Xác nhận Vào ca hoặc Ra ca. Xóa sẽ loại lượt quét khỏi kết quả chấm công và giữ bản ghi gốc trong nhật ký.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(choice == "CHECK_IN", { choice = "CHECK_IN" }, label = { Text("Vào ca") })
                    FilterChip(choice == "CHECK_OUT", { choice = "CHECK_OUT" }, label = { Text("Ra ca") })
                }
                OutlinedTextField(reason, { reason = it.take(500) }, Modifier.fillMaxWidth(),
                    label = { Text("Lý do xử lý (bắt buộc)") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && date != null && reason.isNotBlank(), onClick = { onSubmit(correction(choice)) }) {
                Text("Xác nhận")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy && date != null && reason.isNotBlank(), onClick = { onSubmit(correction("DISCARDED")) }) {
                Text("Xóa lượt quét", color = MaterialTheme.colorScheme.error)
            }
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Hủy") }
        }
    )
}
