package vn.chamcong.iot.ui.employees

import vn.chamcong.iot.ui.AppSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.ui.EmployeeList
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel

@Composable
fun EmployeesScreen(
    state: MainUiState,
    vm: MainViewModel,
    onAdd: () -> Unit,
    onEdit: (Employee) -> Unit,
    onEnroll: (Employee) -> Unit,
    onSalary: (Employee) -> Unit,
    onRemove: (Employee, Boolean) -> Unit
) {
    val departments = listOf("Tất cả") + state.employees.map { it.department.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Danh sách nhân sự", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text("Thêm") }
        }
        OutlinedTextField(
            value = state.employeeQuery,
            onValueChange = vm::setEmployeeQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tìm theo tên hoặc mã nhân viên") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            departments.forEach { department ->
                FilterChip(
                    selected = (state.departmentFilter ?: "Tất cả") == department,
                    onClick = { vm.setDepartmentFilter(department.takeUnless { it == "Tất cả" }) },
                    label = { Text(department) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hiện nhân viên đã nghỉ", modifier = Modifier.weight(1f))
            Switch(checked = state.showRetired, onCheckedChange = vm::setShowRetired)
        }
        Text("${state.visibleEmployees.size} nhân viên trong kết quả", style = MaterialTheme.typography.bodySmall)
        EmployeeList(
            employees = state.visibleEmployees,
            commands = state.commands,
            onEnroll = onEnroll,
            onEdit = onEdit,
            onSalary = onSalary,
            onRemove = onRemove
        )
    }
}
