package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun CalculationLoadingNotice(state: MainUiState, vm: MainViewModel) {
    val loading = state.attendanceHistoryLoading
    if (loading || state.attendanceHistoryError != null || state.attendanceHistoryTruncated) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = if (loading) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (loading) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
        ) {
            Column(Modifier.padding(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Đang tải lịch, lượt chấm, đơn duyệt và điều chỉnh…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(state.attendanceHistoryError ?: "Dữ liệu vượt giới hạn; hãy chọn khoảng ngày nhỏ hơn.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::retryAttendanceRange) { Text("Tải lại dữ liệu") }
                }
            }
        }
    }
}
