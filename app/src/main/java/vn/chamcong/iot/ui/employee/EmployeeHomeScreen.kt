package vn.chamcong.iot.ui.employee

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy")

@Composable
fun EmployeeHomeScreen(state: MainUiState, vm: MainViewModel) {
    val employee = state.currentEmployee
    if (employee == null) {
        EmptyEmployeeLinkState()
        return
    }
    val today = LocalDate.now(zone)
    val summaries = vm.employeeMonthSummaries(today)
    val todaySummary = summaries.firstOrNull { it.date == today }
    val workedDays = summaries.count { it.workedHours > 0.0 }
    val totalHours = summaries.sumOf { it.workedHours }
    val lateCount = summaries.count { it.status == EmployeeAttendanceStatus.LATE || it.status == EmployeeAttendanceStatus.ABNORMAL }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            Text("Xin chào, ${employee.fullName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(today.format(dateFormatter).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("Hôm nay", style = MaterialTheme.typography.titleLarge)
                    Text("Ca làm: ${todaySummary?.shiftName?.ifBlank { "Chưa được phân ca" } ?: "Chưa được phân ca"}")
                    Text("Khung giờ: ${todaySummary?.shiftStartTime?.takeIf(String::isNotBlank) ?: "—"} – ${todaySummary?.shiftEndTime?.takeIf(String::isNotBlank) ?: "—"}")
                    Text("Giờ vào: ${todaySummary?.checkIn?.let(::formatTime) ?: "Chưa chấm"}")
                    Text("Giờ ra: ${todaySummary?.checkOut?.let(::formatTime) ?: "Chưa chấm"}")
                    Text("Trạng thái: ${todaySummary?.status?.toVietnamese() ?: "Chưa có dữ liệu"}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                MiniStat("Giờ làm tháng", "%.2f".format(totalHours), Modifier.weight(1f))
                MiniStat("Ngày đi làm", workedDays.toString(), Modifier.weight(1f))
                MiniStat("Lần đi trễ", lateCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyEmployeeLinkState() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Chưa liên kết hồ sơ nhân viên", style = MaterialTheme.typography.titleLarge)
            Text("Hãy liên hệ Admin để gán users/{uid}.employeeId vào hồ sơ của bạn.")
        }
    }
}

internal fun EmployeeAttendanceStatus.toVietnamese(): String = when (this) {
    EmployeeAttendanceStatus.PRESENT -> "Đang làm việc"
    EmployeeAttendanceStatus.ON_TIME -> "Đủ công"
    EmployeeAttendanceStatus.LATE -> "Đi trễ"
    EmployeeAttendanceStatus.EARLY_LEAVE -> "Về sớm"
    EmployeeAttendanceStatus.ABNORMAL -> "Bất thường"
    EmployeeAttendanceStatus.MISSING_CHECK_IN -> "Chưa chấm vào"
    EmployeeAttendanceStatus.MISSING_CHECK_OUT -> "Chưa chấm ra"
    EmployeeAttendanceStatus.LEAVE -> "Nghỉ phép"
}

internal fun formatTime(value: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(value)
