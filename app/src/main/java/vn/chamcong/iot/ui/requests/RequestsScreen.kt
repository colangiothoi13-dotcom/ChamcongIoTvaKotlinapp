package vn.chamcong.iot.ui.requests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.overtime.AdminOvertimeRequestsList

internal enum class AdminRequestGroup(val label: String) {
    WEEKLY_SCHEDULE("Đăng ký tuần"),
    OTHER_REQUESTS("Đơn khác"),
    OVERTIME_REQUESTS("Tăng ca")
}

@Composable
internal fun RequestsScreen(
    state: MainUiState,
    vm: MainViewModel,
    initialGroup: AdminRequestGroup = AdminRequestGroup.WEEKLY_SCHEDULE
) {
    var group by rememberSaveable(initialGroup) { mutableStateOf(initialGroup) }
    var weeklyInitialized by rememberSaveable(initialGroup) { mutableStateOf(false) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(group, initialGroup) {
        if (!inPreview && group == AdminRequestGroup.WEEKLY_SCHEDULE && !weeklyInitialized) {
            weeklyInitialized = true
            vm.selectWeeklyRegistrationWeek()
        }
    }
    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            AdminRequestGroup.entries.forEach { option ->
                FilterChip(
                    selected = group == option,
                    onClick = { group = option },
                    label = { Text(option.label) },
                    enabled = !state.saving
                )
            }
        }
    }
    when (group) {
        AdminRequestGroup.WEEKLY_SCHEDULE -> AdminWeeklyScheduleRequestsList(state, vm, header)
        AdminRequestGroup.OTHER_REQUESTS -> AdminOtherRequestsList(state, vm, header)
        AdminRequestGroup.OVERTIME_REQUESTS -> AdminOvertimeRequestsList(state, vm, header)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RequestsScreenPreview() {
    PreviewStateScreen { state, vm -> RequestsScreen(state, vm) }
}
