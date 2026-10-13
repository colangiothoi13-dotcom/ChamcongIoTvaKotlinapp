package vn.chamcong.iot.ui.attendance

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import java.time.LocalDate
import vn.chamcong.iot.domain.latestAdjustment
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.ui.MainUiState

data class AttendanceAdjustmentTarget(
    val employeeId: String,
    val employeeName: String,
    val scheduleDate: String,
    val currentCheckIn: Timestamp?,
    val currentCheckOut: Timestamp?,
    val currentWorkedHours: Double?,
    val currentWorkedHoursOverride: Double? = null
)

@Composable
fun AttendanceAdjustmentDialog(
    target: AttendanceAdjustmentTarget,
    state: MainUiState,
    onDismiss: () -> Unit,
    onSubmit: (AttendanceAdjustment) -> Unit,
    selectorContent: (@Composable ColumnScope.() -> Unit)? = null,
    dataReady: Boolean = true,
    keepDraftOnTargetChange: Boolean = false
) {
    val draftKey = if (keepDraftOnTargetChange) "manual" else "${target.employeeId}|${target.scheduleDate}"
    var checkIn by rememberSaveable(draftKey) { mutableStateOf("") }
    var checkOut by rememberSaveable(draftKey) { mutableStateOf("") }
    var hours by rememberSaveable(draftKey) { mutableStateOf("") }
    var reason by rememberSaveable(draftKey) { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.employeeId, target.scheduleDate) { validationError = null }
    fun currentTime(value: Timestamp?) = value?.toDate()?.toInstant()?.atZone(attendanceZone)
        ?.format(adjustmentTimeFormat) ?: "Chưa có"

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Điều chỉnh công") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                selectorContent?.invoke(this)
                if (selectorContent == null) {
                    Text("${target.employeeName} (${target.employeeId})")
                    Text("Ngày ca: ${target.scheduleDate}")
                }
                if (dataReady) Text("Hiện tại — Vào: ${currentTime(target.currentCheckIn)}\nRa: ${currentTime(target.currentCheckOut)}\nGiờ công: ${target.currentWorkedHours ?: "Chưa có"}")
                Text("Múi giờ Asia/Ho_Chi_Minh. Giờ theo yyyy-MM-dd HH:mm. Để trống để giữ giá trị hiện tại; không dùng để xóa giờ chấm.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(checkIn, { checkIn = it; validationError = null }, Modifier.fillMaxWidth(),
                    label = { Text("Giờ vào (tùy chọn)") }, singleLine = true, enabled = !state.saving)
                OutlinedTextField(checkOut, { checkOut = it; validationError = null }, Modifier.fillMaxWidth(),
                    label = { Text("Giờ ra (tùy chọn)") }, singleLine = true, enabled = !state.saving)
                OutlinedTextField(hours, { hours = it; validationError = null }, Modifier.fillMaxWidth(),
                    label = { Text("Giờ công (0–24, tùy chọn)") }, singleLine = true, enabled = !state.saving)
                OutlinedTextField(reason, { reason = it; validationError = null }, Modifier.fillMaxWidth(),
                    label = { Text("Lý do (bắt buộc)") }, enabled = !state.saving)
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !state.saving && dataReady, shape = MaterialTheme.shapes.small, onClick = {
                if (!state.saving && dataReady) {
                    val parsed = runCatching {
                        parseAttendanceAdjustmentInput(checkIn, checkOut, hours, reason,
                            target.currentCheckIn?.toDate()?.toInstant(),
                            target.currentCheckOut?.toDate()?.toInstant(), target.currentWorkedHours)
                    }
                    parsed.onFailure { validationError = it.message }
                    parsed.onSuccess { input ->
                        // Latest adjustment replaces the previous record's effective overrides.
                        // Carry current endpoints forward so a blank field preserves a prior correction.
                        onSubmit(AttendanceAdjustment(
                            employeeId = target.employeeId, employeeName = target.employeeName,
                            scheduleDate = target.scheduleDate,
                            checkInAt = input.checkInAt ?: target.currentCheckIn?.toDate()?.toInstant(),
                            checkOutAt = input.checkOutAt ?: target.currentCheckOut?.toDate()?.toInstant(),
                            workedHoursOverride = input.workedHoursOverride ?: target.currentWorkedHoursOverride ?: latestAdjustment(
                                state.calculationAdjustments, target.employeeId, LocalDate.parse(target.scheduleDate)
                            )?.workedHoursOverride,
                            reason = input.reason
                        ))
                    }
                }
            }) { Text(if (state.saving) "Đang lưu…" else "Lưu điều chỉnh") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AttendanceAdjustmentDialogPreview() {
    PreviewStateScreen { state, vm -> AttendanceAdjustmentDialog(AttendanceAdjustmentTarget("nv-01", "Nguyễn Văn An", java.time.LocalDate.now().toString(), null, null, null), state, onDismiss = {}, onSubmit = {}) }
}
