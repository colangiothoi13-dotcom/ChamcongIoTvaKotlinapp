package vn.chamcong.iot.ui.departments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(DepartmentFilter.ACTIVE) }
    var createDialogOpen by remember { mutableStateOf(false) }
    var departmentBeingEdited by remember { mutableStateOf<Department?>(null) }

    val activeCount = departments.count(Department::active)
    val inactiveCount = departments.size - activeCount
    val visibleDepartments = departments
        .filter { department ->
            when (filter) {
                DepartmentFilter.ACTIVE -> department.active
                DepartmentFilter.INACTIVE -> !department.active
                DepartmentFilter.ALL -> true
            }
        }
        .sortedWith(compareByDescending<Department> { it.active }.thenBy { it.name.lowercase() })

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                    Text("Quản lý phòng ban", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "$activeCount đang hoạt động · $inactiveCount đã ngừng",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { createDialogOpen = true }, enabled = !saving) {
                    Text("Thêm phòng ban")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                DepartmentFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
        }
        if (visibleDepartments.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(AppSpacing.xLarge),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when (filter) {
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
            onDismiss = { createDialogOpen = false },
            onSave = { name -> onCreateDepartment(name) { createDialogOpen = false } }
        )
    }

    departmentBeingEdited?.let { department ->
        DepartmentNameDialog(
            title = "Chỉnh sửa phòng ban",
            initialName = department.name,
            saving = saving,
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
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                    Text(department.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$employeeCount nhân viên",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit, enabled = !saving) {
                    Icon(Icons.Default.Edit, contentDescription = "Sửa ${department.name}")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (department.active) "Đang hoạt động" else "Đã ngừng hoạt động",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (department.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = department.active,
                    onCheckedChange = onActiveChange,
                    enabled = !saving
                )
            }
        }
    }
}

@Composable
private fun DepartmentNameDialog(
    title: String,
    initialName: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tên phòng ban") },
                singleLine = true,
                enabled = !saving
            )
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
