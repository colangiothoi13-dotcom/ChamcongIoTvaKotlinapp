package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import vn.chamcong.iot.domain.canCorrectAttendanceClassification
import vn.chamcong.iot.domain.latestAttendanceClassificationOverride
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceRecordHistory
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.attendanceStatusLabel
import vn.chamcong.iot.ui.userFacingErrorMessage
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN"))
private fun historyTime(value: Instant?): String = value?.atZone(attendanceZone)?.format(historyTimeFormat) ?: "Chưa có"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttendanceRecordHistoryDialog(
    row: Attendance,
    busy: Boolean,
    loadHistory: suspend () -> AttendanceRecordHistory,
    onDismiss: () -> Unit,
    onAdjust: (Attendance, AttendanceRecordHistory) -> Unit,
    onClassify: (Attendance, AttendanceRecordHistory) -> Unit,
    onReviewUnscheduled: (Attendance) -> Unit
) {
    var history by remember(row.id) { mutableStateOf<AttendanceRecordHistory?>(null) }
    var loading by remember(row.id) { mutableStateOf(true) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }
    var reload by remember(row.id) { mutableStateOf(0) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(row.id, reload) {
        loading = true; error = null; history = null
        try {
            history = if (inPreview) AttendanceRecordHistory(row, attendanceAdjustmentDate(row).orEmpty()) else loadHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = userFacingErrorMessage(failure)
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = MaterialTheme.shapes.large,
        title = { Text("Chi tiết & lịch sử chấm công") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                item {
                    Text(row.employeeName.ifBlank { row.employeeId }, style = MaterialTheme.typography.titleMedium)
                    Text("Ngày ca: ${history?.scheduleDate ?: attendanceAdjustmentDate(row).orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                }
                if (loading) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Đang tải lịch sử từ máy chủ…", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { reload++ }, enabled = !busy) { Text("Thử tải lại") }
                } }
                history?.let { complete ->
                    val source = complete.source
                    val latest = latestAttendanceClassificationOverride(complete.classifications, source.id)
                    item {
                        HistoryBlock {
                            Text("Lượt quét gốc", style = MaterialTheme.typography.titleSmall)
                            Text(historyTime(source.timestamp.toDate().toInstant()))
                            Text("${classificationTypeLabel(source.type)} · ${attendanceStatusLabel(source.status)}")
                            Text("Phương thức: ${attendanceMethodLabel(source)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Thiết bị: ${source.deviceId.ifBlank { source.stationId ?: "Chưa rõ" }}", style = MaterialTheme.typography.bodyMedium)
                            Text("Hiện tại: ${classificationTypeLabel(latest?.correctedType ?: row.type)} · ${attendanceStatusLabel(latest?.correctedStatus ?: row.status)}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item { HistoryHeading("Lịch sử phân loại", complete.classifications.size) }
                    if (complete.classifications.isEmpty()) item { Text("Chưa sửa phân loại lượt này.", style = MaterialTheme.typography.bodyMedium) }
                    items(complete.classifications, key = { "classification-${it.id}" }) { correction ->
                        HistoryBlock {
                            Text("${classificationTypeLabel(correction.previousType)} · ${attendanceStatusLabel(correction.previousStatus)} → ${classificationTypeLabel(correction.correctedType)} · ${attendanceStatusLabel(correction.correctedStatus)}", style = MaterialTheme.typography.bodyMedium)
                            HistoryActor(correction.actorName, correction.createdAt, correction.reason)
                        }
                    }
                    item { HistoryHeading("Điều chỉnh công của ngày", complete.adjustments.size) }
                    if (complete.adjustments.isEmpty()) item { Text("Chưa có điều chỉnh ngày này.", style = MaterialTheme.typography.bodyMedium) }
                    items(complete.adjustments, key = { "adjustment-${it.id}" }) { adjustment ->
                        HistoryBlock {
                            Text("Vào: ${historyTime(adjustment.checkInAt)}\nRa: ${historyTime(adjustment.checkOutAt)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Giờ công thay thế: ${adjustment.workedHoursOverride?.let { String.format(Locale("vi", "VN"), "%.2f giờ", it) } ?: "Giữ cách tính theo lượt chấm"}", style = MaterialTheme.typography.bodyMedium)
                            HistoryActor(adjustment.actorName, adjustment.createdAt, adjustment.reason)
                        }
                    }
                    item { HistoryHeading("Quyết định chấm ngoài lịch", complete.offScheduleReviews.size) }
                    if (complete.offScheduleReviews.isEmpty()) item { Text("Chưa có quyết định ngoài lịch.", style = MaterialTheme.typography.bodyMedium) }
                    items(complete.offScheduleReviews, key = { "review-${it.id}" }) { review ->
                        HistoryBlock {
                            Text("${if (review.decision == "APPROVE") "Duyệt vào ca" else "Từ chối"} · ${review.shiftName.ifBlank { review.shiftId }}", style = MaterialTheme.typography.titleSmall)
                            Text(when (review.status) { "FAILED" -> "Thất bại"; "APPROVED" -> "Đã duyệt"; "REJECTED" -> "Đã từ chối"; else -> "Đã lưu quyết định" }, style = MaterialTheme.typography.bodyMedium)
                            review.failureReason?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            HistoryActor(review.reviewerName, review.createdAt?.toDate()?.toInstant(), review.reason)
                        }
                    }
                    item { HistoryHeading("Nhật ký quản trị liên quan", complete.audits.size) }
                    if (complete.audits.isEmpty()) item { Text("Chưa có nhật ký ghép với các thay đổi này.", style = MaterialTheme.typography.bodyMedium) }
                    items(complete.audits, key = { "audit-${it.id}" }) { audit ->
                        HistoryBlock {
                            Text(when (audit.action) { "ATTENDANCE_CLASSIFICATION_CORRECT" -> "Sửa phân loại lượt chấm"; "ATTENDANCE_ADJUST" -> "Điều chỉnh giờ công"; "OFF_SCHEDULE_REVIEW" -> "Xử lý chấm ngoài lịch"; else -> audit.action }, style = MaterialTheme.typography.titleSmall)
                            HistoryActor(audit.actorName, audit.createdAt.toDate().toInstant(), audit.reason)
                        }
                    }
                }
            }
        },
        confirmButton = {
            history?.let { complete ->
                val scopedRow = row.copy(scheduleDate = complete.scheduleDate)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    TextButton(onClick = { onAdjust(scopedRow, complete) }, enabled = !busy && !loading) { Text("Điều chỉnh công") }
                    if (canCorrectAttendanceClassification(complete.source)) {
                        TextButton(onClick = { onClassify(scopedRow, complete) }, enabled = !busy && !loading) { Text("Sửa phân loại") }
                    }
                    if (row.type == "UNSCHEDULED" && row.offScheduleReviewStatus != "REJECTED") {
                        TextButton(onClick = { onReviewUnscheduled(scopedRow) }, enabled = !busy && !loading) { Text("Xử lý ngoài lịch") }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Đóng") } }
    )
}

@Composable
private fun HistoryHeading(title: String, count: Int) {
    Text("$title ($count)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = AppSpacing.small))
}

@Composable
private fun HistoryBlock(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) { content() }
    }
}

@Composable
private fun HistoryActor(name: String, time: Instant?, reason: String) {
    Text("${name.ifBlank { "Chưa rõ người thực hiện" }} · ${historyTime(time)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Lý do: ${reason.ifBlank { "Chưa ghi nhận" }}", style = MaterialTheme.typography.bodyMedium)
}
