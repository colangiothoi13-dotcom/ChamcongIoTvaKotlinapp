package vn.chamcong.iot.ui.reports

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.terminationLocalDate
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import vn.chamcong.iot.ui.AdminMetricCard
import vn.chamcong.iot.ui.userFacingErrorMessage
import vn.chamcong.iot.data.monthlyTimesheetRowsToCsv
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
    val context = LocalContext.current
    var exportError by remember(month) { mutableStateOf<String?>(null) }
    val monthlyRows = employees.mapNotNull { employee ->
        val retirementDate = employee.terminationLocalDate()
        val lastEmploymentMonth = retirementDate?.let { YearMonth.from(it) }
            ?: if (!employee.active) YearMonth.now(timesheetZone) else null
        if (lastEmploymentMonth != null && lastEmploymentMonth.isBefore(month)) return@mapNotNull null
        val summaries = summariesForEmployee(employee.id).filter { summary ->
            YearMonth.from(summary.date) == month && (retirementDate == null || !summary.date.isAfter(retirementDate))
        }
        val hasAttendanceOrSchedule = summaries.any { summary ->
            summary.shiftSummaries.isNotEmpty() || summary.checkIn != null || summary.checkOut != null ||
                summary.workedSeconds > 0L || summary.overtimeSeconds > 0L ||
                summary.workedHours > 0.0 || summary.overtimeHours > 0.0
        }
        if (employee.active || retirementDate != null || hasAttendanceOrSchedule) employee to summaries else null
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                AdminPageHeader("Bảng công tháng", "Đối chiếu giờ thường, tăng ca và chi tiết theo ngày, ca của từng nhân viên.")
                AdminSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onPreviousMonth, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Tháng trước")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(month.format(timesheetMonthFormatter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${monthlyRows.size} nhân viên",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNextMonth, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Tháng sau")
                        }
                    }
                    Button(enabled = monthlyRows.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, onClick = {
                        exportError = null
                        runCatching { shareCsv(context, monthlyTimesheetRowsToCsv(month, monthlyRows), "bang-cong-$month") }
                            .onFailure { exportError = userFacingErrorMessage(it) }
                    }) { Text("Xuất CSV") }
                    exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (monthlyRows.isNotEmpty()) item {
            val summaries = monthlyRows.flatMap { it.second }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Giờ thường", summaries.sumOf { if (it.workedSeconds > 0L) it.workedSeconds / 3600.0 else it.workedHours.coerceAtLeast(0.0) }.formatHours(),
                        "${monthlyRows.size} nhân viên", Modifier.weight(1f))
                    AdminMetricCard("Giờ tăng ca", summaries.sumOf { if (it.overtimeSeconds > 0L) it.overtimeSeconds / 3600.0 else it.overtimeHours.coerceAtLeast(0.0) }.formatHours(),
                        "Theo ca và đơn đã duyệt", Modifier.weight(1f))
                }
                AdminMetricCard("Đi trễ / về sớm", summaries.count { it.lateMinutes > 0 || it.earlyLeaveMinutes > 0 }.toString(), "Ngày có phát sinh")
            }
        }
        if (monthlyRows.isEmpty()) {
            item {
                AdminSectionCard {
                    Text(
                        "Không có nhân viên hoạt động hoặc có dữ liệu trong tháng này",
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

    AdminSectionCard {
            Text(
                employee.fullName.ifBlank { "Nhân viên" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!employee.active) {
                val retirementDate = employee.terminationLocalDate()
                Text(
                    retirementDate?.let { "Ngày nghỉ việc: ${it.format(timesheetDateFormatter)}" }
                        ?: "Đã nghỉ · chưa ghi nhận ngày nghỉ việc",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val employeeDetail = listOf(employee.code, employee.department)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            if (employeeDetail.isNotBlank()) {
                Text(employeeDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showDetails) "Ẩn chi tiết ngày / ca" else "Chi tiết ngày / ca")
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
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Column(
                                    modifier = Modifier.padding(AppSpacing.medium),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(shift.shiftName.ifBlank { "Ca làm" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        Text("${shift.shiftStartTime}–${shift.shiftEndTime}")
                                    }
                                    Text(
                                        "Quét: ${shift.rawCheckInAt.formatLocalTime()} → ${shift.rawCheckOutAt.formatLocalTime()}  ·  " +
                                            "Tính công: ${shift.paidCheckInAt.formatLocalTime()} → ${shift.paidCheckOutAt.formatLocalTime()}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "Thường ${shift.workedHours.formatHours()} giờ  ·  " +
                                            "Tăng ca ${shift.overtimeHours.formatHours()} giờ  ·  ${shift.status.toVietnameseLabel()}",
                                        style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun TimesheetMetricCell(metric: TimesheetMetric, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.background
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MonthlyTimesheetScreenPreview() {
    PreviewStateScreen { state, vm -> MonthlyTimesheetScreen(month = java.time.YearMonth.now(), employees = state.employees, summariesForEmployee = { listOf(vn.chamcong.iot.model.EmployeeDaySummary(date = java.time.LocalDate.now(), shiftName = "Ca sáng", workedHours = 4.0)) }, onPreviousMonth = {}, onNextMonth = {}) }
}
