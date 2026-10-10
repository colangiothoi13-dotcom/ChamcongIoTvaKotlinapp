package vn.chamcong.iot.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.calculateBasePay
import vn.chamcong.iot.model.terminationLocalDate
import vn.chamcong.iot.ui.payroll.PayrollHoursPreview
import vn.chamcong.iot.ui.payroll.payrollHoursPreviews
import java.text.NumberFormat
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PayrollZone = ZoneId.of("Asia/Ho_Chi_Minh")
private val payrollDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("vi", "VN"))
private val payrollTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale("vi", "VN"))

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
                MoneyField("Lương cơ bản / giờ (đ)", value, enabled = !state.saving) { value = it }
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
private fun MoneyField(label: String, value: String, enabled: Boolean, changed: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { c -> c in '0'..'9' } && it.length <= 13) changed(it) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun PayrollScreen(state: MainUiState, vm: MainViewModel) {
    var month by remember { mutableStateOf(state.selectedPayrollMonth.toString()) }
    var selected by remember { mutableStateOf<Employee?>(null) }
    var recalculating by remember { mutableStateOf<Payroll?>(null) }
    var settings by remember { mutableStateOf<Employee?>(null) }
    var picker by remember { mutableStateOf(false) }
    val validMonth = Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
    val selectedPayrollMonth = month.takeIf { validMonth }?.let(YearMonth::parse)
    val inPreview = LocalInspectionMode.current
    val hoursByEmployee = remember(state, selectedPayrollMonth, inPreview) {
        selectedPayrollMonth?.let { payrollHoursPreviews(state, it, allowPreviewData = inPreview) }
    }
    val reloadMonth: () -> Unit = {
        selectedPayrollMonth?.let {
            vm.loadAttendanceRange(it.atDay(1), it.atEndOfMonth(), force = true)
        }
    }
    LaunchedEffect(selectedPayrollMonth) {
        if (!inPreview) selectedPayrollMonth?.let {
            vm.selectPayrollMonth(it)
            vm.loadAttendanceRange(it.atDay(1), it.atEndOfMonth(), force = true)
        }
    }
    val monthEmployees = if (validMonth) state.historicalEmployees(YearMonth.parse(month)) else emptyList()
    val operationalIds = monthEmployees.mapTo(mutableSetOf()) { it.id }
    val rows = state.payroll.filter { it.month == month && it.employeeId in operationalIds }
    val savedByEmployee = rows.associateBy { it.employeeId }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        OutlinedTextField(month, { month = it }, label = { Text("Tháng lương (yyyy-MM)") }, singleLine = true, isError = !validMonth)
        Text("Thực lĩnh = (lương cơ bản/giờ × số giờ làm) + thưởng − khấu trừ.", style = MaterialTheme.typography.bodySmall)
        if (validMonth && hoursByEmployee == null) PayrollDataStatus(state)
        Button({ vm.clearError(); picker = true }, enabled = validMonth && !state.saving) { Text("Lập phiếu lương / thiết lập lương") }
        TextButton(
            onClick = reloadMonth,
            enabled = validMonth && !state.attendanceHistoryLoading && !state.saving,
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("Tải lại giờ công tháng") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            if (rows.isEmpty()) item { Text("Chưa có phiếu lương đã lưu trong tháng này") }
            items(rows, key = { it.employeeId + it.month }) { p ->
                val e = monthEmployees.firstOrNull { it.id == p.employeeId }
                val currentHours = hoursByEmployee?.get(p.employeeId)
                var showCurrentDays by remember(p.employeeId, p.month) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        val retirementLabel = if (e != null && !e.active) {
                            e.terminationLocalDate()?.format(payrollDateFormatter)?.let { " • Đã nghỉ $it" } ?: " • Đã nghỉ"
                        } else ""
                        Text("${p.employeeCode.ifBlank { e?.code.orEmpty() }} • ${p.employeeName.ifBlank { e?.fullName ?: p.employeeId }}$retirementLabel")
                        Text("Lương cơ bản: ${money(p.baseSalary)}")
                        Text("Tổng giờ đã lưu: ${hoursText(p.hoursWorked)} giờ", style = MaterialTheme.typography.titleMedium)
                        Text("Đơn giá: ${money(p.hourlyRate)}/giờ")
                        Text("Thưởng: ${money(p.bonus)} • Khấu trừ: ${money(p.deduction)}")
                        Text("Thực lĩnh: ${money(p.netSalary)}", style = MaterialTheme.typography.titleMedium)
                        if (p.revision > 0L) Text("Đã điều chỉnh ${p.revision} lần", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        if (currentHours != null) {
                            PayrollHoursBreakdown(currentHours, compact = true, totalLabel = "Giờ công hiện tại trong tháng")
                            if (currentHours.days.isNotEmpty()) {
                                TextButton(
                                    onClick = { showCurrentDays = !showCurrentDays },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(if (showCurrentDays) "Ẩn chi tiết giờ công hiện tại" else "Xem chi tiết ${currentHours.days.size} ngày")
                                }
                                if (showCurrentDays) currentHours.days.forEach { PayrollDayHours(it) }
                            }
                        } else {
                            Text("Giờ công hiện tại: chưa tải đủ dữ liệu tháng.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Tải lại chỉ cập nhật giờ công để đối chiếu. Chọn Tính lại phiếu để xem số mới rồi lưu; mỗi lần lưu giữ lịch sử điều chỉnh.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { vm.clearError(); reloadMonth(); recalculating = p },
                            enabled = currentHours != null && !state.saving,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Tính lại phiếu") }
                    }
                }
            }
        }
    }

    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text("Chọn nhân viên") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                if (hoursByEmployee == null) item { PayrollDataStatus(state) }
                if (monthEmployees.isEmpty()) item { Text("Chưa có nhân viên trong tháng này") }
                items(monthEmployees, key = { it.id }) { e ->
                    val savedPayroll = savedByEmployee[e.id]
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        val retirementLabel = if (!e.active) {
                            e.terminationLocalDate()?.format(payrollDateFormatter)?.let { " • Đã nghỉ $it" } ?: " • Đã nghỉ"
                        } else ""
                        Text("${e.code} • ${e.fullName}$retirementLabel")
                        Text("Đơn giá cơ bản: ${money(e.baseSalary)}/giờ")
                        if (savedPayroll != null) Text("Đã có phiếu lương tháng $month", style = MaterialTheme.typography.bodySmall)
                        hoursByEmployee?.get(e.id)?.let { PayrollHoursBreakdown(it, compact = true) }
                        Row {
                            if (e.active) TextButton({ picker = false; settings = e }, enabled = !state.saving) { Text("Đặt lương") }
                            if (savedPayroll == null) {
                                TextButton({ picker = false; vm.clearError(); selected = e }, enabled = !state.saving) { Text("Lập phiếu") }
                            } else {
                                TextButton(
                                    onClick = { picker = false; vm.clearError(); reloadMonth(); recalculating = savedPayroll },
                                    enabled = hoursByEmployee?.containsKey(e.id) == true && !state.saving
                                ) { Text("Tính lại phiếu") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ picker = false }) { Text("Đóng") } }
    )

    settings?.let { e -> SalaryDialog(e, state, { settings = null }) { amount -> vm.setSalary(e.id, amount) { settings = null } } }

    recalculating?.let { previous ->
        PayrollRecalculationDialog(
            previous = previous,
            employee = state.employees.firstOrNull { it.id == previous.employeeId },
            hours = hoursByEmployee?.get(previous.employeeId),
            state = state,
            onReload = reloadMonth,
            onDismiss = { recalculating = null },
            onSave = { hours, hourlyRate, bonus, deduction, reason ->
                vm.recalculatePayroll(previous, hours, hourlyRate, bonus, deduction, reason) { recalculating = null }
            }
        )
    }

    selected?.let { e ->
        val hours = hoursByEmployee?.get(e.id)
        val breakdown = hours?.bonus
        var deduction by remember(e.id, month) { mutableStateOf("0") }
        var showDays by remember(e.id, month) { mutableStateOf(false) }
        val current = state.employees.firstOrNull { it.id == e.id } ?: e
        val h = hours?.totalHours
        val b = breakdown?.totalBonus
        val d = deduction.toLongOrNull()
        val basePay = h?.takeIf { it in 0.0..744.0 }?.let { calculateBasePay(current.baseSalary, it) }
        AlertDialog(
            onDismissRequest = { if (!state.saving) selected = null },
            title = { Text("Phiếu lương $month") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("${current.code} • ${current.fullName}")
                    Text("Đơn giá lương cơ bản: ${money(current.baseSalary)}/giờ")
                    if (current.baseSalary == 0L) Text("Đơn giá đang là 0 đ/giờ. Kiểm tra mức lương trước khi lưu phiếu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (hours != null && breakdown != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                PayrollHoursBreakdown(hours)
                                if (hours.days.isEmpty()) Text("Chưa có giờ công hoặc cặp chấm công được ghi nhận trong tháng.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (hours.days.isNotEmpty()) {
                            TextButton(
                                onClick = { showDays = !showDays },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(if (showDays) "Ẩn chi tiết ngày" else "Xem chi tiết ${hours.days.size} ngày") }
                            if (showDays) hours.days.forEach { PayrollDayHours(it) }
                        }
                    } else {
                        PayrollDataStatus(state)
                    }
                    Text("Giờ lương tính từ cặp chấm vào/ra hợp lệ trong khung ca, hoặc giờ công được Admin điều chỉnh. Xác nhận lượt quét không tự cộng đủ giờ của cả ca.", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = reloadMonth,
                        enabled = !state.attendanceHistoryLoading && !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Tải lại giờ công tháng") }
                    if (breakdown != null) {
                        Text("Đi muộn: ${breakdown.lateCount} lần")
                        Text("Ca bổ sung đã duyệt: ${breakdown.overtimeShiftCount}")
                        Text("Top 3: ${breakdown.top3Rank?.let { "hạng $it • ${money(breakdown.top3Bonus)}" } ?: "không đủ điều kiện • ${money(breakdown.top3Bonus)}"}")
                        Text("Thưởng theo ca: ${money(breakdown.overtimeBonus)}")
                        Text("Phạt đi muộn trong thưởng: ${money(breakdown.latePenalty)}")
                        Text("Thưởng tự động: ${money(breakdown.totalBonus)}", style = MaterialTheme.typography.titleMedium)
                    }
                    MoneyField("Khấu trừ (đ)", deduction, enabled = hours != null && !state.saving) { deduction = it }
                    if (basePay != null && b != null && d != null) Text("Lương cơ bản: ${money(basePay)} • Thực lĩnh: ${money(basePay + b - d)}")
                    Text("Phiếu đã lưu giữ nguyên giờ và tiền tại lúc lập; điều chỉnh chấm công sau đó không tự cập nhật phiếu. Nhân viên nghỉ việc chỉ có thể lập lương đến tháng nghỉ.", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (h != null && b != null && d != null) {
                            vm.savePayroll(e.id, month, h, b, d) { selected = null }
                        }
                    },
                    enabled = hours != null && !state.saving && h != null && h in 0.0..744.0 && b != null && b in 0..1000000000000L && d != null && d in 0..1000000000000L
                ) { Text("Lưu phiếu") }
            },
            dismissButton = { TextButton({ selected = null }, enabled = !state.saving) { Text("Hủy") } }
        )
    }
}

@Composable
private fun PayrollRecalculationDialog(
    previous: Payroll,
    employee: Employee?,
    hours: PayrollHoursPreview?,
    state: MainUiState,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double, Long, Long, Long, String) -> Unit
) {
    var bonusText by remember(previous) { mutableStateOf(previous.bonus.toString()) }
    var deductionText by remember(previous) { mutableStateOf(previous.deduction.toString()) }
    var reason by remember(previous) { mutableStateOf("") }
    val bonus = bonusText.toLongOrNull()?.takeIf { it in 0..1000000000000L }
    val deduction = deductionText.toLongOrNull()?.takeIf { it in 0..1000000000000L }
    val hourlyRate = employee?.baseSalary?.takeIf { it in 0..1000000000000L }
    val currentHours = hours?.totalHours?.takeIf { it in 0.0..744.0 }
    val newBasePay = if (hourlyRate != null && currentHours != null) calculateBasePay(hourlyRate, currentHours) else null
    val newNetPay = if (newBasePay != null && bonus != null && deduction != null) newBasePay + bonus - deduction else null
    val latestSaved = state.payroll.firstOrNull { it.employeeId == previous.employeeId && it.month == previous.month }
    val savedChanged = latestSaved != previous
    val validReason = reason.trim().isNotBlank() && reason.length <= 500
    val canSave = !state.saving && !savedChanged && currentHours != null && hourlyRate != null &&
        bonus != null && deduction != null && validReason
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Tính lại phiếu ${previous.month}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text("${previous.employeeCode.ifBlank { employee?.code.orEmpty() }} • ${previous.employeeName.ifBlank { employee?.fullName ?: previous.employeeId }}")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("Phiếu đang lưu", style = MaterialTheme.typography.titleMedium)
                        Text("${hoursText(previous.hoursWorked)} giờ × ${money(previous.hourlyRate)}/giờ")
                        Text("Lương theo giờ: ${money(previous.baseSalary)}")
                        Text("Thưởng: ${money(previous.bonus)} • Khấu trừ: ${money(previous.deduction)}")
                        Text("Thực lĩnh đã lưu: ${money(previous.netSalary)}", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("Phiếu sau khi tính lại", style = MaterialTheme.typography.titleMedium)
                        if (hours != null) PayrollHoursBreakdown(hours, compact = true) else PayrollDataStatus(state)
                        if (hourlyRate != null) Text("Đơn giá hiện tại: ${money(hourlyRate)}/giờ")
                        else Text("Chưa có đơn giá nhân viên hợp lệ để tính lại.", color = MaterialTheme.colorScheme.error)
                        if (newBasePay != null) Text("Lương theo giờ mới: ${money(newBasePay)}")
                        MoneyField("Thưởng (đ)", bonusText, enabled = !state.saving) { bonusText = it }
                        MoneyField("Khấu trừ (đ)", deductionText, enabled = !state.saving) { deductionText = it }
                        if (newNetPay != null) Text("Thực lĩnh mới: ${money(newNetPay)}", style = MaterialTheme.typography.titleMedium)
                        if (hourlyRate == 0L) {
                            Text("Đơn giá hiện tại đang là 0 đ/giờ. Kiểm tra mức lương trước khi xác nhận.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (newNetPay != null && newNetPay < 0L) {
                            Text("Thực lĩnh mới âm vì khấu trừ lớn hơn lương và thưởng. Kiểm tra khấu trừ trước khi xác nhận.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Giờ công và đơn giá lấy dữ liệu hiện tại. Thưởng và khấu trừ giữ giá trị từ phiếu cũ cho đến khi bạn sửa.", style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = onReload,
                    enabled = !state.saving && !state.attendanceHistoryLoading,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Tải lại giờ công tháng") }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 500) reason = it },
                    label = { Text("Lý do tính lại phiếu") },
                    supportingText = { Text("Bắt buộc • ${reason.length}/500 ký tự") },
                    isError = reason.isNotEmpty() && !validReason,
                    enabled = !state.saving,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Lưu sẽ cập nhật phiếu đang có và giữ lịch sử số liệu cũ cùng lý do điều chỉnh.", style = MaterialTheme.typography.bodySmall)
                if (savedChanged) Text("Phiếu đã thay đổi trong lúc bạn xem. Đóng và mở lại Tính lại phiếu để lấy bản mới nhất.", color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(currentHours, hourlyRate, bonus, deduction, reason.trim())
                    }
                },
                enabled = canSave
            ) { Text("Lưu phiếu tính lại") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Composable
private fun PayrollDataStatus(state: MainUiState) {
    when {
        state.attendanceHistoryLoading -> Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Đang tải giờ công của tháng…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.attendanceHistoryError != null -> Text(state.attendanceHistoryError, color = MaterialTheme.colorScheme.error)
        state.attendanceHistoryTruncated -> Text("Dữ liệu tháng chưa đầy đủ nên chưa thể tính và lưu lương.", color = MaterialTheme.colorScheme.error)
        else -> Text("Chưa tải đủ dữ liệu giờ công của tháng. Bấm tải lại để xem.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PayrollHoursBreakdown(
    hours: PayrollHoursPreview,
    compact: Boolean = false,
    totalLabel: String = "Tổng giờ tính lương"
) {
    Text("$totalLabel: ${hoursText(hours.totalHours)} giờ", style = MaterialTheme.typography.titleMedium)
    if (compact) {
        Text("Ca chính: ${hoursText(hours.regularHours)} giờ • Tăng ca: ${hoursText(hours.overtimeHours)} giờ", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Giờ ca chính: ${hoursText(hours.regularHours)} giờ")
        Text("Giờ tăng ca: ${hoursText(hours.overtimeHours)} giờ")
    }
    if (hours.missingCheckOutDays > 0) {
        Text(
            "${hours.missingCheckOutDays} ngày đã chấm vào nhưng chưa chấm ra; phần ca chưa đủ cặp chưa có giờ tính lương.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PayrollDayHours(day: EmployeeDaySummary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        HorizontalDivider()
        Text(day.date.format(payrollDateFormatter), style = MaterialTheme.typography.titleSmall)
        val checkIn = day.checkIn?.atZone(PayrollZone)?.format(payrollTimeFormatter) ?: "—"
        val checkOut = day.checkOut?.atZone(PayrollZone)?.format(payrollTimeFormatter) ?: "—"
        Text("Vào: $checkIn • Ra: $checkOut", style = MaterialTheme.typography.bodySmall)
        val status = when (day.status) {
            EmployeeAttendanceStatus.PRESENT -> "Đã chấm vào"
            EmployeeAttendanceStatus.ON_TIME -> "Đúng giờ"
            EmployeeAttendanceStatus.LATE -> "Đi muộn"
            EmployeeAttendanceStatus.EARLY_LEAVE -> "Về sớm"
            EmployeeAttendanceStatus.ABNORMAL -> "Cần kiểm tra"
            EmployeeAttendanceStatus.MISSING_CHECK_IN -> "Thiếu chấm vào"
            EmployeeAttendanceStatus.MISSING_CHECK_OUT -> "Thiếu chấm ra"
            EmployeeAttendanceStatus.LEAVE -> "Nghỉ phép"
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Ca chính: ${hoursText(day.workedHours)} giờ • Tăng ca: ${hoursText(day.overtimeHours)} giờ", style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PayrollScreenPreview() {
    PreviewStateScreen { state, vm -> PayrollScreen(state, vm) }
}
