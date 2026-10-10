package vn.chamcong.iot.ui.workitems

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vn.chamcong.iot.domain.isWorkItemOverdue
import vn.chamcong.iot.domain.wasWorkItemCompletedLate
import vn.chamcong.iot.domain.workItemMetrics
import vn.chamcong.iot.model.UserRole
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemPriority
import vn.chamcong.iot.model.WorkItemStatus
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.ui.*
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkItemsScreen(state: MainUiState, vm: MainViewModel, initialSchedule: WorkSchedule? = null) {
    val admin = state.userProfile?.role == UserRole.ADMIN.name
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<WorkItem?>(null) }
    val now by currentWorkTime()
    val today = now.atZone(workZone).toLocalDate()
    LaunchedEffect(initialSchedule?.id) {
        if (initialSchedule != null && admin) showEditor = true
    }
    val availableItems = remember(state.workItems, admin, state.currentEmployee?.id) {
        if (admin) state.workItems else state.workItems.filter { it.assigneeId == state.currentEmployee?.id }
    }
    val visible = remember(availableItems, query, status, priority, due, now) {
        availableItems.filter { item ->
            (query.isBlank() || "${item.title} ${item.description} ${item.assigneeName}".contains(query.trim(), ignoreCase = true)) &&
                (status.isBlank() || item.status == status) && (priority.isBlank() || item.priority == priority) && when (due) {
                    "TODAY" -> item.status != "COMPLETED" && item.deadline.toDate().toInstant().atZone(workZone).toLocalDate() == today
                    "SOON" -> item.status != "COMPLETED" && !item.deadline.toDate().toInstant().isBefore(now) && item.deadline.toDate().toInstant() <= now.plusSeconds(24 * 3600)
                    "OVERDUE" -> isWorkItemOverdue(item, now)
                    "LATE_DONE" -> wasWorkItemCompletedLate(item)
                    else -> true
                }
        }.sortedWith(compareBy<WorkItem> { it.status == "COMPLETED" }.thenBy { it.deadline })
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(if (admin) "Công việc" else "Việc của tôi", style = MaterialTheme.typography.headlineSmall)
                Text("Được giao → Đang thực hiện → Chờ duyệt → Hoàn thành", style = MaterialTheme.typography.bodyMedium)
                if (admin) Button(onClick = { editItem = null; showEditor = true }, enabled = !state.saving) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Tạo và giao việc", Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Tìm công việc hoặc người thực hiện") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            WorkChoiceField("Trạng thái", if (status.isBlank()) "Tất cả trạng thái" else workStatusLabel(status), true,
                listOf("" to "Tất cả trạng thái") + WorkItemStatus.entries.map { it.name to workStatusLabel(it.name) }, { status = it })
        }
        item {
            WorkChoiceField("Ưu tiên", if (priority.isBlank()) "Tất cả mức ưu tiên" else workPriorityLabel(priority), true,
                listOf("" to "Tất cả mức ưu tiên") + WorkItemPriority.entries.map { it.name to workPriorityLabel(it.name) }, { priority = it })
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to "Tất cả hạn", "TODAY" to "Hạn hôm nay", "SOON" to "Trong 24 giờ", "OVERDUE" to "Đang quá hạn", "LATE_DONE" to "Hoàn thành muộn").forEach { (key, label) ->
                    FilterChip(selected = due == key, onClick = { due = key }, label = { Text(label) })
                }
            }
        }
        if (state.workItemsLoading) item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Đang tải công việc…")
            }
        }
        state.workItemsError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryWorkItems) { Text("Thử lại") }
        } }
        item { Text("${visible.size} công việc", style = MaterialTheme.typography.labelLarge) }
        if (visible.isEmpty() && !state.workItemsLoading && state.workItemsError == null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(if (availableItems.isEmpty()) "Chưa có công việc" else "Không có kết quả phù hợp", style = MaterialTheme.typography.titleMedium)
                    Text(if (availableItems.isEmpty()) if (admin) "Tạo công việc và giao cho một nhân viên để bắt đầu." else "Công việc được giao cho bạn sẽ xuất hiện ở đây."
                        else "Đổi từ khóa hoặc bộ lọc để xem công việc khác.")
                }
            }
        }
        items(visible, key = { it.id }) { item ->
            WorkItemCard(item, now, admin, onClick = { vm.selectWorkItem(item.id) })
        }
        item { Text("Vân tay ghi nhận có mặt. Kết quả và duyệt của quản lý xác nhận hoàn thành công việc.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = AppSpacing.large)) }
    }
    val selected = state.selectedWorkItemId?.let { id -> availableItems.firstOrNull { it.id == id } }
    selected?.let { item ->
        WorkItemDetailDialog(state, vm, item, onDismiss = { vm.selectWorkItem(null) }, onEdit = {
            editItem = item
            vm.selectWorkItem(null)
            showEditor = true
        })
    }
    if (showEditor && admin) WorkItemEditorDialog(state, vm, onDismiss = { showEditor = false }, item = editItem,
        initialSchedule = initialSchedule.takeIf { editItem == null }, onSaved = { vm.selectWorkItem(it) })
}

@Composable
private fun WorkItemCard(item: WorkItem, now: Instant, admin: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text("${workStatusLabel(item.status)} • Ưu tiên ${workPriorityLabel(item.priority)}", style = MaterialTheme.typography.labelLarge)
            if (admin) Text("Người thực hiện: ${item.assigneeName}")
            Text("Hạn: ${workTimeLabel(item.deadline)}", style = MaterialTheme.typography.bodyMedium)
            when {
                isWorkItemOverdue(item, now) -> Text("Đang quá hạn", color = MaterialTheme.colorScheme.error)
                wasWorkItemCompletedLate(item) -> Text("Đã hoàn thành muộn • ${workTimeLabel(item.completedAt)}", color = MaterialTheme.colorScheme.error)
                item.status == "COMPLETED" -> Text("Đã hoàn thành đúng hạn • ${workTimeLabel(item.completedAt)}")
            }
            Text(item.requiredResult, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (item.relatedScheduleId != null) Text("Có liên kết ca làm", style = MaterialTheme.typography.bodySmall)
            Text("Xem chi tiết và lịch sử", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun WorkChoiceField(label: String, value: String, enabled: Boolean, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(value) }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                choices.forEach { (key, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); expanded = false })
                }
                if (choices.isEmpty()) DropdownMenuItem(text = { Text("Không có lựa chọn phù hợp") }, enabled = false, onClick = {})
            }
        }
    }
}

@Composable
internal fun currentWorkTime(): State<Instant> = produceState(initialValue = Instant.now()) {
    while (true) { value = Instant.now(); delay(60_000) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkOverview(state: MainUiState, onOpen: () -> Unit) {
    val now by currentWorkTime()
    val today = LocalDate.now(workZone)
    val metrics = remember(state.workItems, now) { workItemMetrics(state.workItems, now) }
    val todayCount = remember(state.workItems, today) { state.workItems.count {
        it.status != "COMPLETED" && it.startAt.toDate().toInstant().atZone(workZone).toLocalDate() <= today &&
            it.deadline.toDate().toInstant().atZone(workZone).toLocalDate() >= today
    } }
    val soonCount = remember(state.workItems, now) { state.workItems.count {
        it.status != "COMPLETED" && !it.deadline.toDate().toInstant().isBefore(now) && it.deadline.toDate().toInstant() <= now.plusSeconds(24 * 3600)
    } }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(if (state.userProfile?.role == UserRole.ADMIN.name) "Theo dõi công việc" else "Việc của tôi", style = MaterialTheme.typography.titleLarge)
            if (state.workItemsLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Đang tải công việc…")
            } else if (state.workItemsError != null) {
                Text("Chưa tải được công việc. Mở danh sách để thử lại.", color = MaterialTheme.colorScheme.error)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("Hôm nay: $todayCount")
                    Text("Sắp đến hạn: $soonCount")
                    Text("Đang quá hạn: ${metrics.overdue}", color = if (metrics.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Chờ duyệt: ${metrics.pendingReview}")
                }
            }
            Text("Mở danh sách công việc", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
