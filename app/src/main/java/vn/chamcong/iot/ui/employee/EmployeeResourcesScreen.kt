package vn.chamcong.iot.ui.employee

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import vn.chamcong.iot.model.EmployeeResource
import vn.chamcong.iot.model.EmployeeResourceType
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun EmployeeResourcesScreen(
    state: MainUiState,
    vm: MainViewModel,
    type: EmployeeResourceType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = remember(state.employeeResources, type) {
        state.employeeResources.filter { it.type == type.name }
            .sortedByDescending { it.createdAt?.toDate()?.time ?: Long.MIN_VALUE }
    }
    val title = when (type) {
        EmployeeResourceType.MEETING -> "Lịch họp"
        EmployeeResourceType.REWARD -> "Khen thưởng"
        EmployeeResourceType.DOCUMENT -> "Tài liệu"
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                when (type) {
                    EmployeeResourceType.MEETING -> "Lịch họp và thông tin tham gia do quản trị viên gửi đến bạn."
                    EmployeeResourceType.REWARD -> "Các quyết định khen thưởng đã được ghi nhận cho bạn."
                    EmployeeResourceType.DOCUMENT -> "Tài liệu được chia sẻ với bạn và phòng ban của bạn."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.employeeResourcesLoading) {
            item { CircularProgressIndicator() }
        }
        state.employeeResourcesError?.let { error ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.retryEmployeeResources() }) { Text("Thử lại") }
                }
            }
        }
        if (resources.isEmpty() && !state.employeeResourcesLoading && state.employeeResourcesError == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        when (type) {
                            EmployeeResourceType.MEETING -> "Bạn chưa có lịch họp nào."
                            EmployeeResourceType.REWARD -> "Bạn chưa có quyết định khen thưởng nào."
                            EmployeeResourceType.DOCUMENT -> "Chưa có tài liệu nào được chia sẻ với bạn."
                        },
                        modifier = Modifier.padding(AppSpacing.large),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(resources, key = { it.id }) { resource ->
            var linkError by remember(resource.id, resource.url) { mutableStateOf<String?>(null) }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Text(resource.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    EmployeeResourceDetails(resource)
                    if (resource.url.isNotBlank()) {
                        OutlinedButton(onClick = {
                            linkError = null
                            val uri = runCatching { URI(resource.url) }.getOrNull()
                            if (uri == null || !uri.scheme.equals("https", ignoreCase = true) ||
                                uri.host.isNullOrBlank() || uri.userInfo != null) {
                                linkError = "Liên kết không hợp lệ. Vui lòng liên hệ quản trị viên."
                            } else {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resource.url)))
                                } catch (_: ActivityNotFoundException) {
                                    linkError = "Không tìm thấy ứng dụng để mở liên kết này."
                                } catch (_: SecurityException) {
                                    linkError = "Ứng dụng không thể mở liên kết này."
                                }
                            }
                        }) {
                            Text(when (type) {
                                EmployeeResourceType.DOCUMENT -> "Mở tài liệu"
                                EmployeeResourceType.MEETING -> "Mở liên kết tham gia"
                                EmployeeResourceType.REWARD -> "Mở liên kết"
                            })
                        }
                        linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

/** Shared with the administrator history so both views show the saved details. */
@Composable
internal fun EmployeeResourceDetails(resource: EmployeeResource) {
    if (resource.eventDate.isNotBlank()) {
        val date = runCatching {
            LocalDate.parse(resource.eventDate).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }.getOrDefault(resource.eventDate)
        Text("Ngày: $date", style = MaterialTheme.typography.bodyMedium)
    }
    if (resource.startTime.isNotBlank() || resource.endTime.isNotBlank()) {
        Text("Thời gian: ${resource.startTime} – ${resource.endTime}", style = MaterialTheme.typography.bodyMedium)
    }
    if (resource.location.isNotBlank()) {
        Text("Địa điểm: ${resource.location}", style = MaterialTheme.typography.bodyMedium)
    }
    Text(resource.body, style = MaterialTheme.typography.bodyMedium)
}
