package vn.chamcong.iot.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val breakdowns = selectedMonth?.let(vm::kpiBonusBreakdowns).orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Text(
            "Top 3 chỉ dành cho nhân viên không đi muộn; thứ hạng dùng số ca tăng ca hoàn thành.",
            style = MaterialTheme.typography.bodySmall
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.employees.isEmpty()) {
                item { Text("Chưa có nhân viên để tính hiệu suất") }
            }
            items(state.employees, key = { it.id }) { employee ->
                val breakdown = breakdowns[employee.id] ?: KpiBonusBreakdown()
                PerformanceCard(employee.fullName.ifBlank { employee.id }, employee.code, breakdown)
            }
        }
    }
}

@Composable
private fun PerformanceCard(name: String, code: String, breakdown: KpiBonusBreakdown) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
