package vn.chamcong.iot.ui.workitems

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.MAX_WORK_ITEM_ATTACHMENT_BYTES
import vn.chamcong.iot.model.WorkItemAttachment
import vn.chamcong.iot.ui.AppSpacing
import java.util.Locale

/** Selected documents remain local until the employee explicitly saves or submits. */
internal data class PendingWorkItemAttachment(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long?
)

internal fun readPendingWorkItemAttachment(resolver: ContentResolver, uri: Uri): PendingWorkItemAttachment {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Không đọc được tệp này. Vui lòng chọn lại từ trình chọn tệp." }
    // Some providers support only a temporary read grant. That grant still permits saving
    // during this session; refusing a persisted grant must not crash document selection.
    runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    var fileName: String? = null
    var sizeBytes: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
    } catch (error: Exception) {
        throw IllegalArgumentException("Không đọc được thông tin tệp. Vui lòng chọn lại hoặc chọn tệp khác.", error)
    }
    val safeName = fileName.orEmpty().replace('\n', ' ').replace('\r', ' ').trim().take(200).ifBlank { "Tệp đính kèm" }
    require(sizeBytes != 0L) { "$safeName: tệp trống, vui lòng chọn tệp khác." }
    require(sizeBytes == null || sizeBytes!! <= MAX_WORK_ITEM_ATTACHMENT_BYTES) { "$safeName: vượt quá 5 MB mỗi tệp." }
    val mimeType = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    return PendingWorkItemAttachment(uri, safeName, mimeType, sizeBytes)
}

internal fun workAttachmentSizeLabel(sizeBytes: Long?): String = when {
    sizeBytes == null -> "Chưa xác định dung lượng"
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}

@Composable
internal fun WorkItemAttachmentList(
    attachments: List<WorkItemAttachment>,
    enabled: Boolean,
    onOpen: (WorkItemAttachment) -> Unit,
    onRemove: ((WorkItemAttachment) -> Unit)? = null
) {
    attachments.forEach { attachment ->
        WorkItemAttachmentRow(
            fileName = attachment.fileName,
            mimeType = attachment.mimeType,
            details = "Đã gửi • ${workAttachmentSizeLabel(attachment.sizeBytes)}",
            enabled = enabled,
            onOpen = { onOpen(attachment) },
            onRemove = onRemove?.let { remove -> { remove(attachment) } }
        )
    }
}

@Composable
internal fun PendingWorkItemAttachmentList(
    attachments: List<PendingWorkItemAttachment>,
    enabled: Boolean,
    onRemove: (PendingWorkItemAttachment) -> Unit
) {
    attachments.forEach { attachment ->
        WorkItemAttachmentRow(
            fileName = attachment.fileName,
            mimeType = attachment.mimeType,
            details = "Chưa gửi • ${workAttachmentSizeLabel(attachment.sizeBytes)}",
            enabled = enabled,
            onRemove = { onRemove(attachment) }
        )
    }
}

@Composable
private fun WorkItemAttachmentRow(
    fileName: String,
    mimeType: String,
    details: String,
    enabled: Boolean,
    onOpen: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = AppSpacing.medium, top = AppSpacing.small, bottom = AppSpacing.small, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fileName.ifBlank { "Tệp đính kèm" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpen != null) TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mở tệp $fileName" }) { Text("Mở") }
            if (onRemove != null) IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Bỏ tệp $fileName")
            }
        }
    }
}
