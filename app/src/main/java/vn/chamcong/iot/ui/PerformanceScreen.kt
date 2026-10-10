package vn.chamcong.iot.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalInspectionMode
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.workItemMetrics
import vn.chamcong.iot.model.WorkItemMetrics
import vn.chamcong.iot.ui.workitems.currentWorkTime
import java.time.YearMonth
import java.util.Locale
import java.time.ZoneId

private fun performanceHoursText(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
internal fun PerformanceScreen(state: MainUiState, vm: MainViewModel) {
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    var showAttendanceSupport by remember { mutableStateOf(false) }
    val now by currentWorkTime()
    val validMonth = Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
    val selectedMonth = if (validMonth) YearMonth.parse(month) else null
    val inPreview = LocalInspectionMode.current
    val calculationReady = selectedMonth?.let {
        state.hasCompleteCalculationRange(it.atDay(1), it.atEndOfMonth())
    } == true
    LaunchedEffect(selectedMonth, showAttendanceSupport) {
        if (!inPreview && showAttendanceSupport) selectedMonth?.let {
            vm.loadAttendanceRange(it.atDay(1), it.atEndOfMonth(), force = true)
        }
    }
    val breakdowns = if (showAttendanceSupport && (calculationReady || inPreview)) selectedMonth?.let(vm::kpiBonusBreakdowns).orEmpty() else emptyMap()
    val monthWork = remember(state.workItems, selectedMonth) {
        state.workItems.filter { YearMonth.from(it.deadline.toDate().toInstant().atZone(ZoneId.of("Asia/Ho_Chi_Minh"))) == selectedMonth }
    }
    val employees = selectedMonth?.let(state::historicalEmployees).orEmpty()
    val employeeIds = (employees.map { it.id } + monthWork.map { it.assigneeId }).distinct()
    val workByEmployee = remember(monthWork) { monthWork.groupBy { it.assigneeId } }
    val workReady = !state.workItemsLoading && state.workItemsError == null

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        OutlinedTextField(
            value = month,
            onValueChange = { month = it },
            label = { Text("Tháng hiệu suất (yyyy-MM)") },
            singleLine = true,
            isError = !validMonth,
            modifier = Modifier.fillMaxWidth()
        )
        if (!validMonth) {
            Text("Tháng không hợp lệ", color = MaterialTheme.colorScheme.error)
        }
        Text("Công việc có hạn trong tháng. Đúng hạn / muộn tính theo lúc quản lý duyệt hoàn thành.", style = MaterialTheme.typography.bodySmall)
        if (state.workItemsLoading) Text("Đang tải dữ liệu hiệu suất công việc…")
        state.workItemsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryWorkItems) { Text("Thử lại công việc") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hiện chấm công và tăng ca hỗ trợ", modifier = Modifier.weight(1f))
            Switch(checked = showAttendanceSupport, onCheckedChange = { showAttendanceSupport = it })
        }
        if (showAttendanceSupport && validMonth && !calculationReady && !inPreview) {
            Text("Đang chờ đủ dữ liệu lịch, lượt quét, nghỉ phép, tăng ca và điều chỉnh của tháng.", style = MaterialTheme.typography.bodySmall)
            state.attendanceHistoryError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::retryAttendanceRange) { Text("Thử lại") }
            }
            if (state.attendanceHistoryTruncated) {
                Text("Dữ liệu tháng chưa đầy đủ nên chưa thể tính KPI.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (showAttendanceSupport) Text("Thưởng chấm công và tăng ca là dữ liệu hỗ trợ; hiệu suất công việc dựa trên kết quả được duyệt.", style = MaterialTheme.typography.bodySmall)
        }
        }
        if (employeeIds.isEmpty()) {
            item { Text("Chưa có nhân viên để tính hiệu suất") }
        }
        if (validMonth) {
            items(employeeIds, key = { it }) { employeeId ->
                    val employee = employees.firstOrNull { it.id == employeeId }
                        ?: state.employees.firstOrNull { it.id == employeeId }
                    val name = employee?.fullName?.ifBlank { employeeId }
                        ?: workByEmployee[employeeId]?.firstOrNull()?.assigneeName?.ifBlank { employeeId } ?: employeeId
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        if (workReady) WorkPerformanceCard(name, employee?.code.orEmpty(), workItemMetrics(workByEmployee[employeeId].orEmpty(), now))
                        if (showAttendanceSupport && (calculationReady || inPreview)) {
                            PerformanceCard(name, breakdowns[employeeId] ?: KpiBonusBreakdown())
                        }
                    }
                }
        }
    }
}

@Composable
private fun WorkPerformanceCard(name: String, code: String, metrics: WorkItemMetrics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("${code.ifBlank { "—" }} • $name", style = MaterialTheme.typography.titleMedium)
            Text("Được giao: ${metrics.assigned} • Hoàn thành: ${metrics.completed}")
            Text("Đang thực hiện: ${metrics.inProgress} • Chờ duyệt: ${metrics.pendingReview}")
            Text("Hoàn thành đúng hạn: ${metrics.completedOnTime} • Hoàn thành muộn: ${metrics.completedLate}")
            Text("Đang quá hạn: ${metrics.overdue}", color = if (metrics.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Việc phải làm lại: ${metrics.reworked} • Số lần yêu cầu làm lại: ${metrics.reworkCount}")
            Text(if (metrics.completed > 0) "Tỷ lệ đúng hạn: ${String.format(Locale.US, "%.1f", metrics.onTimeCompletionRate * 100)}% (${metrics.completedOnTime}/${metrics.completed} việc đã hoàn thành)"
                else "Tỷ lệ đúng hạn: chưa có công việc hoàn thành để tính", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun PerformanceCard(name: String, breakdown: KpiBonusBreakdown) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Chấm công hỗ trợ • $name", style = MaterialTheme.typography.titleMedium)
            Text(
                breakdown.top3Rank?.let { "Top 3: hạng $it" }
                    ?: if (breakdown.lateCount > 0) {
                        "Top 3: không đủ điều kiện vì có ${breakdown.lateCount} lần đi muộn"
                    } else {
                        "Top 3: chưa được xếp hạng theo số ca tăng ca"
                    }
            )
            Text("Ca tăng ca: ${breakdown.overtimeShiftCount} ca • ${performanceHoursText(breakdown.overtimeHours)} giờ")
            Text("Đi muộn: ${breakdown.lateCount} lần")
            Text("Thưởng Top 3: ${money(breakdown.top3Bonus)}")
            Text("Thưởng theo ca: ${money(breakdown.overtimeBonus)}")
            Text("Phạt đi muộn: ${money(breakdown.latePenalty)}")
            Text("Tổng thưởng tự động: ${money(breakdown.totalBonus)}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PerformanceScreenPreview() {
    PreviewStateScreen { state, vm -> PerformanceScreen(state, vm) }
}
