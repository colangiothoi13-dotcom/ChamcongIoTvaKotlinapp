package vn.chamcong.iot.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.validateEmployeeResource
import vn.chamcong.iot.model.EmployeeResource
import vn.chamcong.iot.model.EmployeeResourceType
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.employee.EmployeeResourceDetails
import kotlinx.coroutines.launch

@Composable
fun EmployeeResourceManagementScreen(
    state: MainUiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf(EmployeeResource()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var operationSubmitted by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EmployeeResource?>(null) }
    var deleteSubmitted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedType = EmployeeResourceType.entries.firstOrNull { it.name == draft.type }
        ?: EmployeeResourceType.MEETING
    val departments = state.departments.filter { it.active }.sortedBy { it.name }
    val employees = state.operationalEmployees.filter { it.active }.sortedBy { it.fullName }
    val audienceMode = draft.audience.substringBefore(':')
    val audienceId = draft.audience.substringAfter(':', "")
    val targetError = when (audienceMode) {
        "DEPARTMENT" -> if (departments.none { it.id == audienceId }) "Vui lòng chọn phòng ban đang hoạt động." else null
        "EMPLOYEE" -> if (employees.none { it.id == audienceId }) "Vui lòng chọn nhân viên đang làm việc." else null
        "ALL" -> null
        else -> "Vui lòng chọn lại người nhận."
    }
    val history = state.employeeResources.sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }

    fun updateDraft(value: EmployeeResource) {
        draft = value
        validationError = null
        feedback = null
        operationSubmitted = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text("Tiện ích nhân viên", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Đăng lịch họp, khen thưởng và tài liệu cho đúng người nhận.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
                ) {
                    Text(
                        if (draft.id.isBlank()) "Tạo nội dung" else "Chỉnh sửa nội dung",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ResourceSelector(
                        label = "Loại nội dung",
                        selectedLabel = resourceTypeLabel(selectedType),
                        options = EmployeeResourceType.entries.map { it.name to resourceTypeLabel(it) },
                        enabled = !state.saving,
                        onSelect = { value ->
                            if (value != draft.type) updateDraft(draft.copy(
                                type = value,
                                audience = if (value == EmployeeResourceType.REWARD.name) "EMPLOYEE:" else draft.audience,
                                eventDate = "", startTime = "", endTime = "", location = "", url = ""
                            ))
                        }
                    )
                    if (selectedType != EmployeeResourceType.REWARD) {
                        ResourceSelector(
                            label = "Người nhận",
                            selectedLabel = when (audienceMode) {
                                "DEPARTMENT" -> "Theo phòng ban"
                                "EMPLOYEE" -> "Một nhân viên"
                                else -> "Tất cả nhân viên"
                            },
                            options = listOf("ALL" to "Tất cả nhân viên", "DEPARTMENT" to "Theo phòng ban", "EMPLOYEE" to "Một nhân viên"),
                            enabled = !state.saving,
                            onSelect = { mode -> if (mode != audienceMode) updateDraft(draft.copy(audience = if (mode == "ALL") mode else "$mode:")) }
                        )
                    }
                    if (audienceMode == "DEPARTMENT") {
                        ResourceSelector(
                            label = "Phòng ban *",
                            selectedLabel = departments.firstOrNull { it.id == audienceId }?.name ?: "Chọn phòng ban",
                            options = departments.map { it.id to it.name },
                            enabled = !state.saving,
                            onSelect = { updateDraft(draft.copy(audience = "DEPARTMENT:$it")) }
                        )
                    }
                    if (audienceMode == "EMPLOYEE" || selectedType == EmployeeResourceType.REWARD) {
                        ResourceSelector(
                            label = "Nhân viên *",
                            selectedLabel = employees.firstOrNull { it.id == audienceId }
                                ?.let { "${it.fullName} (${it.code})" } ?: "Chọn nhân viên",
                            options = employees.map { it.id to "${it.fullName} (${it.code})" },
                            enabled = !state.saving,
                            onSelect = { updateDraft(draft.copy(audience = "EMPLOYEE:$it")) }
                        )
                    }
                    targetError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { updateDraft(draft.copy(title = it)) },
                        label = { Text("Tiêu đề *") },
                        supportingText = { Text("Tối đa 120 ký tự") },
                        singleLine = true,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.body,
                        onValueChange = { updateDraft(draft.copy(body = it)) },
                        label = { Text("Nội dung *") },
                        supportingText = { Text("Tối đa 4.000 ký tự") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selectedType != EmployeeResourceType.DOCUMENT) {
                        OutlinedTextField(
                            value = draft.eventDate,
                            onValueChange = { updateDraft(draft.copy(eventDate = it)) },
                            label = { Text(if (selectedType == EmployeeResourceType.REWARD) "Ngày khen thưởng *" else "Ngày họp *") },
                            supportingText = { Text("Định dạng năm-tháng-ngày, ví dụ 2026-10-08") },
                            singleLine = true,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (selectedType == EmployeeResourceType.MEETING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            OutlinedTextField(
                                value = draft.startTime,
                                onValueChange = { updateDraft(draft.copy(startTime = it)) },
                                label = { Text("Bắt đầu *") },
                                supportingText = { Text("Giờ:phút, ví dụ 08:00") },
                                singleLine = true,
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.endTime,
                                onValueChange = { updateDraft(draft.copy(endTime = it)) },
                                label = { Text("Kết thúc *") },
                                supportingText = { Text("Sau giờ bắt đầu") },
                                singleLine = true,
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = draft.location,
                            onValueChange = { updateDraft(draft.copy(location = it)) },
                            label = { Text("Địa điểm *") },
                            singleLine = true,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (selectedType != EmployeeResourceType.REWARD) {
                        OutlinedTextField(
                            value = draft.url,
                            onValueChange = { updateDraft(draft.copy(url = it)) },
                            label = { Text(if (selectedType == EmployeeResourceType.DOCUMENT) "Liên kết tài liệu *" else "Liên kết tham gia (không bắt buộc)") },
                            supportingText = { Text("Liên kết bắt đầu bằng https://") },
                            singleLine = true,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    (validationError ?: state.error.takeIf { operationSubmitted })?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Button(
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val normalized = draft.copy(
                                title = draft.title.trim(), body = draft.body.trim(),
                                eventDate = draft.eventDate.trim(), startTime = draft.startTime.trim(),
                                endTime = draft.endTime.trim(), location = draft.location.trim(), url = draft.url.trim()
                            )
                            validationError = targetError ?: runCatching { validateEmployeeResource(normalized) }
                                .exceptionOrNull()?.message
                            if (validationError == null) {
                                operationSubmitted = true
                                vm.saveEmployeeResource(normalized) {
                                    draft = EmployeeResource(type = normalized.type)
                                    if (normalized.type == EmployeeResourceType.REWARD.name) {
                                        draft = draft.copy(audience = "EMPLOYEE:")
                                    }
                                    validationError = null
                                    operationSubmitted = false
                                    feedback = "Đã lưu nội dung cho nhân viên."
                                }
                            }
                        }
                    ) {
                        if (state.saving) CircularProgressIndicator(
                            Modifier.padding(end = AppSpacing.small).size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Text(if (state.saving) "Đang lưu…" else if (draft.id.isBlank()) "Đăng nội dung" else "Lưu thay đổi")
                    }
                    if (draft.id.isNotBlank()) {
                        OutlinedButton(
                            onClick = { updateDraft(EmployeeResource()) },
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Hủy chỉnh sửa / Tạo mới") }
                    }
                }
            }
        }
        item { Text("Nội dung đã đăng (${history.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (state.employeeResourcesLoading) item { CircularProgressIndicator() }
        state.employeeResourcesError?.let { error ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.retryEmployeeResources() }) { Text("Thử lại") }
                }
            }
        }
        if (history.isEmpty() && !state.employeeResourcesLoading && state.employeeResourcesError == null) {
            item { Text("Chưa có nội dung nào được đăng.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(history, key = { it.id }) { resource ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(resource.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        EmployeeResourceType.entries.firstOrNull { it.name == resource.type }?.let(::resourceTypeLabel) ?: resource.type,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    EmployeeResourceDetails(resource)
                    Text("Người nhận: ${resourceAudienceLabel(resource, state)}", style = MaterialTheme.typography.bodySmall)
                    if (resource.url.isNotBlank()) Text(resource.url, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        OutlinedButton(onClick = {
                            updateDraft(resource)
                            scope.launch { listState.animateScrollToItem(1) }
                        }, enabled = !state.saving) { Text("Chỉnh sửa") }
                        TextButton(onClick = {
                            deleteTarget = resource
                            deleteSubmitted = false
                        }, enabled = !state.saving) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    deleteTarget?.let { resource ->
        AlertDialog(
            onDismissRequest = { if (!state.saving) deleteTarget = null },
            title = { Text("Xóa nội dung?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("Nội dung “${resource.title}” sẽ được xóa khỏi tiện ích của nhân viên.")
                    if (deleteSubmitted) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteSubmitted = true
                    vm.deleteEmployeeResource(resource.id) {
                        deleteTarget = null
                        deleteSubmitted = false
                        if (draft.id == resource.id) draft = EmployeeResource()
                        operationSubmitted = false
                        validationError = null
                        feedback = "Đã xóa nội dung."
                    }
                }, enabled = !state.saving) { Text(if (state.saving) "Đang xóa…" else "Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !state.saving) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun ResourceSelector(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedLabel, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                if (options.isEmpty()) {
                    DropdownMenuItem(text = { Text("Chưa có đối tượng phù hợp") }, onClick = {}, enabled = false)
                }
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        onSelect(value)
                        expanded = false
                    })
                }
            }
        }
    }
}

private fun resourceTypeLabel(type: EmployeeResourceType): String = when (type) {
    EmployeeResourceType.MEETING -> "Lịch họp"
    EmployeeResourceType.REWARD -> "Khen thưởng"
    EmployeeResourceType.DOCUMENT -> "Tài liệu"
}

private fun resourceAudienceLabel(resource: EmployeeResource, state: MainUiState): String = when {
    resource.audience == "ALL" -> "Tất cả nhân viên"
    resource.audience.startsWith("DEPARTMENT:") -> state.departments.firstOrNull {
        it.id == resource.audience.substringAfter(':')
    }?.name ?: "Phòng ban không còn hoạt động"
    resource.audience.startsWith("EMPLOYEE:") -> state.employees.firstOrNull {
        it.id == resource.audience.substringAfter(':')
    }?.fullName ?: "Nhân viên không còn hoạt động"
    else -> "Cần cập nhật người nhận"
}
