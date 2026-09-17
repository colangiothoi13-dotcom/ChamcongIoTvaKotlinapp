package vn.chamcong.iot.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val currentWorkedHours: Double?
)

@Composable
fun AttendanceAdjustmentDialog(
    target: AttendanceAdjustmentTarget,
    state: MainUiState,
    onDismiss: () -> Unit,
    onSubmit: (AttendanceAdjustment) -> Unit
) {
    var checkIn by rememberSaveable(target.employeeId, target.scheduleDate) { mutableStateOf("") }
    var checkOut by rememberSaveable(target.employeeId, target.scheduleDate) { mutableStateOf("") }
    var hours by rememberSaveable(target.employeeId, target.scheduleDate) { mutableStateOf("") }
    var reason by rememberSaveable(target.employeeId, target.scheduleDate) { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    fun currentTime(value: Timestamp?) = value?.toDate()?.toInstant()?.atZone(attendanceZone)
        ?.format(adjustmentTimeFormat) ?: "Chưa có"

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Điều chỉnh chấm công") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${target.employeeName} (${target.employeeId})")
                Text("Ngày ca: ${target.scheduleDate}")
                Text("Hiện tại — Vào: ${currentTime(target.currentCheckIn)}\nRa: ${currentTime(target.currentCheckOut)}\nGiờ công: ${target.currentWorkedHours ?: "Chưa có"}")
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
            Button(enabled = !state.saving, onClick = {
                if (!state.saving) {
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
                            workedHoursOverride = input.workedHoursOverride ?: latestAdjustment(
                                state.attendanceAdjustments, target.employeeId, LocalDate.parse(target.scheduleDate)
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
