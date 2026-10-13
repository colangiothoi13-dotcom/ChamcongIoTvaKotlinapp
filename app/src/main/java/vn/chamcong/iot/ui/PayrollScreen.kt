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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.PayrollRevisionSnapshot
import vn.chamcong.iot.model.payrollSnapshotNet
import kotlinx.coroutines.CancellationException
import vn.chamcong.iot.data.payrollRowsToCsv
import vn.chamcong.iot.ui.reports.shareCsv
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
        title = { Text("Đơn giá giờ · ${e.fullName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("${e.code} • ${e.fullName}")
                MoneyField("Đơn giá giờ (đ)", value, enabled = !state.saving) { value = it }
                Text("Đơn giá giờ dùng để lập phiếu mới và xem trước tính lại. Phiếu đã lưu chỉ thay đổi khi xác nhận tính lại.", style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { save(amount!!) },
                enabled = !state.saving && amount != null && amount in 0..1000000000000L,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Lưu đơn giá") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PayrollScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var exportError by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(state.selectedPayrollMonth.toString()) }
    var selected by remember { mutableStateOf<Employee?>(null) }
    var recalculating by remember { mutableStateOf<Payroll?>(null) }
    var settings by remember { mutableStateOf<Employee?>(null) }
    var picker by remember { mutableStateOf(false) }
    var salaryPickerOnly by remember { mutableStateOf(false) }
    var historyPayroll by remember { mutableStateOf<Payroll?>(null) }
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
    val savedPayrollReady = validMonth && !state.payrollLoading && state.payrollError == null &&
        state.payrollLoadedMonth == selectedPayrollMonth

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            AdminPageHeader("Bảng lương", "Đặt đơn giá giờ, lập phiếu và quản lý phiếu đã lưu. Phiếu chỉ thay đổi khi xác nhận lưu hoặc tính lại.")
        }
        item {
            AdminSectionCard {
                OutlinedTextField(month, { month = it }, label = { Text("Tháng (yyyy-MM)") }, singleLine = true,
                    isError = !validMonth, modifier = Modifier.fillMaxWidth())
                if (!validMonth) Text("Tháng không hợp lệ. Nhập theo định dạng yyyy-MM.", color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Button({ vm.clearError(); salaryPickerOnly = false; picker = true }, enabled = validMonth && !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Lập / quản lý phiếu") }
                    OutlinedButton({ vm.clearError(); salaryPickerOnly = true; picker = true }, enabled = validMonth && !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Đặt đơn giá giờ") }
                    OutlinedButton(enabled = savedPayrollReady, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, onClick = {
                        exportError = null
                        runCatching { shareCsv(context, payrollRowsToCsv(rows), "luong-$month") }
                            .onFailure { exportError = userFacingErrorMessage(it) }
                    }) { Text("Xuất bảng lương") }
                }
                exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (validMonth && hoursByEmployee == null) PayrollDataStatus(state)
                if (state.payrollLoading) Text("Đang tải phiếu lương đã lưu…", style = MaterialTheme.typography.bodyMedium)
                state.payrollError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retryPayroll, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tải lại phiếu lương") }
                }
                TextButton(onClick = reloadMonth, enabled = validMonth && !state.attendanceHistoryLoading && !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Tải lại giờ công tháng") }
            }
        }
        if (savedPayrollReady) item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                AdminMetricCard("Thực nhận đã lưu", money(rows.sumOf { it.netSalary }), "${rows.size} phiếu lương tháng $month")
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminMetricCard("Giờ trên phiếu", hoursText(rows.sumOf { it.hoursWorked }), "Giờ công đã chốt", Modifier.weight(1f))
                    AdminMetricCard("Tổng thưởng", money(rows.sumOf { it.bonus }), "Theo phiếu đã lưu", Modifier.weight(1f))
                }
            }
        }
        item { Text("Phiếu lương đã lưu", style = MaterialTheme.typography.titleMedium) }
            if (rows.isEmpty()) item { AdminSectionCard { Text(if (state.payrollLoading) "Đang tải phiếu lương…" else "Chưa có phiếu lương đã lưu trong tháng này", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(rows, key = { it.employeeId + it.month }) { p ->
                val e = monthEmployees.firstOrNull { it.id == p.employeeId }
                val currentHours = hoursByEmployee?.get(p.employeeId)
                var showCurrentDays by remember(p.employeeId, p.month) { mutableStateOf(false) }
                AdminSectionCard {
                        val retirementLabel = if (e != null && !e.active) {
                            e.terminationLocalDate()?.format(payrollDateFormatter)?.let { " • Đã nghỉ $it" } ?: " • Đã nghỉ"
                        } else ""
                        Text("${p.employeeCode.ifBlank { e?.code.orEmpty() }} • ${p.employeeName.ifBlank { e?.fullName ?: p.employeeId }}$retirementLabel", style = MaterialTheme.typography.titleMedium)
                        Text("Lương cơ bản: ${money(p.baseSalary)}")
                        Text("Tổng giờ đã lưu: ${hoursText(p.hoursWorked)} giờ", style = MaterialTheme.typography.titleMedium)
                        Text("Đơn giá giờ: ${money(p.hourlyRate)}/giờ")
                        Text("Thưởng: ${money(p.bonus)} • Khấu trừ: ${money(p.deduction)}")
                        Text("Thực nhận: ${money(p.netSalary)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (p.revision > 0L) Text("Đã điều chỉnh ${p.revision} lần", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { historyPayroll = p }, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Lịch sử phiên bản") }
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
                            Text("Giờ công hiện tại: chưa tải đủ dữ liệu tháng.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Tải lại chỉ cập nhật giờ công để đối chiếu. Chọn Xem / tính lại để xem số mới rồi lưu; mỗi lần lưu giữ lịch sử phiên bản.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { vm.clearError(); reloadMonth(); recalculating = p },
                            enabled = currentHours != null && !state.saving,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Xem / tính lại") }
                }
            }
    }

    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text(if (salaryPickerOnly) "Đặt đơn giá giờ" else "Lập / quản lý phiếu lương") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                item { Text(if (salaryPickerOnly) "Đơn giá giờ dùng để lập phiếu mới và xem trước tính lại. Phiếu đã lưu giữ nguyên cho đến khi xác nhận tính lại." else "Tháng $month. Chọn nhân viên để lập phiếu mới hoặc xem và tính lại phiếu đã lưu.", style = MaterialTheme.typography.bodyMedium) }
                if (!salaryPickerOnly && hoursByEmployee == null) item { PayrollDataStatus(state) }
                if (monthEmployees.none { !salaryPickerOnly || it.active }) item { Text(if (salaryPickerOnly) "Chưa có nhân viên đang làm" else "Chưa có nhân viên trong tháng này") }
                items(monthEmployees.filter { !salaryPickerOnly || it.active }, key = { it.id }) { e ->
                    val savedPayroll = savedByEmployee[e.id]
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        val retirementLabel = if (!e.active) {
                            e.terminationLocalDate()?.format(payrollDateFormatter)?.let { " • Đã nghỉ $it" } ?: " • Đã nghỉ"
                        } else ""
                        Text("${e.code} • ${e.fullName}$retirementLabel")
                        Text("Đơn giá giờ: ${money(e.baseSalary)}/giờ")
                        if (!salaryPickerOnly && savedPayroll != null) Text("Đã có phiếu lương tháng $month", style = MaterialTheme.typography.bodyMedium)
                        if (!salaryPickerOnly) hoursByEmployee?.get(e.id)?.let { PayrollHoursBreakdown(it, compact = true) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            if (e.active) TextButton({ picker = false; settings = e }, enabled = !state.saving,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Đặt đơn giá giờ") }
                            if (!salaryPickerOnly && savedPayroll == null) {
                                TextButton({ picker = false; vm.clearError(); selected = e }, enabled = !state.saving,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Tạo phiếu") }
                            } else if (!salaryPickerOnly) {
                                TextButton(
                                    onClick = { picker = false; vm.clearError(); reloadMonth(); recalculating = savedPayroll },
                                    enabled = hoursByEmployee?.containsKey(e.id) == true && !state.saving,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Xem / tính lại") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ picker = false }) { Text("Đóng") } }
    )

    settings?.let { e -> SalaryDialog(e, state, { settings = null }) { amount -> vm.setSalary(e.id, amount) { settings = null } } }
    historyPayroll?.let { payroll -> PayrollRevisionHistoryDialog(payroll, vm) { historyPayroll = null } }

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
                    Text("Đơn giá giờ: ${money(current.baseSalary)}/giờ")
                    if (current.baseSalary == 0L) Text("Đơn giá đang là 0 đ/giờ. Kiểm tra mức lương trước khi lưu phiếu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (hours != null && breakdown != null) {
                        AdminSectionCard {
                                PayrollHoursBreakdown(hours)
                                if (hours.days.isEmpty()) Text("Chưa có giờ công hoặc cặp chấm công được ghi nhận trong tháng.", style = MaterialTheme.typography.bodyMedium)
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
                    Text("Giờ lương tính từ cặp chấm vào/ra hợp lệ trong khung ca, hoặc giờ công được Admin điều chỉnh. Xác nhận lượt quét không tự cộng đủ giờ của cả ca.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = reloadMonth,
                        enabled = !state.attendanceHistoryLoading && !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Tải lại giờ công tháng") }
                    if (breakdown != null) {
                        Text("Đi trễ: ${breakdown.lateCount} lần")
                        Text("Ca bổ sung đã duyệt: ${breakdown.overtimeShiftCount}")
                        Text("Top 3: ${breakdown.top3Rank?.let { "hạng $it • ${money(breakdown.top3Bonus)}" } ?: "không đủ điều kiện • ${money(breakdown.top3Bonus)}"}")
                        Text("Thưởng theo ca: ${money(breakdown.overtimeBonus)}")
                        Text("Phạt đi trễ trong thưởng: ${money(breakdown.latePenalty)}")
                        Text("Thưởng tự động: ${money(breakdown.totalBonus)}", style = MaterialTheme.typography.titleMedium)
                    }
                    MoneyField("Khấu trừ (đ)", deduction, enabled = hours != null && !state.saving) { deduction = it }
                    if (basePay != null && b != null && d != null) Text("Lương cơ bản: ${money(basePay)} • Thực nhận: ${money(basePay + b - d)}")
                    Text("Phiếu đã lưu giữ nguyên giờ và tiền tại lúc lập; điều chỉnh chấm công sau đó không tự cập nhật phiếu. Nhân viên nghỉ việc chỉ có thể lập lương đến tháng nghỉ.", style = MaterialTheme.typography.bodyMedium)
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
private fun PayrollRevisionHistoryDialog(payroll: Payroll, vm: MainViewModel, onDismiss: () -> Unit) {
    var rows by remember(payroll.employeeId, payroll.month) { mutableStateOf<List<PayrollRevisionSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(payroll.employeeId, payroll.month, reload) {
        loading = true; error = null
        try { rows = vm.loadPayrollRevisionHistory(payroll) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: Exception) { error = userFacingErrorMessage(failed) }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Lịch sử phiên bản • ${payroll.month}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                item { Text(payroll.employeeName.ifBlank { payroll.employeeId }) }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Đang tải lịch sử…") }
                else if (error != null) item {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Thử lại") }
                } else if (rows.isEmpty()) item { Text("Phiếu chưa được tính lại. Phiếu ban đầu ở phiên bản 0.") }
                else items(rows, key = { it.id }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text("Phiên bản ${row.revision}", style = MaterialTheme.typography.titleSmall)
                        row.createdAt?.let { Text(it.toDate().toInstant().atZone(PayrollZone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))) }
                        Text("Giờ công: ${snapshotHours(row.previous)} → ${snapshotHours(row.next)}")
                        Text("Đơn giá giờ: ${snapshotMoney(row.previous, "hourlyRate")} → ${snapshotMoney(row.next, "hourlyRate")}")
                        Text("Lương cơ bản: ${snapshotMoney(row.previous, "baseSalary")} → ${snapshotMoney(row.next, "baseSalary")}")
                        Text("Thưởng: ${snapshotMoney(row.previous, "bonus")} → ${snapshotMoney(row.next, "bonus")}")
                        Text("Khấu trừ: ${snapshotMoney(row.previous, "deduction")} → ${snapshotMoney(row.next, "deduction")}")
                        Text("Thực nhận: ${payrollSnapshotNet(row.previous)?.let(::money) ?: "Chưa lưu"} → ${payrollSnapshotNet(row.next)?.let(::money) ?: "Chưa lưu"}")
                        Text("Người thực hiện: ${row.actorId}", style = MaterialTheme.typography.bodyMedium)
                        Text("Lý do: ${row.reason}")
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } })
}

private fun snapshotHours(snapshot: Map<String, Any?>): String =
    (snapshot["hoursWorked"] as? Number)?.toDouble()?.let { "${hoursText(it)} h" } ?: "Chưa lưu"
private fun snapshotMoney(snapshot: Map<String, Any?>, field: String): String =
    (snapshot[field] as? Number)?.toLong()?.let(::money) ?: "Chưa lưu"

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
        title = { Text("Xem trước tính lại · ${previous.month}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text("${previous.employeeCode.ifBlank { employee?.code.orEmpty() }} • ${previous.employeeName.ifBlank { employee?.fullName ?: previous.employeeId }}")
                AdminSectionCard {
                        Text("Phiếu đang lưu", style = MaterialTheme.typography.titleMedium)
                        Text("${hoursText(previous.hoursWorked)} giờ × ${money(previous.hourlyRate)}/giờ")
                        Text("Lương theo giờ: ${money(previous.baseSalary)}")
                        Text("Thưởng: ${money(previous.bonus)} • Khấu trừ: ${money(previous.deduction)}")
                        Text("Thực nhận đã lưu: ${money(previous.netSalary)}", style = MaterialTheme.typography.titleMedium)
                }
                AdminSectionCard {
                        Text("Phiếu sau khi tính lại", style = MaterialTheme.typography.titleMedium)
                        if (hours != null) PayrollHoursBreakdown(hours, compact = true) else PayrollDataStatus(state)
                        if (hourlyRate != null) Text("Đơn giá hiện tại: ${money(hourlyRate)}/giờ")
                        else Text("Chưa có đơn giá nhân viên hợp lệ để tính lại.", color = MaterialTheme.colorScheme.error)
                        if (newBasePay != null) Text("Lương theo giờ mới: ${money(newBasePay)}")
                        MoneyField("Thưởng (đ)", bonusText, enabled = !state.saving) { bonusText = it }
                        MoneyField("Khấu trừ (đ)", deductionText, enabled = !state.saving) { deductionText = it }
                        if (newNetPay != null) Text("Thực nhận mới: ${money(newNetPay)}", style = MaterialTheme.typography.titleMedium)
                        if (hourlyRate == 0L) {
                            Text("Đơn giá hiện tại đang là 0 đ/giờ. Kiểm tra mức lương trước khi xác nhận.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (newNetPay != null && newNetPay < 0L) {
                            Text("Thực nhận mới âm vì khấu trừ lớn hơn lương và thưởng. Kiểm tra khấu trừ trước khi xác nhận.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                }
                Text("Giờ công và đơn giá lấy dữ liệu hiện tại. Thưởng và khấu trừ giữ giá trị từ phiếu cũ cho đến khi bạn sửa.", style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onReload,
                    enabled = !state.saving && !state.attendanceHistoryLoading,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Tải lại giờ công tháng") }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 500) reason = it },
                    label = { Text("Lý do tính lại") },
                    supportingText = { Text("Bắt buộc • ${reason.length}/500 ký tự") },
                    isError = reason.isNotEmpty() && !validReason,
                    enabled = !state.saving,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Lưu sẽ cập nhật phiếu đang có và giữ lịch sử số liệu cũ cùng lý do điều chỉnh.", style = MaterialTheme.typography.bodyMedium)
                if (savedChanged) Text("Phiếu đã thay đổi trong lúc bạn xem. Đóng và mở lại Xem / tính lại để lấy bản mới nhất.", color = MaterialTheme.colorScheme.error)
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
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Xác nhận tính lại và lưu lịch sử") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}

@Composable
private fun PayrollDataStatus(state: MainUiState) {
    when {
        state.attendanceHistoryLoading -> Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("Đang tải giờ công của tháng…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.attendanceHistoryError != null -> Text(state.attendanceHistoryError, color = MaterialTheme.colorScheme.error)
        state.attendanceHistoryTruncated -> Text("Dữ liệu tháng chưa đầy đủ nên chưa thể tính và lưu lương.", color = MaterialTheme.colorScheme.error)
        else -> Text("Chưa tải đủ dữ liệu giờ công của tháng. Bấm tải lại để xem.", style = MaterialTheme.typography.bodyMedium)
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
        Text("Giờ thường: ${hoursText(hours.regularHours)} giờ • Tăng ca: ${hoursText(hours.overtimeHours)} giờ", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Giờ thường: ${hoursText(hours.regularHours)} giờ")
        Text("Giờ tăng ca: ${hoursText(hours.overtimeHours)} giờ")
    }
    if (hours.missingCheckOutDays > 0) {
        Text(
            "${hours.missingCheckOutDays} ngày đã chấm vào nhưng chưa chấm ra; phần ca chưa đủ cặp chưa có giờ tính lương.",
            style = MaterialTheme.typography.bodyMedium,
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
        Text("Vào: $checkIn • Ra: $checkOut", style = MaterialTheme.typography.bodyMedium)
        val status = when (day.status) {
            EmployeeAttendanceStatus.PRESENT -> "Đã chấm vào"
            EmployeeAttendanceStatus.ON_TIME -> "Đúng giờ"
            EmployeeAttendanceStatus.LATE -> "Đi trễ"
            EmployeeAttendanceStatus.EARLY_LEAVE -> "Về sớm"
            EmployeeAttendanceStatus.ABNORMAL -> "Cần kiểm tra"
            EmployeeAttendanceStatus.MISSING_CHECK_IN -> "Thiếu chấm vào"
            EmployeeAttendanceStatus.MISSING_CHECK_OUT -> "Thiếu chấm ra"
            EmployeeAttendanceStatus.LEAVE -> "Nghỉ phép"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("Giờ thường: ${hoursText(day.workedHours)} giờ • Tăng ca: ${hoursText(day.overtimeHours)} giờ", style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PayrollScreenPreview() {
    PreviewStateScreen { state, vm -> PayrollScreen(state, vm) }
}
