package vn.chamcong.iot.ui.employees

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import vn.chamcong.iot.ui.AdminMetricCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.FaceEnrollmentAction
import vn.chamcong.iot.domain.filterEmployees
import vn.chamcong.iot.ui.EmployeeList
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.text.Normalizer
import java.util.Locale

@Composable
fun EmployeesScreen(
    state: MainUiState,
    vm: MainViewModel,
    onAdd: () -> Unit,
    onEdit: (Employee) -> Unit,
    onEnroll: (Employee) -> Unit,
    onSalary: (Employee) -> Unit,
    onRemove: (Employee, Boolean) -> Unit,
    onAccount: ((Employee) -> Unit)? = null
) {
    var faceEmployee by remember { mutableStateOf<Employee?>(null) }
    var faceAction by remember { mutableStateOf(FaceEnrollmentAction.ENROLL) }
    var employeeStatus by remember { mutableStateOf(if (state.showRetired) "all" else "active") }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); now = Instant.now() }
    }
    val departments = listOf("Tất cả") + state.employees.filter { state.showRetired || it.active }.map { it.department.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
    val search = normalizeEmployeeSearch(state.employeeQuery.trim())
    val visibleEmployees = filterEmployees(state.employees, "", state.departmentFilter, state.showRetired).filter {
        (employeeStatus != "retired" || !it.active) && (search.isBlank() ||
            normalizeEmployeeSearch("${it.fullName} ${it.code} ${it.email} ${it.department} ${it.position}").contains(search))
    }
    EmployeeList(
        header = {
            AdminPageHeader("Nhân viên", "Hồ sơ, tài khoản, vân tay và khuôn mặt.")
            Button(onClick = onAdd, enabled = !state.saving, shape = MaterialTheme.shapes.small) { Text("Thêm nhân viên") }
            val activeEmployees = state.employees.filter { it.active }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                AdminMetricCard("Đang làm việc", activeEmployees.size.toString(), "Nhân viên đang hoạt động", Modifier.weight(1f))
                AdminMetricCard("Đã đăng ký vân tay", activeEmployees.count { it.fingerprintTemplateId != null }.toString(), "Nhân viên đang làm đã có mẫu", Modifier.weight(1f))
            }
            AdminMetricCard("Đã nghỉ", (state.employees.size - activeEmployees.size).toString(), "Giữ hồ sơ và lịch sử chấm công")
            AdminSectionCard {
                OutlinedTextField(
                    value = state.employeeQuery,
                    onValueChange = vm::setEmployeeQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tìm tên, mã, email hoặc phòng ban") },
                    singleLine = true
                )
                EmployeeFilterField("Phòng ban", state.departmentFilter ?: "Tất cả phòng ban", departments.map { it to if (it == "Tất cả") "Tất cả phòng ban" else it }) {
                    vm.setDepartmentFilter(it.takeUnless { value -> value == "Tất cả" })
                }
                val statuses = listOf("all" to "Tất cả trạng thái", "active" to "Đang làm việc", "retired" to "Đã nghỉ")
                EmployeeFilterField("Trạng thái", statuses.first { it.first == employeeStatus }.second, statuses) {
                    employeeStatus = it
                    vm.setShowRetired(it != "active")
                }
                Text("Hiển thị ${visibleEmployees.size}/${state.employees.size} nhân viên.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.faceEnrollmentLoading) Text("Đang tải trạng thái khuôn mặt…", style = MaterialTheme.typography.bodySmall)
            state.faceEnrollmentError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::retryFaceEnrollment, enabled = !state.saving) { Text("Tải lại trạng thái khuôn mặt") }
            }
        },
        employees = visibleEmployees,
        commands = state.commands,
        onEnroll = onEnroll,
        onEdit = onEdit,
        onSalary = onSalary,
        onRemove = onRemove,
        onAccount = onAccount,
        faceContent = { employee ->
            EmployeeFaceEnrollmentControls(employee, state, vm, now) { action ->
                vm.clearError()
                faceAction = action
                faceEmployee = employee
            }
        }
    )
    faceEmployee?.let { selected ->
        val employee = state.employees.firstOrNull { it.id == selected.id } ?: selected
        FaceEnrollmentDialog(employee, faceAction, state, vm, now) { faceEmployee = null }
    }
}

private fun normalizeEmployeeSearch(value: String): String = Normalizer.normalize(
    value.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD
).replace(Regex("\\p{M}+"), "")

@Composable
private fun EmployeeFilterField(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); expanded = false }) }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun EmployeesScreenPreview() {
    PreviewStateScreen { state, vm -> EmployeesScreen(state, vm, onAdd = {}, onEdit = {}, onEnroll = {}, onSalary = {}, onRemove = { _, _ -> }) }
}
