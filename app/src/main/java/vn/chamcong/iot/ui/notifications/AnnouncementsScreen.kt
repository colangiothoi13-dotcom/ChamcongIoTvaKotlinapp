package vn.chamcong.iot.ui.notifications

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Announcement
import vn.chamcong.iot.model.Department
import vn.chamcong.iot.ui.AppSpacing
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AnnouncementsScreen(
    announcements: List<Announcement>,
    departments: List<Department>,
    saving: Boolean,
    onSend: (targetDepartment: String?, title: String, body: String, onSent: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var selectedDepartmentId by remember { mutableStateOf<String?>(null) }
    var audienceMenuExpanded by remember { mutableStateOf(false) }

    val activeDepartments = departments
        .filter(Department::active)
        .sortedBy { it.name.lowercase() }
    val selectedDepartment = activeDepartments.firstOrNull { it.id == selectedDepartmentId }
    val history = announcements.sortedByDescending { it.sentAt?.toDate()?.time ?: Long.MIN_VALUE }
    val canSend = !saving && title.isNotBlank() && body.isNotBlank() &&
        (selectedDepartmentId == null || selectedDepartment != null)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                Text("Thông báo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Gửi thông báo cho toàn bộ nhân viên hoặc một phòng ban đang hoạt động.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
                ) {
                    Text("Soạn thông báo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Người nhận", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(
                            onClick = { audienceMenuExpanded = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selectedDepartment?.name ?: if (selectedDepartmentId == null) "Tất cả nhân viên" else "Chọn phòng ban đang hoạt động",
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Chọn người nhận"
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = audienceMenuExpanded,
                            onDismissRequest = { audienceMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Tất cả nhân viên") },
                                onClick = {
                                    selectedDepartmentId = null
                                    audienceMenuExpanded = false
                                }
                            )
                            activeDepartments.forEach { department ->
                                DropdownMenuItem(
                                    text = { Text(department.name) },
                                    onClick = {
                                        selectedDepartmentId = department.id
                                        audienceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (selectedDepartmentId != null && selectedDepartment == null) {
                        Text(
                            "Phòng ban đã ngừng hoạt động hoặc không còn tồn tại. Hãy chọn lại người nhận.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tiêu đề *") },
                        singleLine = true,
                        enabled = !saving
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nội dung *") },
                        minLines = 4,
                        enabled = !saving
                    )
                    Text("Tiêu đề và nội dung là bắt buộc.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            onSend(selectedDepartment?.name, title.trim(), body.trim()) {
                                title = ""
                                body = ""
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = AppSpacing.small).size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text(if (saving) "Đang gửi…" else "Gửi thông báo")
                    }
                }
            }
        }
        item {
            Text("Lịch sử đã gửi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (history.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Chưa có thông báo nào được gửi.",
                        modifier = Modifier.padding(AppSpacing.large),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(history, key = { it.id }) { announcement ->
                AnnouncementHistoryCard(announcement)
            }
        }
    }
}

@Composable
private fun AnnouncementHistoryCard(announcement: Announcement) {
    val sentAt = announcement.sentAt?.toDate()?.let { date ->
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(date)
    } ?: "Chưa rõ thời gian"

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
        ) {
            Text(announcement.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(announcement.body, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Đối tượng: ${announcement.targetDepartment?.takeIf(String::isNotBlank) ?: "Tất cả nhân viên"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Người nhận: ${announcement.recipientCount}", style = MaterialTheme.typography.bodySmall)
            Text("Thời gian gửi: $sentAt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
