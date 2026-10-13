package vn.chamcong.iot.ui.employees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.FaceEnrollmentAction
import vn.chamcong.iot.model.FaceEnrollmentCommand
import vn.chamcong.iot.model.FaceStation
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.Instant

internal fun faceStationBusy(station: FaceStation, commands: List<FaceEnrollmentCommand>): Boolean =
    commands.any { it.stationId == station.stationId && it.active } ||
        (station.pendingCommandId.isNotBlank() && commands.none {
            it.requestId == station.pendingCommandId && it.status in setOf("COMPLETED", "FAILED", "CANCELLED")
        })

@Composable
internal fun EmployeeFaceEnrollmentControls(
    employee: Employee,
    state: MainUiState,
    vm: MainViewModel,
    now: Instant,
    onAction: (FaceEnrollmentAction) -> Unit
) {
    val profile = state.faceProfiles.firstOrNull { it.employeeId == employee.id }
    val command = state.faceEnrollmentCommands.firstOrNull { it.employeeId == employee.id && it.active }
        ?: state.faceEnrollmentCommands.firstOrNull { it.employeeId == employee.id }
    val busy = command?.active == true
    Text(
        "Khuôn mặt: ${when {
            state.faceEnrollmentLoading -> "Đang tải…"
            state.faceEnrollmentError != null -> "Chưa xác định; lỗi kết nối"
            profile?.registered == true -> "Đã đăng ký · mẫu v${profile.version}"
            else -> "Chưa đăng ký"
        }}",
        style = MaterialTheme.typography.bodyMedium
    )
    command?.let {
        Text(it.statusLabel, style = MaterialTheme.typography.bodySmall,
            color = if (it.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (it.active) {
            if (it.status == "CAPTURING" && !it.cancelRequested && it.message.isNotBlank()) Text(
                it.message.take(300), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (it.action == "ENROLL" && it.sampleCount > 0) LinearProgressIndicator(
                progress = { (it.samplesCaptured.toFloat() / it.sampleCount).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            val online = state.faceStations.firstOrNull { station -> station.stationId == it.stationId }?.available(now) == true
            if (!online) Text("Laptop hoặc camera đang mất kết nối; tiến độ chưa được xác nhận.", style = MaterialTheme.typography.bodySmall)
            if (it.canCancel) TextButton({ vm.cancelFaceEnrollment(it.requestId) }, enabled = !state.saving,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Hủy yêu cầu khuôn mặt") }
        }
    }
    if (employee.active) FilledTonalButton(
        { onAction(FaceEnrollmentAction.ENROLL) },
        enabled = !state.saving && !busy && !state.faceEnrollmentLoading && state.faceEnrollmentError == null,
        modifier = Modifier.heightIn(min = 48.dp)
    ) { Text(if (profile?.registered == true) "Đăng ký lại khuôn mặt" else "Đăng ký khuôn mặt") }
    if (employee.active && profile?.registered == true) TextButton(
        { onAction(FaceEnrollmentAction.DELETE) },
        enabled = !state.saving && !busy && !state.faceEnrollmentLoading && state.faceEnrollmentError == null,
        modifier = Modifier.heightIn(min = 48.dp)
    ) { Text("Xóa mẫu khuôn mặt", color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun FaceEnrollmentDialog(
    employee: Employee,
    action: FaceEnrollmentAction,
    state: MainUiState,
    vm: MainViewModel,
    now: Instant,
    onDismiss: () -> Unit
) {
    var stationId by remember(employee.id, action) {
        mutableStateOf(state.faceStations.firstOrNull { it.available(now) && !faceStationBusy(it, state.faceEnrollmentCommands) }?.stationId.orEmpty())
    }
    var confirmed by remember(employee.id, action) { mutableStateOf(false) }
    var submittedId by remember(employee.id, action) { mutableStateOf<String?>(null) }
    val submitted = submittedId?.let { id -> state.faceEnrollmentCommands.firstOrNull { it.requestId == id } }
    val station = state.faceStations.firstOrNull { it.stationId == stationId }
    val ready = station?.available(now) == true && !faceStationBusy(station, state.faceEnrollmentCommands)
    val employeeBusy = state.faceEnrollmentCommands.any { it.employeeId == employee.id && it.active }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(if (action == FaceEnrollmentAction.ENROLL) "Đăng ký khuôn mặt" else "Xóa mẫu khuôn mặt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text("${employee.code} · ${employee.fullName}", style = MaterialTheme.typography.titleMedium)
                if (submittedId != null) {
                    Text(submitted?.statusLabel ?: "Đã gửi yêu cầu; đang tải tiến độ…",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (submitted?.active == true && submitted.action == "ENROLL") LinearProgressIndicator(
                        progress = { (submitted.samplesCaptured.toFloat() / submitted.sampleCount.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (submitted?.status == "CAPTURING" && !submitted.cancelRequested && submitted.message.isNotBlank()) Text(
                        submitted.message.take(300), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (submitted?.status in setOf("PENDING", "CAPTURING")) Text("Chỉ một người trước camera. Giữ mặt rõ, nhìn thẳng rồi xoay nhẹ theo hướng dẫn.")
                    if (submitted?.status == "COMPLETED") Text("Laptop đã xác nhận hoàn tất.")
                    if (submitted?.canCancel == true) TextButton({ vm.cancelFaceEnrollment(submitted.requestId) },
                        enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hủy yêu cầu") }
                } else {
                    Text(if (action == FaceEnrollmentAction.ENROLL)
                        "Thu 10 ảnh từ camera ESP32-S3. Tháo khẩu trang và kính (kính cận hoặc kính râm). Đặt camera ngang tầm mặt, giữ trọn khuôn mặt, cả trán và cằm trong ảnh và đủ sáng; nhìn thẳng rồi xoay nhẹ."
                    else "Mẫu nhận diện sẽ được xóa trên laptop và hồ sơ khuôn mặt sẽ bị vô hiệu hóa. Ảnh đăng ký trên laptop và lịch sử chấm công vẫn được giữ.")
                    Text("Chọn trạm camera", style = MaterialTheme.typography.labelLarge)
                    if (state.faceStations.isEmpty()) Text("Chưa có trạm. Mở dịch vụ nhận diện trên laptop ở chế độ Firebase.")
                    state.faceStations.sortedBy { it.stationId }.forEach { option ->
                        val busy = faceStationBusy(option, state.faceEnrollmentCommands)
                        val online = option.available(now)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = stationId == option.stationId,
                                onClick = { stationId = option.stationId; confirmed = false },
                                enabled = !state.saving && online && !busy)
                            Column(Modifier.weight(1f)) {
                                Text("${option.stationId} · ${option.faceDeviceId}")
                                Text(when {
                                    option.mode != "firebase" -> "Đang ở chế độ thử; chưa dùng cho app"
                                    !online -> "Chưa sẵn sàng: kiểm tra laptop và camera"
                                    busy -> "Đang có yêu cầu khác"
                                    else -> "Sẵn sàng${option.sensor.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!ready) Text("Chỉ gửi được khi laptop và camera đang sẵn sàng.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !state.saving)
                        Text(if (action == FaceEnrollmentAction.ENROLL)
                            "Tôi đã kiểm tra đúng nhân viên và chỉ một người đang đứng trước camera."
                        else "Tôi xác nhận xóa mẫu khuôn mặt của nhân viên này.")
                    }
                    if (employeeBusy) Text("Nhân viên đang có một yêu cầu khuôn mặt chưa hoàn tất.", color = MaterialTheme.colorScheme.error)
                    if (!employee.active) Text("Nhân viên đã nghỉ; không thể gửi yêu cầu khuôn mặt.", color = MaterialTheme.colorScheme.error)
                }
                state.faceEnrollmentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (submittedId == null) Button(
                onClick = { vm.requestFaceEnrollment(employee.id, stationId, action) { submittedId = it } },
                enabled = !state.saving && !state.faceEnrollmentLoading && state.faceEnrollmentError == null &&
                    ready && confirmed && !employeeBusy && employee.active,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (state.saving) "Đang gửi…" else if (action == FaceEnrollmentAction.ENROLL) "Bắt đầu thu ảnh" else "Xác nhận xóa") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.saving,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (submittedId == null) "Để sau" else "Đóng") } }
    )
}
