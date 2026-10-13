package vn.chamcong.iot.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.workItemMetrics
import vn.chamcong.iot.model.WorkItemMetrics
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.PerformanceReview
import vn.chamcong.iot.domain.performanceReportRows
import vn.chamcong.iot.data.performanceRowsToCsv
import vn.chamcong.iot.ui.reports.shareCsv
import vn.chamcong.iot.ui.workitems.currentWorkTime
import java.time.YearMonth
import java.util.Locale
import java.time.ZoneId

private fun performanceHoursText(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
internal fun PerformanceScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var exportError by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    var showAttendanceSupport by remember { mutableStateOf(false) }
    var reviewingEmployee by remember { mutableStateOf<Employee?>(null) }
    var editingReview by remember { mutableStateOf<PerformanceReview?>(null) }
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
            AdminPageHeader("Hiệu suất", "Kết quả công việc tính theo lúc quản lý duyệt hoàn thành; công và tăng ca hỗ trợ tính thưởng.")
        }
        item {
        AdminSectionCard {
        OutlinedTextField(
            value = month,
            onValueChange = { month = it },
            label = { Text("Tháng (yyyy-MM)") },
            singleLine = true,
            isError = !validMonth,
            modifier = Modifier.fillMaxWidth()
        )
        if (!validMonth) {
            Text("Tháng không hợp lệ", color = MaterialTheme.colorScheme.error)
        }
        Text("Công việc có hạn trong tháng. Đúng hạn / muộn tính theo lúc quản lý duyệt hoàn thành.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = selectedMonth != null && workReady && !state.performanceReviewsLoading && state.performanceReviewsError == null,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
            onClick = {
                exportError = null
                selectedMonth?.let { period ->
                    runCatching { shareCsv(context, performanceRowsToCsv(performanceReportRows(employees, state.workItems,
                        state.performanceReviews, period, now, state.employees)), "hieu-suat-$period") }
                        .onFailure { exportError = userFacingErrorMessage(it) }
                }
            }) { Text("Xuất CSV") }
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.performanceReviewsLoading) Text("Đang tải đánh giá đã lưu…", style = MaterialTheme.typography.bodyMedium)
        state.performanceReviewsError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryPerformanceReviews) { Text("Tải lại đánh giá") }
        }
        if (state.workItemsLoading) Text("Đang tải dữ liệu hiệu suất công việc…")
        state.workItemsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryWorkItems) { Text("Thử lại công việc") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chấm công và tăng ca hỗ trợ", modifier = Modifier.weight(1f))
            Switch(checked = showAttendanceSupport, onCheckedChange = { showAttendanceSupport = it })
        }
        if (showAttendanceSupport && validMonth && !calculationReady && !inPreview) {
            Text("Đang chờ đủ dữ liệu lịch, lượt quét, nghỉ phép, tăng ca và điều chỉnh của tháng.", style = MaterialTheme.typography.bodyMedium)
            state.attendanceHistoryError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::retryAttendanceRange) { Text("Thử lại") }
            }
            if (state.attendanceHistoryTruncated) {
                Text("Dữ liệu tháng chưa đầy đủ nên chưa thể tính KPI.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (showAttendanceSupport) Text("Thưởng chấm công và tăng ca là dữ liệu hỗ trợ; hiệu suất công việc dựa trên kết quả được duyệt.", style = MaterialTheme.typography.bodyMedium)
        }
        }
        if (validMonth) item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                Text("Kết quả công việc", style = MaterialTheme.typography.titleMedium)
                Text("${employeeIds.size} nhân viên · Công việc có hạn hoàn thành trong tháng $month.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (employeeIds.isEmpty()) {
            item { AdminSectionCard { Text("Chưa có nhân viên để tính hiệu suất", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        if (validMonth) {
            items(employeeIds, key = { it }) { employeeId ->
                    val employee = employees.firstOrNull { it.id == employeeId }
                        ?: state.employees.firstOrNull { it.id == employeeId }
                    val name = employee?.fullName?.ifBlank { employeeId }
                        ?: workByEmployee[employeeId]?.firstOrNull()?.assigneeName?.ifBlank { employeeId } ?: employeeId
                    AdminSectionCard {
                        val review = state.performanceReviews.firstOrNull { it.employeeId == employeeId && it.period == month }
                        Text("${employee?.code.orEmpty().ifBlank { "—" }} • $name", style = MaterialTheme.typography.titleMedium)
                        if (workReady) WorkPerformanceDetails(workItemMetrics(workByEmployee[employeeId].orEmpty(), now))
                        HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                Text("Đánh giá tháng $month", style = MaterialTheme.typography.titleSmall)
                                if (review != null) {
                                    Text("Điểm: ${performanceHoursText(review.score)} / 100")
                                    if (review.note.isNotBlank()) Text(review.note)
                                } else Text("Chưa có đánh giá đã lưu", style = MaterialTheme.typography.bodyMedium)
                                if (employee != null && vm.hasAdminAccess()) TextButton(
                                    enabled = !state.saving && !state.performanceReviewsLoading && state.performanceReviewsError == null,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { vm.clearError(); editingReview = review; reviewingEmployee = employee }
                                ) { Text(if (review == null) "Đánh giá" else "Sửa đánh giá") }
                            }
                        if (showAttendanceSupport && (calculationReady || inPreview)) {
                            HorizontalDivider()
                            PerformanceSupportDetails(breakdowns[employeeId] ?: KpiBonusBreakdown())
                        }
                    }
                }
        }
    }
    reviewingEmployee?.let { employee ->
        PerformanceReviewDialog(employee, editingReview, month, state,
            onDismiss = { reviewingEmployee = null },
            onSave = { review -> vm.savePerformanceReview(review, editingReview) { reviewingEmployee = null } })
    }
}

@Composable
private fun PerformanceReviewDialog(employee: Employee, previous: PerformanceReview?, period: String,
    state: MainUiState, onDismiss: () -> Unit, onSave: (PerformanceReview) -> Unit) {
    var score by remember(employee.id, period, previous) { mutableStateOf(previous?.score?.let(::performanceHoursText) ?: "0") }
    var note by remember(employee.id, period, previous) { mutableStateOf(previous?.note.orEmpty()) }
    val amount = score.replace(',', '.').toDoubleOrNull()
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Đánh giá hiệu suất") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${employee.code} • ${employee.fullName} • $period")
                OutlinedTextField(score, { score = it.take(8) }, label = { Text("Điểm / 100") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.saving,
                    isError = amount == null || !amount.isFinite() || amount !in 0.0..100.0)
                OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Nhận xét") },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.saving, minLines = 3, maxLines = 6,
                    supportingText = { Text("${note.length}/2.000 ký tự") })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = !state.saving && amount != null && amount.isFinite() && amount in 0.0..100.0,
            onClick = { onSave(PerformanceReview(employee.id, period, amount!!, note.trim(), previous?.id.orEmpty())) }) {
            Text(if (state.saving) "Đang lưu…" else "Lưu đánh giá")
        } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = onDismiss) { Text("Hủy") } })
}

@Composable
private fun WorkPerformanceDetails(metrics: WorkItemMetrics) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Được giao: ${metrics.assigned} • Hoàn thành: ${metrics.completed}")
            Text("Đang thực hiện: ${metrics.inProgress} • Chờ duyệt: ${metrics.pendingReview}")
            Text("Hoàn thành đúng hạn: ${metrics.completedOnTime} • Hoàn thành muộn: ${metrics.completedLate}")
            Text("Đang quá hạn: ${metrics.overdue}", color = if (metrics.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Việc phải làm lại: ${metrics.reworked} • Số lần yêu cầu làm lại: ${metrics.reworkCount}")
            Text(if (metrics.completed > 0) "Tỷ lệ đúng hạn: ${String.format(Locale.US, "%.1f", metrics.onTimeCompletionRate * 100)}% (${metrics.completedOnTime}/${metrics.completed} việc đã hoàn thành)"
                else "Tỷ lệ đúng hạn: chưa có công việc hoàn thành để tính", style = MaterialTheme.typography.titleSmall)
        }
}

@Composable
private fun PerformanceSupportDetails(breakdown: KpiBonusBreakdown) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Chấm công và tăng ca hỗ trợ", style = MaterialTheme.typography.titleMedium)
            Text(
                breakdown.top3Rank?.let { "Top 3: hạng $it" }
                    ?: if (breakdown.lateCount > 0) {
                        "Top 3: không đủ điều kiện vì có ${breakdown.lateCount} lần đi trễ"
                    } else {
                        "Top 3: chưa được xếp hạng theo số ca tăng ca"
                    }
            )
            Text("Ca tăng ca: ${breakdown.overtimeShiftCount} ca • ${performanceHoursText(breakdown.overtimeHours)} giờ")
            Text("Đi trễ: ${breakdown.lateCount} lần")
            Text("Thưởng Top 3: ${money(breakdown.top3Bonus)}")
            Text("Thưởng theo ca: ${money(breakdown.overtimeBonus)}")
            Text("Phạt đi trễ: ${money(breakdown.latePenalty)}")
            Text("Tổng thưởng tự động: ${money(breakdown.totalBonus)}", style = MaterialTheme.typography.titleMedium)
        }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PerformanceScreenPreview() {
    PreviewStateScreen { state, vm -> PerformanceScreen(state, vm) }
}
