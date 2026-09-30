package vn.chamcong.iot.ui.employee

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val monthFormatter = DateTimeFormatter.ofPattern("MM/yyyy")
private val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")

@Composable
fun EmployeeAttendanceScreen(
    state: MainUiState,
    vm: MainViewModel,
    onOpenRequests: () -> Unit = {},
    onOpenPayroll: () -> Unit = {}
) {
    var month by remember { mutableStateOf(LocalDate.now(zone).withDayOfMonth(1)) }
    val summaries = vm.employeeMonthSummaries(month).filter { summary ->
        summary.workedHours > 0.0 || summary.overtimeHours > 0.0 || summary.checkIn != null || summary.checkOut != null ||
            summary.shiftName.isNotBlank() || summary.status.name in listOf("LEAVE", "ABNORMAL")
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Tháng trước") }
            Text("Lịch sử chấm công • ${month.format(monthFormatter)}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = AppSpacing.medium))
            IconButton({ month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Tháng sau") }
        }
        Button(onClick = onOpenRequests, modifier = Modifier.fillMaxWidth()) { Text("Gửi yêu cầu điều chỉnh") }
        Button(onClick = onOpenPayroll, modifier = Modifier.fillMaxWidth()) { Text("Xem phiếu lương") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            if (summaries.isEmpty()) item { Text("Chưa có lịch hoặc lượt chấm công trong tháng này") }
            items(summaries, key = { it.date.toString() }) { summary -> EmployeeDayCard(summary) }
        }
    }
}

@Composable
private fun EmployeeDayCard(summary: EmployeeDaySummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(summary.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), style = MaterialTheme.typography.titleMedium)
            Text("Ca: ${summary.shiftName.ifBlank { "Chưa phân ca" }}")
            Text("Vào: ${summary.checkIn?.let(::formatTime) ?: "—"}    Ra: ${summary.checkOut?.let(::formatTime) ?: "—"}")
            Text(
                "Giờ thường: %.2f giờ • Tăng ca: %.2f giờ • ${summary.status.toVietnamese()}"
                    .format(summary.workedHours, summary.overtimeHours)
            )
            if (summary.lateMinutes > 0 || summary.earlyLeaveMinutes > 0) {
                Text("Đi trễ: ${summary.lateMinutes} phút • Về sớm: ${summary.earlyLeaveMinutes} phút", style = MaterialTheme.typography.bodySmall)
            }
            summary.shiftSummaries.forEach { shift ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(AppSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
                    ) {
                        Text("${shift.shiftName} · ${shift.shiftStartTime}–${shift.shiftEndTime}", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            "Quét: ${shift.rawCheckInAt?.let(::formatTime) ?: "—"} → ${shift.rawCheckOutAt?.let(::formatTime) ?: "—"}  ·  " +
                                "Tính công: ${shift.paidCheckInAt?.let(::formatTime) ?: "—"} → ${shift.paidCheckOutAt?.let(::formatTime) ?: "—"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Thường %.2f giờ · Tăng ca %.2f giờ · ${shift.status.toVietnamese()}".format(
                                shift.workedHours, shift.overtimeHours
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
