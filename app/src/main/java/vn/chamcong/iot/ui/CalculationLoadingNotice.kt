package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun CalculationLoadingNotice(state: MainUiState, vm: MainViewModel) {
    Column {
        if (state.attendanceHistoryLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Đang tải lịch, lượt chấm, đơn duyệt và điều chỉnh…")
        } else if (state.attendanceHistoryError != null || state.attendanceHistoryTruncated) {
            Text(state.attendanceHistoryError ?: "Dữ liệu vượt giới hạn; hãy chọn khoảng ngày nhỏ hơn.",
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryAttendanceRange) { Text("Tải lại dữ liệu") }
        }
    }
}
