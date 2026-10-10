package vn.chamcong.iot.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AppTouchTarget
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val notificationTimeFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale("vi", "VN"))
private val notificationZone = ZoneId.of("Asia/Ho_Chi_Minh")

@Composable
internal fun AdminNotificationsCard(
    notifications: List<AppNotification>,
    saving: Boolean,
    onMarkRead: (List<String>) -> Unit
) {
    val groups = remember(notifications) { groupAdminNotifications(notifications) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val unreadGroups = groups.count { it.unreadCount > 0 }
    val visibleGroups = if (showAll) groups else groups.take(3)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = AppSpacing.large, vertical = AppSpacing.small)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = AppSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Thông báo trong app",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (unreadGroups > 0) {
                    Text(
                        "$unreadGroups chưa đọc",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            visibleGroups.forEachIndexed { index, group ->
                key(group.key) {
                    val expanded = expandedKey == group.key
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AdminNotificationRow(
                        group = group,
                        expanded = expanded,
                        saving = saving,
                        onToggle = { expandedKey = if (expanded) null else group.key },
                        onMarkRead = { onMarkRead(group.unreadIds) }
                    )
                }
            }
            if (groups.size > 3) {
                TextButton(
                    onClick = {
                        showAll = !showAll
                        if (!showAll && expandedKey !in groups.take(3).map { it.key }) expandedKey = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppTouchTarget.minimum),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                ) {
                    Text(if (showAll) "Thu gọn" else "Xem thêm ${groups.size - 3} thông báo")
                }
            }
        }
    }
}

@Composable
private fun AdminNotificationRow(
    group: AdminNotificationGroup,
    expanded: Boolean,
    saving: Boolean,
    onToggle: () -> Unit,
    onMarkRead: () -> Unit
) {
    val notification = group.latest
    Column {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics { stateDescription = if (expanded) "Đã mở nội dung" else "Đã thu gọn" }
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Thu gọn thông báo" else "Xem nội dung thông báo",
                    onClick = onToggle
                )
                .padding(vertical = AppSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                Text(
                    notification.title.ifBlank { "Thông báo" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (group.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!expanded) {
                    Text(
                        notification.body.ifBlank { "Nhấn để xem chi tiết" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                if (group.unreadCount > 0) "Chưa đọc" else "Đã đọc",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = AppSpacing.small),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                Text(notification.body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    notification.createdAt.toDate().toInstant().atZone(notificationZone).format(notificationTimeFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                notification.audienceLabel?.takeIf(String::isNotBlank)?.let {
                    Text("Đối tượng: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (group.recipientCount > 1) {
                    Text(
                        "${group.recipientCount} người nhận · ${group.unreadCount} chưa đọc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (group.unreadIds.isNotEmpty()) {
                    TextButton(
                        onClick = onMarkRead,
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = AppTouchTarget.minimum),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) { Text("Đánh dấu đã đọc") }
                }
            }
        }
    }
}
