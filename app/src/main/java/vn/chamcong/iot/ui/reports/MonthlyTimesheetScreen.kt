package vn.chamcong.iot.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.ui.AppSpacing
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timesheetMonthFormatter = DateTimeFormatter.ofPattern("'Tháng' MM/yyyy", Locale("vi", "VN"))
private val timesheetDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("vi", "VN"))
private val timesheetTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val timesheetZone = ZoneId.of("Asia/Ho_Chi_Minh")

private data class TimesheetMetric(val label: String, val value: String)

@Composable
fun MonthlyTimesheetScreen(
    month: YearMonth,
    employees: List<Employee>,
    summariesForEmployee: (employeeId: String) -> List<EmployeeDaySummary>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthlyRows = employees.map { employee ->
        employee to summariesForEmployee(employee.id).filter { YearMonth.from(it.date) == month }
    }.filter { (employee, summaries) ->
        employee.active || summaries.any { summary ->
            summary.shiftSummaries.isNotEmpty() || summary.checkIn != null || summary.checkOut != null ||
                summary.workedSeconds > 0L || summary.overtimeSeconds > 0L ||
                summary.workedHours > 0.0 || summary.overtimeHours > 0.0
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Bảng chấm công theo tháng", style = MaterialTheme.typography.titleLarge)
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.small, vertical = AppSpacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onPreviousMonth) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Tháng trước")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(month.format(timesheetMonthFormatter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${monthlyRows.size} nhân viên",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNextMonth) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Tháng sau")
                        }
                    }
                }
            }
        }
        if (monthlyRows.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Không có nhân viên hoạt động hoặc có dữ liệu trong tháng này",
                        modifier = Modifier.padding(AppSpacing.large),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(monthlyRows, key = { it.first.id }) { (employee, summaries) ->
                EmployeeMonthlyTimesheetCard(employee, summaries)
            }
        }
    }
}

@Composable
private fun EmployeeMonthlyTimesheetCard(
    employee: Employee,
    summaries: List<EmployeeDaySummary>
) {
    var showDetails by remember(employee.id) { mutableStateOf(false) }
    val workedDays = summaries.count {
        it.workedHours > 0.0 || it.overtimeHours > 0.0 || it.checkIn != null || it.checkOut != null
    }
    val lateDays = summaries.count { it.lateMinutes > 0 || it.status == EmployeeAttendanceStatus.LATE }
    val lateMinutes = summaries.sumOf { it.lateMinutes.coerceAtLeast(0) }
    val earlyDays = summaries.count { it.earlyLeaveMinutes > 0 || it.status == EmployeeAttendanceStatus.EARLY_LEAVE }
    val earlyMinutes = summaries.sumOf { it.earlyLeaveMinutes.coerceAtLeast(0) }
    val shiftSummaries = summaries.flatMap { day -> day.shiftSummaries.map { day.date to it } }
    val missingShifts = shiftSummaries.count { (_, shift) ->
        shift.status == EmployeeAttendanceStatus.MISSING_CHECK_IN || shift.status == EmployeeAttendanceStatus.MISSING_CHECK_OUT
    }
    val abnormalShifts = shiftSummaries.count { (_, shift) -> shift.status == EmployeeAttendanceStatus.ABNORMAL }
    val leaveShifts = shiftSummaries.count { (_, shift) -> shift.status == EmployeeAttendanceStatus.LEAVE }
    val regularHours = summaries.sumOf { summary ->
        if (summary.workedSeconds > 0L) summary.workedSeconds / 3600.0 else summary.workedHours.coerceAtLeast(0.0)
    }
    val overtimeHours = summaries.sumOf { summary ->
        if (summary.overtimeSeconds > 0L) summary.overtimeSeconds / 3600.0 else summary.overtimeHours.coerceAtLeast(0.0)
    }
    val metrics = listOf(
        TimesheetMetric("Giờ thường", "${regularHours.formatHours()} giờ"),
        TimesheetMetric("Giờ tăng ca", "${overtimeHours.formatHours()} giờ"),
        TimesheetMetric("Ngày công", workedDays.toString()),
        TimesheetMetric("Đi trễ", "$lateDays ngày · $lateMinutes phút"),
        TimesheetMetric("Về sớm", "$earlyDays ngày · $earlyMinutes phút"),
        TimesheetMetric("Thiếu chấm công", "$missingShifts ca"),
        TimesheetMetric("Bất thường", "$abnormalShifts ca"),
        TimesheetMetric("Nghỉ phép", "$leaveShifts ca")
    )

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Text(
                employee.fullName.ifBlank { "Nhân viên" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val employeeDetail = listOf(employee.code, employee.department)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            if (employeeDetail.isNotBlank()) {
                Text(employeeDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            metrics.chunked(2).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    rowMetrics.forEach { metric ->
                        TimesheetMetricCell(metric, Modifier.weight(1f))
                    }
                    if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (shiftSummaries.isNotEmpty()) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Ẩn chi tiết theo ca" else "Xem chi tiết theo ngày và ca")
                }
            }
            if (showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    summaries.filter { it.shiftSummaries.isNotEmpty() }.forEach { day ->
                        Text(
                            day.date.format(timesheetDateFormatter),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        day.shiftSummaries.forEach { shift ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppSpacing.medium),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier.padding(AppSpacing.medium),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(shift.shiftName.ifBlank { "Ca làm" }, fontWeight = FontWeight.SemiBold)
                                        Text("${shift.shiftStartTime}–${shift.shiftEndTime}")
                                    }
                                    Text(
                                        "Quét: ${shift.rawCheckInAt.formatLocalTime()} → ${shift.rawCheckOutAt.formatLocalTime()}  ·  " +
                                            "Tính công: ${shift.paidCheckInAt.formatLocalTime()} → ${shift.paidCheckOutAt.formatLocalTime()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Thường ${shift.workedHours.formatHours()} giờ  ·  " +
                                            "Tăng ca ${shift.overtimeHours.formatHours()} giờ  ·  ${shift.status.toVietnameseLabel()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimesheetMetricCell(metric: TimesheetMetric, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppSpacing.medium),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun Double.formatHours(): String = String.format(Locale.US, "%.2f", this)

private fun Instant?.formatLocalTime(): String =
    this?.atZone(timesheetZone)?.format(timesheetTimeFormatter) ?: "—"

private fun EmployeeAttendanceStatus.toVietnameseLabel(): String = when (this) {
    EmployeeAttendanceStatus.PRESENT -> "Đang làm"
    EmployeeAttendanceStatus.ON_TIME -> "Đúng giờ"
    EmployeeAttendanceStatus.LATE -> "Đi trễ"
    EmployeeAttendanceStatus.EARLY_LEAVE -> "Về sớm"
    EmployeeAttendanceStatus.ABNORMAL -> "Bất thường"
    EmployeeAttendanceStatus.MISSING_CHECK_IN -> "Thiếu chấm vào"
    EmployeeAttendanceStatus.MISSING_CHECK_OUT -> "Thiếu chấm ra"
    EmployeeAttendanceStatus.LEAVE -> "Nghỉ phép"
}
