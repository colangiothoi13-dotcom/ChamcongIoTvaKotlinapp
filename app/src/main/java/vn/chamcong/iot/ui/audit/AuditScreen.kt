package vn.chamcong.iot.ui.audit

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
    val normalized = query.trim().lowercase()
    val logs = state.auditLogs.filter { log ->
        normalized.isBlank() || listOf(log.action, log.targetType, log.targetId, log.actorName, log.details)
            .any { it.lowercase().contains(normalized) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Nhật ký hệ thống", style = MaterialTheme.typography.titleLarge)
            Text("Nhật ký chỉ đọc; mỗi dòng lưu người thực hiện, đối tượng, thời gian và nội dung.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Lọc hành động/đối tượng") }, singleLine = true)
        }
        items(logs, key = { it.id }) { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(auditLogTitle(log.action, log.targetType), style = MaterialTheme.typography.titleMedium)
                    Text(auditActorLabel(log.actorName))
                    Text(log.details)
                    if (log.reason.isNotBlank()) Text("Lý do: ${log.reason}")
                    Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(log.createdAt.toDate()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
