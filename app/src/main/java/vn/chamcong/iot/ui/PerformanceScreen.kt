package vn.chamcong.iot.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.KpiBonusBreakdown
import java.time.YearMonth
import java.util.Locale

private fun performanceHoursText(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
internal fun PerformanceScreen(state: MainUiState, vm: MainViewModel) {
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    val validMonth = Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
    val selectedMonth = if (validMonth) YearMonth.parse(month) else null
    val inPreview = LocalInspectionMode.current
    val calculationReady = selectedMonth?.let {
        state.hasCompleteCalculationRange(it.atDay(1), it.atEndOfMonth())
    } == true
    LaunchedEffect(selectedMonth) {
        if (!inPreview) selectedMonth?.let {
            vm.loadAttendanceRange(it.atDay(1), it.atEndOfMonth(), force = true)
        }
    }
    val breakdowns = if (calculationReady || inPreview) selectedMonth?.let(vm::kpiBonusBreakdowns).orEmpty() else emptyMap()
    val employees = selectedMonth?.let(state::historicalEmployees).orEmpty()

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
        if (validMonth && !calculationReady && !inPreview) {
            Text("Đang chờ đủ dữ liệu lịch, lượt quét, nghỉ phép, tăng ca và điều chỉnh của tháng.", style = MaterialTheme.typography.bodySmall)
            state.attendanceHistoryError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::retryAttendanceRange) { Text("Thử lại") }
            }
            if (state.attendanceHistoryTruncated) {
                Text("Dữ liệu tháng chưa đầy đủ nên chưa thể tính KPI.", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "Top 3 chỉ dành cho nhân viên không đi muộn; thứ hạng dùng số ca tăng ca hoàn thành.",
            style = MaterialTheme.typography.bodySmall
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            if (employees.isEmpty()) {
                item { Text("Chưa có nhân viên để tính hiệu suất") }
            }
            if (calculationReady || inPreview) {
                items(employees, key = { it.id }) { employee ->
                    val breakdown = breakdowns[employee.id] ?: KpiBonusBreakdown()
                    PerformanceCard(employee.fullName.ifBlank { employee.id }, employee.code, breakdown)
                }
            }
        }
    }
}

@Composable
private fun PerformanceCard(name: String, code: String, breakdown: KpiBonusBreakdown) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("${code.ifBlank { "—" }} • $name", style = MaterialTheme.typography.titleMedium)
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
