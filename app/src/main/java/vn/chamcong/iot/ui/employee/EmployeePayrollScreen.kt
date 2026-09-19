package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.money
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val payrollMonthFormatter = DateTimeFormatter.ofPattern("'Tháng' MM/yyyy")

@Composable
fun EmployeePayrollScreen(state: MainUiState) {
    var selectedPayroll by remember { mutableStateOf<Payroll?>(null) }
    val payrollRows = remember(state.employeePayroll) {
        state.employeePayroll.sortedByDescending { it.month }
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = AppSpacing.small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
    ) {
        item {
            Text(
                "Phiếu lương đã lưu",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AppSpacing.small)
            )
        }
        if (payrollRows.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(AppSpacing.xLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Chưa có phiếu lương đã lưu")
                    }
                }
            }
        } else {
            items(
                items = payrollRows,
                key = { row -> "${row.employeeId}-${row.month}" }
            ) { payroll ->
                Card(
                    onClick = { selectedPayroll = payroll },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(AppSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        Text(
                            payrollMonthLabel(payroll.month),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(payroll.employeeName.ifBlank { "Nhân viên" })
                        Text(
                            "Thực nhận: ${money(payroll.netSalary)}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Nhấn để xem chi tiết",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    selectedPayroll?.let { payroll ->
        EmployeePayrollDetailDialog(payroll) { selectedPayroll = null }
    }
}

@Composable
private fun EmployeePayrollDetailDialog(payroll: Payroll, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phiếu lương • ${payrollMonthLabel(payroll.month)}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(AppSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        Text(payroll.employeeName.ifBlank { "Nhân viên" }, fontWeight = FontWeight.SemiBold)
                        PayrollDetailRow("Mã nhân viên", payroll.employeeCode.ifBlank { "—" })
                        PayrollDetailRow("Kỳ lương", payrollMonthLabel(payroll.month))
                        PayrollDetailRow("Lương cơ bản / giờ", "${money(payroll.hourlyRate)}/giờ")
                        PayrollDetailRow("Tổng số giờ", "${payroll.hoursWorked.formatPayrollHours()} giờ")
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(AppSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        Text("Tổng tiền lương", fontWeight = FontWeight.SemiBold)
                        PayrollDetailRow("Lương theo giờ làm", money(payroll.baseSalary))
                        PayrollDetailRow("Thưởng", money(payroll.bonus))
                        PayrollDetailRow("Khấu trừ", money(payroll.deduction))
                        PayrollDetailRow(
                            "Thực nhận",
                            money(payroll.netSalary),
                            emphasized = true
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Đóng") } }
    )
}

@Composable
private fun PayrollDetailRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.width(AppSpacing.small))
        Text(
            value,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun payrollMonthLabel(month: String): String = runCatching {
    YearMonth.parse(month).format(payrollMonthFormatter)
}.getOrDefault(month.ifBlank { "Kỳ lương" })

private fun Double.formatPayrollHours(): String = String.format(Locale.US, "%.2f", this)
