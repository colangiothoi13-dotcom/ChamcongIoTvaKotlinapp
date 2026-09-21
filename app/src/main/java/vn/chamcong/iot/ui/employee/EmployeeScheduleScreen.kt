package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.AppSpacing
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import vn.chamcong.iot.domain.mondayOfWeek

private val employeeScheduleZone = ZoneId.of("Asia/Ho_Chi_Minh")
private val employeeScheduleMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("vi", "VN"))
private val employeeScheduleDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val employeeScheduleWeekFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val vietnameseLocale = Locale("vi", "VN")

private enum class EmployeeScheduleView { WEEK, MONTH }

/** Employee view of assigned shifts, with unassigned days kept visible in both ranges. */
@Composable
fun EmployeeScheduleScreen(
    state: MainUiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val schedules = state.employeeSchedules
    val shifts = state.shifts
    var view by remember { mutableStateOf(EmployeeScheduleView.WEEK) }
    var anchorDate by remember { mutableStateOf(LocalDate.now(employeeScheduleZone)) }

    val dates = remember(view, anchorDate) {
        when (view) {
            EmployeeScheduleView.WEEK -> {
                val monday = anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
                (0L..6L).map(monday::plusDays)
            }
            EmployeeScheduleView.MONTH -> {
                val month = YearMonth.from(anchorDate)
                (1..month.lengthOfMonth()).map { month.atDay(it) }
            }
        }
    }
    val schedulesByDate = remember(schedules, dates) {
        schedules.mapNotNull { schedule ->
            runCatching { LocalDate.parse(schedule.date) }.getOrNull()?.let { it to schedule }
        }.groupBy({ it.first }, { it.second })
    }
    val shiftsById = remember(shifts) { shifts.associateBy(WorkShift::id) }
    val periodLabel = when (view) {
        EmployeeScheduleView.WEEK -> "${dates.first().format(employeeScheduleWeekFormatter)} – ${dates.last().format(employeeScheduleWeekFormatter)}"
        EmployeeScheduleView.MONTH -> YearMonth.from(anchorDate).format(employeeScheduleMonthFormatter)
    }
    val assignedDayCount = dates.count { date -> schedulesByDate[date].orEmpty().any { it.shiftId.isNotBlank() } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        Text(
            "Lịch làm việc",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        EmployeeWeeklyScheduleRegistration(state, vm)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            FilterChip(
                selected = view == EmployeeScheduleView.WEEK,
                onClick = { view = EmployeeScheduleView.WEEK },
                label = { Text("Tuần") }
            )
            FilterChip(
                selected = view == EmployeeScheduleView.MONTH,
                onClick = { view = EmployeeScheduleView.MONTH },
                label = { Text("Tháng") }
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.small, vertical = AppSpacing.xSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        anchorDate = if (view == EmployeeScheduleView.WEEK) anchorDate.minusWeeks(1) else anchorDate.minusMonths(1)
                    }
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Kỳ trước")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(periodLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$assignedDayCount/${dates.size} ngày có ca",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        anchorDate = if (view == EmployeeScheduleView.WEEK) anchorDate.plusWeeks(1) else anchorDate.plusMonths(1)
                    }
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Kỳ sau")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            dates.forEach { date ->
                EmployeeScheduleDayCard(
                    date = date,
                    schedules = schedulesByDate[date].orEmpty(),
                    shiftsById = shiftsById,
                    overtimeRequests = state.employeeOvertimeRequests.filter { it.workDate == date.toString() }
                )
            }
        }
    }
}

@Composable
private fun EmployeeWeeklyScheduleRegistration(state: MainUiState, vm: MainViewModel) {
    val currentWeek = mondayOfWeek(LocalDate.now(employeeScheduleZone))
    val weekStart = currentWeek.plusWeeks(1)
    val weekDates = (0L..5L).map(weekStart::plusDays)
    val request = state.employeeWeeklyScheduleRequest
    val mainShifts = state.shifts
        .filter { it.active && it.category in setOf(ShiftCategory.MORNING.name, ShiftCategory.EVENING.name) }
        .sortedBy(WorkShift::startTime)
    val shiftsById = remember(state.shifts) { state.shifts.associateBy(WorkShift::id) }
    var selections by remember(weekStart, request?.status, request?.shiftsByDate) {
        mutableStateOf(request?.shiftsByDate.orEmpty().mapValues { it.value.toList() })
    }
    var note by remember(weekStart, request?.reason) { mutableStateOf(request?.reason.orEmpty()) }
    val isApproved = request?.status == WeeklyScheduleRequestStatus.APPROVED
    val deadline = weekStart.minusDays(2).atTime(12, 0).atZone(employeeScheduleZone).toInstant()
    val overdue = Instant.now().isAfter(deadline)
    val statusLabel = when (request?.status) {
        WeeklyScheduleRequestStatus.PENDING -> "Chờ Admin duyệt"
        WeeklyScheduleRequestStatus.NEEDS_REVISION -> "Cần chỉnh sửa"
        WeeklyScheduleRequestStatus.APPROVED -> "Đã duyệt"
        null -> "Chưa đăng ký"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Text("Đăng ký lịch tuần sau", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Tuần bắt đầu ${weekStart.format(employeeScheduleDateFormatter)} • chọn ca từ thứ Hai đến thứ Bảy.")
            Text("Trạng thái: $statusLabel", color = if (request?.status == WeeklyScheduleRequestStatus.NEEDS_REVISION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (overdue && request?.status != WeeklyScheduleRequestStatus.APPROVED) {
                Text("Đã quá hạn gửi (thứ Bảy 12:00). Đăng ký vẫn ở trạng thái chờ Admin, không tự được duyệt.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Hạn gửi: thứ Bảy trước 12:00. Admin duyệt trước 17:00.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            request?.reviewNote?.takeIf(String::isNotBlank)?.let {
                Text("Phản hồi Admin: $it", color = MaterialTheme.colorScheme.error)
            }
            if (!isApproved) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Button(onClick = {
                        val morningId = mainShifts.firstOrNull { it.category == ShiftCategory.MORNING.name }?.id
                        selections = weekDates.associate { it.toString() to listOfNotNull(morningId) }
                    }) { Text("Ca sáng cả tuần") }
                    Button(onClick = {
                        val afternoonId = mainShifts.firstOrNull { it.category == ShiftCategory.EVENING.name }?.id
                        selections = weekDates.associate { it.toString() to listOfNotNull(afternoonId) }
                    }) { Text("Ca chiều cả tuần") }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Button(onClick = {
                        val morningId = mainShifts.firstOrNull { it.category == ShiftCategory.MORNING.name }?.id
                        val afternoonId = mainShifts.firstOrNull { it.category == ShiftCategory.EVENING.name }?.id
                        selections = weekDates.associate { it.toString() to listOfNotNull(morningId, afternoonId) }
                    }) { Text("Cả ngày") }
                    Button(onClick = {
                        selections = weekDates.associate { targetDate ->
                            val sourceDate = targetDate.minusWeeks(1).toString()
                            val source = state.employeeSchedules.firstOrNull { it.date == sourceDate }
                            val ids = source?.let { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.orEmpty()
                                .filter { it in shiftsById }
                            targetDate.toString() to ids.take(2)
                        }
                    }) { Text("Sao chép tuần này") }
                    Button(onClick = { selections = weekDates.associate { it.toString() to emptyList() } }) { Text("Xóa chọn") }
                }
                if (mainShifts.isEmpty()) Text("Chưa có ca sáng/chiều đang hoạt động để đăng ký.", color = MaterialTheme.colorScheme.error)
                weekDates.forEach { date ->
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                        Text("${date.dayOfWeek.getDisplayName(TextStyle.FULL, vietnameseLocale).replaceFirstChar { it.titlecase(vietnameseLocale) }} ${date.format(employeeScheduleDateFormatter)}", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            mainShifts.forEach { shift ->
                                val key = date.toString()
                                val selected = shift.id in selections[key].orEmpty()
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val current = selections[key].orEmpty()
                                        val next = if (selected) current - shift.id else {
                                            current.filterNot { shiftsById[it]?.category == shift.category } + shift.id
                                        }
                                        selections = selections + (key to next.take(2))
                                    },
                                    label = { Text("${if (shift.category == ShiftCategory.MORNING.name) "Sáng" else "Chiều"} ${shift.startTime}–${shift.endTime}") }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 500) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ghi chú (không bắt buộc)") },
                    minLines = 1,
                    maxLines = 3
                )
                Button(
                    onClick = { vm.submitWeeklySchedule(selections, note) },
                    enabled = !state.saving && mainShifts.isNotEmpty()
                ) {
                    Text(if (request == null) "Gửi đăng ký" else "Gửi lại / cập nhật đăng ký")
                }
            }
        }
    }
}

@Composable
private fun EmployeeScheduleDayCard(
    date: LocalDate,
    schedules: List<WorkSchedule>,
    shiftsById: Map<String, WorkShift>,
    overtimeRequests: List<OvertimeRequest>
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Text(
                "${date.dayOfWeek.getDisplayName(TextStyle.FULL, vietnameseLocale).replaceFirstChar { it.titlecase(vietnameseLocale) }}, ${date.format(employeeScheduleDateFormatter)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val assignedSchedules = schedules.filter { it.shiftId.isNotBlank() }
            if (assignedSchedules.isEmpty()) {
                Text(
                    "Chưa được phân ca",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                assignedSchedules.forEach { schedule ->
                    val shiftIds = schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }.filter(String::isNotBlank)
                    shiftIds.forEachIndexed { index, shiftId ->
                        val shift = shiftsById[shiftId]
                        val name = shift?.name?.takeIf(String::isNotBlank)
                            ?: schedule.shiftName.takeIf(String::isNotBlank).takeIf { index == 0 }
                            ?: "Ca làm"
                        val startTime = shift?.startTime?.takeIf(String::isNotBlank)
                        val endTime = shift?.endTime?.takeIf(String::isNotBlank)
                        val times = if (startTime != null && endTime != null) "$startTime – $endTime" else "Chưa có khung giờ"
                        val overnight = if (startTime != null && endTime != null) {
                            runCatching {
                                java.time.LocalTime.parse(endTime).isBefore(java.time.LocalTime.parse(startTime))
                            }.getOrDefault(false)
                        } else false
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                if (overnight) "$times (qua ngày hôm sau)" else times,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            overtimeRequests.forEach { request ->
                val status = when (request.status) {
                    OvertimeRequestStatus.APPROVED.name -> "đã duyệt"
                    OvertimeRequestStatus.REJECTED.name -> "từ chối"
                    else -> "chờ duyệt"
                }
                Text("Tăng ca ${request.startTime}–${request.endTime} • $status", color = MaterialTheme.colorScheme.primary)
                request.reason.takeIf(String::isNotBlank)?.let { Text("Lý do: $it", style = MaterialTheme.typography.bodySmall) }
                request.rejectionReason?.takeIf(String::isNotBlank)?.let { Text("Phản hồi: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
