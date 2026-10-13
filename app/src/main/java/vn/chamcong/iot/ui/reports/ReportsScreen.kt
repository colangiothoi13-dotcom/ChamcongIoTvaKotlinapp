package vn.chamcong.iot.ui.reports

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalInspectionMode
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.retryAttendanceRange
import vn.chamcong.iot.ui.retryWorkItems

import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.domain.filterAttendanceReportRows
import vn.chamcong.iot.domain.validateReportFilter
import vn.chamcong.iot.domain.filterWorkItemsForReport
import vn.chamcong.iot.domain.workItemMetrics
import vn.chamcong.iot.domain.workSummaryReportRows
import vn.chamcong.iot.domain.filterEmployees
import vn.chamcong.iot.domain.payrollReportMonth
import vn.chamcong.iot.ui.money
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.attendanceStatusLabel
import vn.chamcong.iot.ui.deviceStatusLabel
import vn.chamcong.iot.ui.userFacingErrorMessage
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

@Composable
fun ReportsScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(ReportType.WORK_ITEMS) }
    var startText by remember { mutableStateOf(state.selectedWeekStart.toString()) }
    var endText by remember { mutableStateOf(state.selectedWeekStart.plusDays(6).toString()) }
    var employeeId by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf<String?>(null) }
    var choosingType by remember { mutableStateOf(false) }
    val filterResult = remember(startText, endText, employeeId, department, state.employees, type) {
        runCatching {
            ReportFilter(
                startDate = LocalDate.parse(startText),
                endDate = LocalDate.parse(endText),
                employeeId = resolveReportEmployeeId(employeeId, state.employees),
                department = department.trim().takeIf(String::isNotBlank)
            ).also { selected -> validateReportFilter(selected); if (type == ReportType.PAYROLL) payrollReportMonth(selected) }
        }
    }
    val filter = filterResult.getOrNull()
    val filterError = filterResult.exceptionOrNull()?.let { error ->
        if (error is DateTimeParseException) "Ngày báo cáo không hợp lệ. Nhập theo định dạng yyyy-MM-dd."
        else error.message?.takeIf(String::isNotBlank) ?: "Bộ lọc báo cáo không hợp lệ"
    }
    val needsAttendance = type !in setOf(ReportType.DEVICE_ACTIVITY, ReportType.WORK_ITEMS, ReportType.PAYROLL)
    val workItemsReady = !state.workItemsLoading && state.workItemsError == null
    val payrollReady = filter != null && !state.payrollLoading && state.payrollError == null &&
        state.payrollLoadedMonth == YearMonth.from(filter.startDate)
    val attendanceHistoryReady = type == ReportType.DEVICE_ACTIVITY ||
        (type == ReportType.WORK_ITEMS && workItemsReady) || (
        type == ReportType.PAYROLL && payrollReady
        ) || (needsAttendance && filter != null && state.hasCompleteCalculationRange(filter.startDate, filter.endDate, filter.employeeId)
        )
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(filter?.startDate, filter?.endDate, filter?.employeeId, needsAttendance) {
        if (!inPreview && needsAttendance) {
            filter?.let { vm.loadAttendanceRange(it.startDate, it.endDate, it.employeeId, force = true) }
        }
    }
    LaunchedEffect(type, filter?.startDate) {
        if (!inPreview && type == ReportType.PAYROLL) filter?.let { vm.selectPayrollMonth(YearMonth.from(it.startDate)) }
    }
    val attendanceRows = if (attendanceHistoryReady && needsAttendance) filter?.let(vm::reportAttendanceRows).orEmpty() else emptyList()
    val filteredAttendanceRows = filterAttendanceReportRows(attendanceRows, type)
    val deviceRows = if (type == ReportType.DEVICE_ACTIVITY) vm.reportDeviceRows() else emptyList()
    val workRows = if (type == ReportType.WORK_ITEMS && workItemsReady) filter?.let {
        filterWorkItemsForReport(state.workItems, it, state.employees)
    }.orEmpty() else emptyList()
    val payrollRows = if (type == ReportType.PAYROLL && payrollReady) filter?.let(vm::reportPayrollRows).orEmpty() else emptyList()
    val summaryRows = if (type == ReportType.WORK_SUMMARY && attendanceHistoryReady && filter != null)
        workSummaryReportRows(attendanceRows, filterEmployees(state.historicalEmployees(YearMonth.from(filter.startDate)), filter)) else emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            AdminPageHeader("Báo cáo & bảng công", "Đối chiếu công, nghỉ phép, tăng ca, lương và hoạt động thiết bị.")
        }
        item {
            AdminSectionCard {
                Text("Loại báo cáo", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { choosingType = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                        Text(reportTypeTitle(type), modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Chọn loại báo cáo")
                    }
                    DropdownMenu(expanded = choosingType, onDismissRequest = { choosingType = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        ReportType.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(reportTypeTitle(option)) },
                                onClick = { type = option; choosingType = false }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    TextButton({
                        startText = state.selectedWeekStart.toString()
                        endText = state.selectedWeekStart.plusDays(6).toString()
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tuần đang chọn") }
                    TextButton({
                        val month = LocalDate.now().withDayOfMonth(1)
                        startText = month.toString()
                        endText = month.withDayOfMonth(month.lengthOfMonth()).toString()
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tháng này") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), label = { Text("Từ ngày") }, singleLine = true)
                    OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), label = { Text("Đến ngày") }, singleLine = true)
                }
                Text("Ngày dùng định dạng yyyy-MM-dd.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(employeeId, { employeeId = it }, Modifier.fillMaxWidth(), label = { Text("Mã nhân viên") }, singleLine = true)
                OutlinedTextField(department, { department = it }, Modifier.fillMaxWidth(), label = { Text("Phòng ban") }, singleLine = true)
                if (type == ReportType.WORK_ITEMS) Text("Công việc được lọc theo hạn hoàn thành. Thời điểm hoàn thành là lúc quản lý duyệt.", style = MaterialTheme.typography.bodyMedium)
                if (type == ReportType.PAYROLL) Text("Xuất đúng phiếu lương đã lưu của tháng; dữ liệu công hiện tại không làm thay đổi phiếu.", style = MaterialTheme.typography.bodyMedium)
            filterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val selectedFilter = filter ?: return@Button
                    exportError = null
                    runCatching {
                        val csv = vm.reportCsv(type, selectedFilter)
                        shareCsv(context, csv, type.name.lowercase())
                    }.onFailure { exportError = "Không xuất được báo cáo. ${userFacingErrorMessage(it)}" }
                },
                enabled = filter != null && attendanceHistoryReady,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Xuất CSV") }
            Text("Chọn nơi lưu hoặc chia sẻ tệp CSV sau khi xuất.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (type == ReportType.WORK_ITEMS) {
                if (state.workItemsLoading) Text("Đang tải công việc…")
                state.workItemsError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retryWorkItems) { Text("Thử lại") }
                }
            }
            if (type == ReportType.PAYROLL) {
                if (state.payrollLoading) Text("Đang tải phiếu lương tháng…")
                state.payrollError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retryPayroll) { Text("Tải lại phiếu lương") }
                }
            }
            if (state.attendanceHistoryLoading && filter != null && needsAttendance) {
                Text("Đang tải dữ liệu chấm công theo khoảng ngày...", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.attendanceHistoryError != null && filter != null && needsAttendance) {
                Text(state.attendanceHistoryError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::retryAttendanceRange) { Text("Thử lại") }
            }
            if (state.attendanceHistoryTruncated && filter != null && needsAttendance) {
                Text(
                    "Khoảng chọn có quá nhiều bản ghi (tối đa ${state.attendanceHistoryLoadedCount}). Hãy thu hẹp khoảng ngày để xuất đầy đủ.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            }
        }
        item {
            Text(
                when (type) {
                    ReportType.DEVICE_ACTIVITY -> "${deviceRows.size} thiết bị"
                    ReportType.WORK_ITEMS -> "${workRows.size} công việc"
                    ReportType.PAYROLL -> "${payrollRows.size} phiếu lương đã lưu"
                    ReportType.WORK_SUMMARY -> "${summaryRows.size} nhân viên"
                    else -> "${filteredAttendanceRows.size} dòng báo cáo"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (type == ReportType.WORK_ITEMS) {
            item {
                val metrics = workItemMetrics(workRows)
                AdminSectionCard {
                    Text("Kết quả công việc", style = MaterialTheme.typography.titleMedium)
                    Text("Hoàn thành ${metrics.completed} · Đang làm ${metrics.inProgress} · Chờ duyệt ${metrics.pendingReview}")
                    Text("Đúng hạn ${metrics.completedOnTime} · Hoàn thành muộn ${metrics.completedLate} · Đang quá hạn ${metrics.overdue}")
                    Text("Tỷ lệ hoàn thành đúng hạn: ${if (metrics.completed == 0) "Chưa có việc hoàn thành" else "%.1f%%".format(metrics.onTimeCompletionRate * 100)}")
                }
            }
            items(workRows, key = { it.id }) { row ->
                AdminSectionCard {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text(row.assigneeName)
                        Text("Hạn: ${row.deadline.toDate().toInstant().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}")
                        Text(when (row.status) {
                            "ASSIGNED" -> "Được giao"
                            "IN_PROGRESS" -> "Đang thực hiện"
                            "PENDING_REVIEW" -> "Chờ duyệt"
                            "COMPLETED" -> "Hoàn thành"
                            else -> row.status
                        })
                        if (row.resultReport.isNotBlank()) Text("Kết quả: ${row.resultReport}")
                        if (row.managerFeedback.isNotBlank()) Text("Phản hồi: ${row.managerFeedback}")
                }
            }
        } else if (type == ReportType.DEVICE_ACTIVITY) {
            items(deviceRows, key = { it.deviceId }) { row ->
                AdminSectionCard {
                        Text(row.deviceId, style = MaterialTheme.typography.titleMedium)
                        Text("${deviceStatusLabel(row.status)} • Phần mềm ${row.firmwareVersion.ifBlank { "?" }}")
                        Text("Tín hiệu cuối: ${row.lastHeartbeat.ifBlank { "chưa có" }}")
                        Text("Vân tay: ${row.fingerprintCount ?: "?"}/${row.capacity ?: "?"} • Lệnh lỗi: ${row.failedCommandCount}")
                }
            }
        } else if (type == ReportType.PAYROLL) {
            items(payrollRows, key = { "${it.employeeId}_${it.month}" }) { row ->
                AdminSectionCard {
                        Text("${row.employeeCode} • ${row.employeeName}", style = MaterialTheme.typography.titleMedium)
                        Text("${row.month} • ${row.hoursWorked} giờ • ${money(row.hourlyRate)}/giờ")
                        Text("Lương cơ bản ${money(row.baseSalary)} • Thưởng ${money(row.bonus)} • Khấu trừ ${money(row.deduction)}")
                        Text("Thực nhận ${money(row.netSalary)} • Phiên bản ${row.revision}")
                }
            }
        } else if (type == ReportType.WORK_SUMMARY) {
            items(summaryRows, key = { it.employeeId }) { row ->
                AdminSectionCard {
                        Text("${row.employeeCode} • ${row.employeeName}", style = MaterialTheme.typography.titleMedium)
                        Text("Ngày có công ${row.workdays} • Giờ thường ${row.regularHours} h • Tăng ca ${row.overtimeHours} h")
                        Text("Ngày đi trễ ${row.lateDays} • Về sớm ${row.earlyDays} • Có nghỉ phép ${row.leaveDays} • Thiếu chấm ${row.missingDays}")
                }
            }
        } else {
            items(filteredAttendanceRows.take(200), key = { "${it.date}_${it.employeeId}" }) { row ->
                AdminSectionCard {
                        Text("${row.date} • ${row.employeeName}", style = MaterialTheme.typography.titleMedium)
                        Text("${row.department.ifBlank { "Chưa có phòng ban" }} • ${attendanceStatusLabel(row.status)}")
                        Text("Vào ${row.checkIn.ifBlank { "--:--" }} • Ra ${row.checkOut.ifBlank { "--:--" }}")
                        Text("Giờ làm ${row.workedHours}h • Tăng ca ${row.overtimeHours}h")
                        if (row.lateMinutes > 0 || row.earlyLeaveMinutes > 0) {
                            Text("Đi trễ ${row.lateMinutes} phút • Về sớm ${row.earlyLeaveMinutes} phút")
                        }
                }
            }
        }
    }
}

private fun reportTypeTitle(type: ReportType): String = when (type) {
    ReportType.WORK_ITEMS -> "Kết quả công việc"
    ReportType.ATTENDANCE -> "Chấm công theo ngày"
    ReportType.WORK_SUMMARY -> "Tổng hợp ngày công"
    ReportType.LATE_EARLY -> "Đi trễ / về sớm"
    ReportType.LEAVE -> "Nghỉ phép"
    ReportType.OVERTIME -> "Tăng ca"
    ReportType.PAYROLL -> "Bảng lương"
    ReportType.DEVICE_ACTIVITY -> "Hoạt động thiết bị"
}

internal fun shareCsv(context: Context, csv: String, name: String) {
    val directory = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(directory, "bao-cao-$name.csv").apply { writeText(csv, Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Chia sẻ báo cáo"))
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ReportsScreenPreview() {
    PreviewStateScreen { state, vm -> ReportsScreen(state, vm) }
}
