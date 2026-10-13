package vn.chamcong.iot.ui.audit

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.auditActorLabel
import vn.chamcong.iot.ui.auditLogTitle
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AuditScreen(state: MainUiState) {
    var query by remember { mutableStateOf("") }
    val logs = state.visibleAuditLogs.filter { auditLogMatchesQuery(it, query) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item {
            AdminPageHeader("Nhật ký", "Lịch sử thao tác quản trị của hệ thống.")
        }
        item {
            AdminSectionCard {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Tìm người thực hiện, thao tác") }, singleLine = true)
                Text("${logs.size} thao tác phù hợp · Nhật ký chỉ đọc", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (logs.isEmpty()) item { AdminSectionCard { Text("Chưa có thao tác phù hợp", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(logs, key = { it.id }) { log ->
            AdminSectionCard {
                    Text(auditLogTitle(log.action, log.targetType), style = MaterialTheme.typography.titleMedium)
                Text("Người thực hiện: ${auditActorLabel(log.actorName)}")
                Text("Đối tượng: ${log.targetType} · ${log.targetId}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(log.details)
                if (log.reason.isNotBlank()) Text("Lý do: ${log.reason}")
                Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(log.createdAt.toDate()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AuditScreenPreview() {
    PreviewStateScreen { state, vm -> AuditScreen(state) }
}
