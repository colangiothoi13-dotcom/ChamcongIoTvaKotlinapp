package vn.chamcong.iot.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.domain.weekDates
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel

@Composable
fun WorkSummaryScreen(state: MainUiState, vm: MainViewModel) {
    val summary = state.weeklyWorkSummary
    val dates = weekDates(state.selectedWeekStart)
    val maxHours = summary.dailyWorkedHours.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Tổng hợp tuần", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.moveWeek(-1) }) { Text("‹ Tuần trước") }
                Text("${state.selectedWeekStart} – ${dates.last()}", modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { vm.moveWeek(1) }) { Text("Tuần sau ›") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetric("Giờ làm", "${summary.totalWorkedHours}h", Modifier.weight(1f))
                SummaryMetric("Ngày công", summary.workdays.toString(), Modifier.weight(1f))
                SummaryMetric("Tăng ca", "${summary.totalOvertimeHours}h", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetric("Đi trễ", summary.lateCount.toString(), Modifier.weight(1f))
                SummaryMetric("Về sớm", summary.earlyLeaveCount.toString(), Modifier.weight(1f))
                SummaryMetric("Nghỉ phép", summary.approvedLeaveDays.toString(), Modifier.weight(1f))
            }
        }
        item { SummaryMetric("Vắng không phép", summary.unauthorizedAbsenceDays.toString(), Modifier.fillMaxWidth()) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Biểu đồ giờ làm trong tuần", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        dates.forEach { date ->
                            val hours = summary.dailyWorkedHours[date] ?: 0.0
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Text("${hours}h", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.width(24.dp).height((hours / maxHours * 100).coerceAtLeast(6.0).dp).background(Color(0xFF147D64)))
                                Spacer(Modifier.height(4.dp))
                                Text("${date.dayOfMonth}/${date.monthValue}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.bodySmall) } }
}
