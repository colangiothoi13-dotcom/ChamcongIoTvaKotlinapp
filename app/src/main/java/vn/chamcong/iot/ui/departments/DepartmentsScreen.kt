package vn.chamcong.iot.ui.departments

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import vn.chamcong.iot.model.Department
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AppTouchTarget
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard

private enum class DepartmentFilter(val label: String) {
    ACTIVE("Đang hoạt động"),
    INACTIVE("Đã ngừng"),
    ALL("Tất cả")
}

/**
 * Department list and editor. `employeeCounts` is keyed by department ID.
 * Completion callbacks close the editor only after the caller confirms a successful save.
 */
@Composable
fun DepartmentsScreen(
    departments: List<Department>,
    employeeCounts: Map<String, Int>,
    saving: Boolean,
    onCreateDepartment: (name: String, onSaved: () -> Unit) -> Unit,
    onRenameDepartment: (departmentId: String, name: String, onSaved: () -> Unit) -> Unit,
    onSetDepartmentActive: (departmentId: String, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    var filter by remember { mutableStateOf(DepartmentFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var createDialogOpen by remember { mutableStateOf(false) }
    var departmentBeingEdited by remember { mutableStateOf<Department?>(null) }

    val activeCount = departments.count(Department::active)
    val inactiveCount = departments.size - activeCount
    val visibleDepartments = departments
        .filter { department ->
            department.name.contains(query.trim(), ignoreCase = true) && when (filter) {
                DepartmentFilter.ACTIVE -> department.active
                DepartmentFilter.INACTIVE -> !department.active
                DepartmentFilter.ALL -> true
            }
        }
        .sortedWith(compareByDescending<Department> { it.active }.thenBy { it.name.lowercase() })

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    AdminPageHeader("Phòng ban", "Quản lý cơ cấu và trạng thái phòng ban.")
                    Text(
                        "$activeCount đang hoạt động · $inactiveCount đã ngừng",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                Button(onClick = { createDialogOpen = true }, enabled = !saving, shape = MaterialTheme.shapes.small) {
                    Text("Thêm phòng ban")
                }
            }
        }
        item {
            AdminSectionCard {
            OutlinedTextField(query, { query = it }, label = { Text("Tìm phòng ban") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                DepartmentFilter.entries.forEach { item ->
                    FilterChip(modifier = Modifier.heightIn(min = AppTouchTarget.minimum),
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            Text("Hiển thị ${visibleDepartments.size}/${departments.size} phòng ban.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visibleDepartments.isEmpty()) {
            item {
                AdminSectionCard {
                        Text(
                            if (query.isNotBlank()) "Không có phòng ban phù hợp" else when (filter) {
                                DepartmentFilter.ACTIVE -> "Chưa có phòng ban đang hoạt động"
                                DepartmentFilter.INACTIVE -> "Chưa có phòng ban đã ngừng"
                                DepartmentFilter.ALL -> "Chưa có phòng ban"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Thêm phòng ban để quản lý nhân viên theo bộ phận.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
            }
        } else {
            items(visibleDepartments, key = Department::id) { department ->
                DepartmentCard(
                    department = department,
                    employeeCount = employeeCounts[department.id] ?: 0,
                    saving = saving,
                    onEdit = { departmentBeingEdited = department },
                    onActiveChange = { active -> onSetDepartmentActive(department.id, active) }
                )
            }
        }
    }

    if (createDialogOpen) {
        DepartmentNameDialog(
            title = "Thêm phòng ban",
            initialName = "",
            saving = saving,
            error = error,
            onDismiss = { createDialogOpen = false },
            onSave = { name -> onCreateDepartment(name) { createDialogOpen = false } }
        )
    }

    departmentBeingEdited?.let { department ->
        DepartmentNameDialog(
            title = "Đổi tên phòng ban",
            initialName = department.name,
            saving = saving,
            error = error,
            onDismiss = { departmentBeingEdited = null },
            onSave = { name ->
                onRenameDepartment(department.id, name) { departmentBeingEdited = null }
            }
        )
    }
}

@Composable
private fun DepartmentCard(
    department: Department,
    employeeCount: Int,
    saving: Boolean,
    onEdit: () -> Unit,
    onActiveChange: (Boolean) -> Unit
) {
    AdminSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                    Text(department.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$employeeCount nhân viên đang làm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (department.active) "Đang hoạt động" else "Đã ngừng hoạt động",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (department.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedButton(onClick = onEdit, enabled = !saving, shape = MaterialTheme.shapes.small) { Text("Đổi tên") }
                TextButton(onClick = { onActiveChange(!department.active) }, enabled = !saving) {
                    Text(if (department.active) "Ngừng hoạt động" else "Kích hoạt")
                }
            }
        }
}

@Composable
private fun DepartmentNameDialog(
    title: String,
    initialName: String,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tên phòng ban") },
                    singleLine = true,
                    enabled = !saving
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(trimmedName) },
                enabled = !saving && trimmedName.isNotEmpty()
            ) {
                Text(if (saving) "Đang lưu…" else "Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Hủy") }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun DepartmentsScreenPreview() {
    PreviewStateScreen { state, vm -> DepartmentsScreen(state.departments, mapOf("ky-thuat" to 1, "nhan-su" to 1), saving = false, onCreateDepartment = { _, _ -> }, onRenameDepartment = { _, _, _ -> }, onSetDepartmentActive = { _, _ -> }) }
}
