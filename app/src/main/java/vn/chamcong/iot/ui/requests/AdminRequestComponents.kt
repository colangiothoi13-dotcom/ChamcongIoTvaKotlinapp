package vn.chamcong.iot.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class AdminRequestStatusTone { PENDING, APPROVED, REVISION, NEUTRAL }

@Composable
internal fun AdminRequestStatusBadge(label: String, tone: AdminRequestStatusTone) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (tone) {
        AdminRequestStatusTone.PENDING -> colors.tertiaryContainer to colors.onTertiaryContainer
        AdminRequestStatusTone.APPROVED -> colors.primaryContainer to colors.onPrimaryContainer
        AdminRequestStatusTone.REVISION -> colors.errorContainer to colors.onErrorContainer
        AdminRequestStatusTone.NEUTRAL -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(8.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
internal fun AdminRequestSummaryCard(
    title: String,
    subtitle: String,
    summary: String,
    status: String,
    tone: AdminRequestStatusTone,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AdminRequestStatusBadge(status, tone)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Xem chi tiết",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun AdminRequestSearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(256)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Tìm nhân viên") },
        placeholder = { Text("Tên, mã NV, phòng ban") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

private val requestSearchMarks = Regex("\\p{M}+")
private val requestSearchSpaces = Regex("\\s+")
private val requestDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun normalizeRequestSearch(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
        .replace(requestSearchMarks, "")

/** A query may combine a name, employee code and department, with or without Vietnamese accents. */
internal fun adminRequestMatchesQuery(query: String, vararg fields: String): Boolean {
    val tokens = normalizeRequestSearch(query).trim().split(requestSearchSpaces).filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val searchable = normalizeRequestSearch(fields.joinToString(" "))
    return tokens.all(searchable::contains)
}

internal fun adminRequestDateLabel(value: String): String =
    runCatching { LocalDate.parse(value).format(requestDateFormatter) }.getOrDefault(value)
