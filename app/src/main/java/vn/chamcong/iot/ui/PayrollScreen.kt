package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.payrollHoursForMonth
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.calculateBasePay
import vn.chamcong.iot.model.payrollCandidates
import java.text.NumberFormat
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

private val PayrollZone = ZoneId.of("Asia/Ho_Chi_Minh")

internal fun money(value: Long): String = NumberFormat.getNumberInstance(Locale("vi", "VN")).format(value) + " đ"

private fun hoursText(value: Double): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
internal fun SalaryDialog(e: Employee, state: MainUiState, dismiss: () -> Unit, save: (Long) -> Unit) {
    var value by remember(e.id) { mutableStateOf(e.baseSalary.toString()) }
    val amount = value.toLongOrNull()
    AlertDialog(
        onDismissRequest = { if (!state.saving) dismiss() },
        title = { Text("Thiết lập lương") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${e.code} • ${e.fullName}")
                MoneyField("Lương cơ bản / giờ (đ)", value) { value = it }
                Text("Phiếu lương = lương cơ bản/giờ × số giờ làm + thưởng − khấu trừ.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { save(amount!!) },
                enabled = !state.saving && amount != null && amount in 0..1000000000000L
            ) { Text("Lưu đơn giá giờ") }
        },
        dismissButton = { TextButton(dismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Composable
private fun MoneyField(label: String, value: String, changed: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { c -> c in '0'..'9' } && it.length <= 13) changed(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun PayrollScreen(state: MainUiState, vm: MainViewModel) {
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    var selected by remember { mutableStateOf<Employee?>(null) }
    var settings by remember { mutableStateOf<Employee?>(null) }
    var picker by remember { mutableStateOf(false) }
    val validMonth = Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
    val rows = state.payroll.filter { it.month == month }
    val candidates = payrollCandidates(state.employees, state.payroll, month)

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        OutlinedTextField(month, { month = it }, label = { Text("Tháng lương (yyyy-MM)") }, singleLine = true, isError = !validMonth)
        Text("Thực lĩnh = (lương cơ bản/giờ × số giờ làm) + thưởng − khấu trừ.", style = MaterialTheme.typography.bodySmall)
        Button({ vm.clearError(); picker = true }, enabled = validMonth && !state.saving) { Text("Lập phiếu lương / thiết lập lương") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            if (rows.isEmpty()) item { Text("Chưa có phiếu lương đã lưu trong tháng này") }
            items(rows, key = { it.employeeId + it.month }) { p ->
                val e = state.employees.firstOrNull { it.id == p.employeeId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("${p.employeeCode.ifBlank { e?.code.orEmpty() }} • ${p.employeeName.ifBlank { e?.fullName ?: p.employeeId }}${if (e?.active == false) " • Đã nghỉ" else ""}")
                        Text("Lương cơ bản: ${money(p.baseSalary)}")
                        if (p.hourlyRate > 0 || p.hoursWorked > 0) {
                            Text("Đơn giá: ${money(p.hourlyRate)}/giờ • Số giờ: ${hoursText(p.hoursWorked)} giờ")
                        }
                        Text("Thưởng: ${money(p.bonus)} • Khấu trừ: ${money(p.deduction)}")
                        Text("Thực lĩnh: ${money(p.netSalary)}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text("Chọn nhân viên") },
        text = {
            LazyColumn {
                if (candidates.isEmpty()) item { Text("Không còn nhân viên cần lập phiếu trong tháng này") }
                items(candidates, key = { it.id }) { e ->
                    Column {
                        Text("${e.code} • ${e.fullName}${if (!e.active) " • Đã nghỉ" else ""}")
                        Text("Đơn giá cơ bản: ${money(e.baseSalary)}/giờ")
                        Row {
                            if (e.active) TextButton({ picker = false; settings = e }) { Text("Đặt lương") }
                            TextButton({ picker = false; selected = e }) { Text("Lập phiếu") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ picker = false }) { Text("Đóng") } }
    )

    settings?.let { e -> SalaryDialog(e, state, { settings = null }) { amount -> vm.setSalary(e.id, amount) { settings = null } } }

    selected?.let { e ->
        val selectedMonth = YearMonth.parse(month)
        val breakdown = vm.kpiBonusBreakdowns(selectedMonth)[e.id] ?: KpiBonusBreakdown()
        val calculatedHours = payrollHoursForMonth(
            employeeId = e.id,
            month = selectedMonth,
            attendance = state.attendanceForSummaries,
            schedules = state.schedules,
            shifts = state.shifts,
            overtimeRequests = state.overtimeRequests,
            adjustments = state.attendanceAdjustments,
            zoneId = PayrollZone
        )
        val regularHours = (calculatedHours - breakdown.overtimeHours).coerceAtLeast(0.0)
        val hours = calculatedHours
        var deduction by remember(e.id, month) { mutableStateOf("0") }
        val current = state.employees.firstOrNull { it.id == e.id } ?: e
        val h = hours
        val b = breakdown.totalBonus
        val d = deduction.toLongOrNull()
        val basePay = h.takeIf { it in 0.0..744.0 }?.let { calculateBasePay(current.baseSalary, it) }
        AlertDialog(
            onDismissRequest = { if (!state.saving) selected = null },
            title = { Text("Phiếu lương $month") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("${current.code} • ${current.fullName}")
                    Text("Đơn giá lương cơ bản: ${money(current.baseSalary)}/giờ")
                    Text("Giờ ca chính: ${hoursText(regularHours)} giờ")
                    Text("Giờ tăng ca: ${hoursText(breakdown.overtimeHours)} giờ • ${breakdown.overtimeShiftCount} ca")
                    Text("Tổng giờ tính lương: ${hoursText(calculatedHours)} giờ")
                    Text("Đi muộn: ${breakdown.lateCount} lần")
                    Text("Top 3: ${breakdown.top3Rank?.let { "hạng $it • ${money(breakdown.top3Bonus)}" } ?: "không đủ điều kiện • ${money(breakdown.top3Bonus)}"}")
                    Text("Thưởng theo ca: ${money(breakdown.overtimeBonus)}")
                    Text("Phạt đi muộn trong thưởng: ${money(breakdown.latePenalty)}")
                    Text("Thưởng tự động: ${money(breakdown.totalBonus)}", style = MaterialTheme.typography.titleMedium)
                    MoneyField("Khấu trừ (đ)", deduction) { deduction = it }
                    if (basePay != null && d != null) Text("Lương cơ bản: ${money(basePay)} • Thực lĩnh: ${money(basePay + b - d)}")
                    Text("Phiếu được lưu cố định để giữ lịch sử. Nhân viên đã nghỉ sẽ biến mất khỏi danh sách sau khi tháng này được lập phiếu.")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.savePayroll(e.id, month, h, b, d!!) { selected = null } },
                    enabled = !state.saving && h in 0.0..744.0 && b in 0..1000000000000L && d != null && d in 0..1000000000000L
                ) { Text("Lưu phiếu") }
            },
            dismissButton = { TextButton({ selected = null }, enabled = !state.saving) { Text("Hủy") } }
        )
    }
}
