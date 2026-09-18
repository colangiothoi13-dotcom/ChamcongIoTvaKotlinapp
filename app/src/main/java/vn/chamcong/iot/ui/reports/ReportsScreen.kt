package vn.chamcong.iot.ui.reports

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.attendanceStatusLabel
import vn.chamcong.iot.ui.deviceStatusLabel
import java.io.File
import java.time.LocalDate

@Composable
fun ReportsScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(ReportType.ATTENDANCE) }
    var startText by remember { mutableStateOf(state.selectedWeekStart.toString()) }
    var endText by remember { mutableStateOf(state.selectedWeekStart.plusDays(6).toString()) }
    var employeeId by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    val filter = remember(startText, endText, employeeId, department) {
        runCatching {
            ReportFilter(
                startDate = LocalDate.parse(startText),
                endDate = LocalDate.parse(endText),
                employeeId = employeeId.trim().takeIf(String::isNotBlank),
                department = department.trim().takeIf(String::isNotBlank)
            )
        }.getOrNull()
    }
    val attendanceRows = filter?.let(vm::reportAttendanceRows).orEmpty().filter { row ->
        when (type) {
            ReportType.LATE_EARLY -> row.status == "LATE" || row.status == "EARLY_LEAVE"
            ReportType.LEAVE -> row.status == "LEAVE"
            ReportType.OVERTIME -> row.overtimeHours > 0
            else -> true
        }
    }
    val deviceRows = if (type == ReportType.DEVICE_ACTIVITY) vm.reportDeviceRows() else emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Báo cáo", style = MaterialTheme.typography.titleLarge)
            Text("Lọc theo ngày, nhân viên hoặc phòng ban; ngày dùng định dạng yyyy-MM-dd.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({
                    startText = state.selectedWeekStart.toString()
                    endText = state.selectedWeekStart.plusDays(6).toString()
                }) { Text("Tuần đang chọn") }
                TextButton({
                    val month = LocalDate.now().withDayOfMonth(1)
                    startText = month.toString()
                    endText = month.withDayOfMonth(month.lengthOfMonth()).toString()
                }) { Text("Tháng này") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), label = { Text("Từ ngày") }, singleLine = true)
                OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), label = { Text("Đến ngày") }, singleLine = true)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(employeeId, { employeeId = it }, Modifier.weight(1f), label = { Text("Mã nhân viên") }, singleLine = true)
                OutlinedTextField(department, { department = it }, Modifier.weight(1f), label = { Text("Phòng ban") }, singleLine = true)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReportType.entries.forEach { option ->
                    FilterChip(
                        selected = option == type,
                        onClick = { type = option },
                        label = { Text(reportTypeTitle(option), maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
        item {
            val invalid = filter == null
            if (invalid) Text("Khoảng ngày không hợp lệ", color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    val csv = filter?.let { vm.reportCsv(type, it) } ?: return@Button
                    shareCsv(context, csv, type.name.lowercase())
                },
                enabled = filter != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Xuất CSV và chia sẻ") }
        }
        item {
            Text(
                if (type == ReportType.DEVICE_ACTIVITY) "${deviceRows.size} thiết bị" else "${attendanceRows.size} dòng báo cáo",
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (type == ReportType.DEVICE_ACTIVITY) {
            items(deviceRows, key = { it.deviceId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.deviceId, style = MaterialTheme.typography.titleMedium)
                        Text("${deviceStatusLabel(row.status)} • Phần mềm ${row.firmwareVersion.ifBlank { "?" }}")
                        Text("Tín hiệu cuối: ${row.lastHeartbeat.ifBlank { "chưa có" }}")
                        Text("Vân tay: ${row.fingerprintCount ?: "?"}/${row.capacity ?: "?"} • Lệnh lỗi: ${row.failedCommandCount}")
                    }
                }
            }
        } else {
            items(attendanceRows.take(200), key = { "${it.date}_${it.employeeId}" }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.date} • ${row.employeeName}", style = MaterialTheme.typography.titleMedium)
                        Text("${row.department.ifBlank { "Chưa có phòng ban" }} • ${attendanceStatusLabel(row.status)}")
                        Text("Vào ${row.checkIn.ifBlank { "--:--" }} • Ra ${row.checkOut.ifBlank { "--:--" }}")
                        Text("Giờ làm ${row.workedHours}h • Tăng ca ${row.overtimeHours}h")
                    }
                }
            }
        }
    }
}

private fun reportTypeTitle(type: ReportType): String = when (type) {
    ReportType.ATTENDANCE -> "Chấm công"
    ReportType.WORK_SUMMARY -> "Ngày công"
    ReportType.LATE_EARLY -> "Trễ/sớm"
    ReportType.LEAVE -> "Nghỉ phép"
    ReportType.OVERTIME -> "Tăng ca"
    ReportType.DEVICE_ACTIVITY -> "Thiết bị"
}

private fun shareCsv(context: Context, csv: String, name: String) {
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
