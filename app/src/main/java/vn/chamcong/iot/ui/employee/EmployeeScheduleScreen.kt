package vn.chamcong.iot.ui.employee

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.CalculationLoadingNotice
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.AppSpacing
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import vn.chamcong.iot.domain.isWeeklyScheduleSubmissionOpen
import vn.chamcong.iot.domain.defaultShiftTemplates
import vn.chamcong.iot.ui.schedule.assignableScheduleShifts
import vn.chamcong.iot.ui.schedule.ScheduleShiftStatus
import vn.chamcong.iot.ui.schedule.ScheduleStatusTone
import vn.chamcong.iot.ui.schedule.scheduleShiftStatus

private val employeeScheduleZone = ZoneId.of("Asia/Ho_Chi_Minh")
private val employeeScheduleMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("vi", "VN"))
private val employeeScheduleDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val employeeScheduleWeekFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val vietnameseLocale = Locale("vi", "VN")

private enum class EmployeeScheduleView { WEEK, MONTH }
private enum class EmployeeScheduleSection { ASSIGNED, REGISTRATION }

/** A calendar first, followed by the shifts for the selected date. */
@Composable
fun EmployeeScheduleScreen(
    state: MainUiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    startInRegistration: Boolean = false
) {
    val schedules = state.effectiveEmployeeSchedules
    val shifts = state.calculationShifts
    val attendance = state.employeeAttendanceForSummaries
    var now by remember { mutableStateOf(Instant.now()) }
    var section by rememberSaveable(startInRegistration) {
        mutableStateOf(if (startInRegistration) EmployeeScheduleSection.REGISTRATION else EmployeeScheduleSection.ASSIGNED)
    }
    var view by rememberSaveable { mutableStateOf(EmployeeScheduleView.MONTH) }
    var anchorDate by rememberSaveable { mutableStateOf(LocalDate.now(employeeScheduleZone)) }
    val weekStart = state.employeeWeeklyTargetWeekStart
    val request = state.employeeWeeklyScheduleRequest
    // Keep the draft when switching between the assigned calendar and registration.
    var selections by rememberSaveable(state.currentEmployee?.id, weekStart, request?.status, request?.shiftsByDate) {
        mutableStateOf(request?.shiftsByDate.orEmpty().mapValues { it.value.toList() })
    }
    var note by rememberSaveable(state.currentEmployee?.id, weekStart, request?.reason) {
        mutableStateOf(request?.reason.orEmpty())
    }
    var registrationDate by rememberSaveable(weekStart) { mutableStateOf(weekStart) }
    val scrollState = rememberScrollState()
    LaunchedEffect(section) { scrollState.scrollTo(0) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(60_000L)
        }
    }

    val calendarDates = remember(view, anchorDate) {
        when (view) {
            EmployeeScheduleView.WEEK -> employeeScheduleWeekDates(anchorDate)
            EmployeeScheduleView.MONTH -> employeeScheduleMonthDates(YearMonth.from(anchorDate))
        }
    }
    val dates = calendarDates.filterNotNull()
    val observedMonth = YearMonth.from(if (section == EmployeeScheduleSection.ASSIGNED) anchorDate else registrationDate)
    LaunchedEffect(observedMonth, state.currentEmployee?.id) {
        if (!inPreview) vm.selectEmployeeScheduleMonth(observedMonth)
    }
    val rangeStart = if (section == EmployeeScheduleSection.ASSIGNED) dates.first() else weekStart.minusWeeks(1)
    val rangeEnd = if (section == EmployeeScheduleSection.ASSIGNED) dates.last() else weekStart.minusWeeks(1).plusDays(5)
    LaunchedEffect(section, rangeStart, rangeEnd, state.currentEmployee?.id) {
        if (!inPreview) {
            state.currentEmployee?.id?.let { employeeId ->
                // A displayed or copied week can span two months.
                vm.loadAttendanceRange(rangeStart, rangeEnd, employeeId, force = true)
            }
        }
    }
    val assignedRangeReady = inPreview || (state.currentEmployee?.id?.let {
        state.hasCompleteCalculationRange(rangeStart, rangeEnd, it)
    } == true)
    val schedulesByDate = remember(schedules) {
        schedules.mapNotNull { schedule ->
            runCatching { LocalDate.parse(schedule.date) }.getOrNull()?.let { it to schedule }
        }.groupBy({ it.first }, { it.second })
    }
    val shiftsById = remember(shifts) { shifts.associateBy(WorkShift::id) }
    val periodLabel = when (view) {
        EmployeeScheduleView.WEEK -> "${dates.first().format(employeeScheduleWeekFormatter)} – ${dates.last().format(employeeScheduleWeekFormatter)}"
        EmployeeScheduleView.MONTH -> YearMonth.from(anchorDate).format(employeeScheduleMonthFormatter)
    }
    val assignedDayCount = dates.count { date -> schedulesByDate[date].orEmpty().any { employeeScheduleShiftIds(it).isNotEmpty() } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
    ) {
        TabRow(selectedTabIndex = section.ordinal) {
            Tab(
                selected = section == EmployeeScheduleSection.ASSIGNED,
                onClick = { section = EmployeeScheduleSection.ASSIGNED },
                text = { Text("Lịch của tôi") }
            )
            Tab(
                selected = section == EmployeeScheduleSection.REGISTRATION,
                onClick = { section = EmployeeScheduleSection.REGISTRATION },
                text = { Text("Đăng ký tuần sau") }
            )
        }
        if (section == EmployeeScheduleSection.REGISTRATION) {
            EmployeeWeeklyScheduleRegistration(
                state = state,
                vm = vm,
                now = now,
                selectedDate = registrationDate,
                onDateSelected = { registrationDate = it },
                selections = selections,
                onSelectionsChange = { selections = it },
                note = note,
                onNoteChange = { note = it }
            )
        } else {
            CalculationLoadingNotice(state, vm)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                FilterChip(
                    selected = view == EmployeeScheduleView.MONTH,
                    onClick = { view = EmployeeScheduleView.MONTH },
                    label = { Text("Tháng") }
                )
                FilterChip(
                    selected = view == EmployeeScheduleView.WEEK,
                    onClick = { view = EmployeeScheduleView.WEEK },
                    label = { Text("Tuần") }
                )
                Box(Modifier.weight(1f))
                TextButton(onClick = { anchorDate = LocalDate.now(employeeScheduleZone) }) { Text("Hôm nay") }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xSmall, vertical = AppSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            anchorDate = if (view == EmployeeScheduleView.WEEK) anchorDate.minusWeeks(1) else anchorDate.minusMonths(1)
                        }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Kỳ trước") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(periodLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Text(if (assignedRangeReady) "$assignedDayCount/${dates.size} ngày có ca" else "Đang chờ dữ liệu lịch làm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            anchorDate = if (view == EmployeeScheduleView.WEEK) anchorDate.plusWeeks(1) else anchorDate.plusMonths(1)
                        }) { Icon(Icons.Default.ChevronRight, contentDescription = "Kỳ sau") }
                    }
                    EmployeeScheduleCalendar(
                        dates = calendarDates,
                        selectedDate = anchorDate,
                        onDateSelected = { anchorDate = it },
                        summaries = dates.associateWith { date ->
                            if (!assignedRangeReady) return@associateWith "…"
                            val ids = schedulesByDate[date].orEmpty().flatMap(::employeeScheduleShiftIds).distinct()
                            employeeCalendarShiftSummary(ids, shiftsById).ifBlank {
                                if (state.calculationEmployeeOvertimeRequests.any { it.workDate == date.toString() }) "TC" else ""
                            }
                        }
                    )
                    Text("S: ca sáng • C: ca chiều • TC: tăng ca", modifier = Modifier.padding(horizontal = AppSpacing.small), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Chọn ngày trên lịch để xem ca làm.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (assignedRangeReady) {
                EmployeeScheduleDayCard(
                    date = anchorDate,
                    schedules = schedulesByDate[anchorDate].orEmpty(),
                    shiftsById = shiftsById,
                    overtimeRequests = state.calculationEmployeeOvertimeRequests.filter { it.workDate == anchorDate.toString() },
                    attendance = attendance,
                    now = now
                )
            }
        }
    }
}

private fun employeeCalendarShiftSummary(ids: List<String>, shiftsById: Map<String, WorkShift>): String {
    val categories = ids.mapNotNull { shiftsById[it]?.category }.toSet()
    return listOfNotNull(
        "S".takeIf { ShiftCategory.MORNING.name in categories },
        "C".takeIf { ShiftCategory.EVENING.name in categories }
    ).joinToString("+").ifBlank { if (ids.isNotEmpty()) "${ids.size} ca" else "" }
}

@Composable
private fun EmployeeScheduleCalendar(
    dates: List<LocalDate?>,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    summaries: Map<LocalDate, String>,
    enabledDates: Set<LocalDate>? = null
) {
    val today = LocalDate.now(employeeScheduleZone)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN").forEach { label ->
                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { date ->
                    if (date == null) {
                        Box(Modifier.weight(1f).heightIn(min = 60.dp))
                    } else {
                        val enabled = enabledDates == null || date in enabledDates
                        val selected = date == selectedDate
                        val summary = summaries[date].orEmpty()
                        val colors = MaterialTheme.colorScheme
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                                .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = { onDateSelected(date) })
                                .semantics {
                                    contentDescription = employeeScheduleFullDate(date)
                                    stateDescription = when (summary) {
                                        "S" -> "Ca sáng"
                                        "C" -> "Ca chiều"
                                        "S+C" -> "Ca sáng và ca chiều"
                                        "TC" -> "Tăng ca"
                                        else -> if (!enabled) "Không đăng ký ngày này" else summary.ifBlank { "Chưa có ca" }
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) colors.primaryContainer else colors.surface,
                            contentColor = when {
                                selected -> colors.onPrimaryContainer
                                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                                else -> colors.onSurface
                            },
                            border = if (date == today && !selected) BorderStroke(1.dp, colors.primary) else null
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = AppSpacing.small),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
                            ) {
                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected || date == today) FontWeight.Bold else FontWeight.Medium)
                                Text(summary.ifBlank { "–" }, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun employeeScheduleFullDate(date: LocalDate): String =
    "${date.dayOfWeek.getDisplayName(TextStyle.FULL, vietnameseLocale).replaceFirstChar { it.titlecase(vietnameseLocale) }}, ${date.format(employeeScheduleDateFormatter)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmployeeWeeklyScheduleRegistration(
    state: MainUiState,
    vm: MainViewModel,
    now: Instant,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    selections: Map<String, List<String>>,
    onSelectionsChange: (Map<String, List<String>>) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit
) {
    val weekStart = state.employeeWeeklyTargetWeekStart
    val weekDates = (0L..5L).map(weekStart::plusDays)
    val request = state.employeeWeeklyScheduleRequest
    val mainShifts = assignableScheduleShifts(state.shifts.filter {
        it.active && it.category in setOf(ShiftCategory.MORNING.name, ShiftCategory.EVENING.name)
    })
    val shiftsById = remember(state.shifts) { state.shifts.associateBy(WorkShift::id) }
    val isApproved = request?.status == WeeklyScheduleRequestStatus.APPROVED
    val overdue = !isWeeklyScheduleSubmissionOpen(weekStart, now, employeeScheduleZone)
    val ready = state.employeeWeeklyScheduleRequestReady
    val editable = ready && !isApproved && !overdue && !state.saving
    val copyStart = weekStart.minusWeeks(1)
    val copyRangeReady = LocalInspectionMode.current || (state.currentEmployee?.id?.let {
        state.hasCompleteCalculationRange(copyStart, copyStart.plusDays(5), it)
    } == true)
    val morning = mainShifts.firstOrNull { it.category == ShiftCategory.MORNING.name }
    val afternoon = mainShifts.firstOrNull { it.category == ShiftCategory.EVENING.name }
    val selectedIds = selections[selectedDate.toString()].orEmpty()
    val displayedShifts = if (isApproved) {
        selectedIds.mapNotNull(shiftsById::get).distinctBy(WorkShift::id).sortedBy(WorkShift::startTime)
    } else mainShifts
    val hasUnavailableSelections = !isApproved && selections.values.any { ids ->
        employeeRegistrationShiftIds(ids, shiftsById).size != ids.distinct().size
    }
    val selectedDayCount = weekDates.count { selections[it.toString()].orEmpty().isNotEmpty() }
    val statusLabel = when (request?.status) {
        WeeklyScheduleRequestStatus.PENDING -> "Chờ Admin duyệt"
        WeeklyScheduleRequestStatus.NEEDS_REVISION -> "Cần chỉnh sửa"
        WeeklyScheduleRequestStatus.APPROVED -> "Đã duyệt"
        null -> "Chưa đăng ký"
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Đăng ký lịch tuần sau", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${weekStart.format(employeeScheduleDateFormatter)} – ${weekDates.last().format(employeeScheduleDateFormatter)}", style = MaterialTheme.typography.bodyMedium)
                if (ready) {
                    Text("Trạng thái: $statusLabel", color = if (request?.status == WeeklyScheduleRequestStatus.NEEDS_REVISION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    request?.reviewNote?.takeIf(String::isNotBlank)?.let {
                        Text("Phản hồi Admin: $it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (!ready) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    if (state.employeeWeeklyScheduleRequestError != null) {
                        Text(state.employeeWeeklyScheduleRequestError, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::retryEmployeeWeeklyScheduleRequest, enabled = !state.saving) {
                            Text("Thử lại")
                        }
                    } else {
                        CircularProgressIndicator()
                        Text("Đang tải đăng ký tuần sau…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xSmall, vertical = AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text("Chọn ngày để xem và chọn ca", modifier = Modifier.padding(horizontal = AppSpacing.small), style = MaterialTheme.typography.titleSmall)
                EmployeeScheduleCalendar(
                    dates = weekDates + listOf(null),
                    selectedDate = selectedDate,
                    onDateSelected = onDateSelected,
                    summaries = weekDates.associateWith { employeeCalendarShiftSummary(selections[it.toString()].orEmpty(), shiftsById) },
                    enabledDates = weekDates.toSet()
                )
                Text("Đã chọn $selectedDayCount/6 ngày • S: sáng • C: chiều", modifier = Modifier.padding(horizontal = AppSpacing.small), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(employeeScheduleFullDate(selectedDate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!isApproved && mainShifts.isEmpty()) {
                    Text("Chưa có ca sáng/chiều đang hoạt động. Vui lòng liên hệ Admin để cấu hình ca.", color = MaterialTheme.colorScheme.error)
                }
                if (isApproved && selectedIds.isEmpty()) Text("Không đăng ký ca cho ngày này.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isApproved && selectedIds.any { it !in shiftsById }) Text("Thông tin một ca đã đăng ký không còn tồn tại. Vui lòng liên hệ Admin.", color = MaterialTheme.colorScheme.error)
                displayedShifts.forEach { shift ->
                    val selected = selectedIds.any { shiftsById[it]?.category == shift.category }
                    val displayedShift = selectedIds.firstOrNull { shiftsById[it]?.category == shift.category }?.let(shiftsById::get) ?: shift
                    FilterChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = selected,
                        enabled = editable,
                        onClick = {
                            onSelectionsChange(selections + (selectedDate.toString() to employeeToggleRegistrationShift(selectedIds, shift, shiftsById)))
                        },
                        label = {
                            Column(Modifier.padding(vertical = AppSpacing.small)) {
                                Text(if (shift.category == ShiftCategory.MORNING.name) "Ca sáng" else "Ca chiều", fontWeight = FontWeight.SemiBold)
                                Text("${displayedShift.startTime} – ${displayedShift.endTime}", style = MaterialTheme.typography.bodyMedium)
                            }
                        },
                        leadingIcon = if (selected) ({ Icon(Icons.Default.CheckCircle, contentDescription = null) }) else null
                    )
                }
                if (!isApproved && mainShifts.isNotEmpty() && (morning == null || afternoon == null)) {
                    Text("${if (morning == null) "Ca sáng" else "Ca chiều"} chưa được cấu hình hoặc đã ngừng hoạt động. Vui lòng liên hệ Admin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (isApproved) Text("Lịch đã được duyệt.", color = MaterialTheme.colorScheme.primary)
                else Text("Chọn cả hai ca để đăng ký làm cả ngày; chạm lại để bỏ chọn.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!isApproved) {
            Text("Chọn nhanh cho cả tuần", style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
                OutlinedButton(enabled = editable && morning != null, onClick = {
                    onSelectionsChange(weekDates.associate { it.toString() to listOfNotNull(morning?.id) })
                }) { Text("Sáng cả tuần") }
                OutlinedButton(enabled = editable && afternoon != null, onClick = {
                    onSelectionsChange(weekDates.associate { it.toString() to listOfNotNull(afternoon?.id) })
                }) { Text("Chiều cả tuần") }
                OutlinedButton(enabled = editable && morning != null && afternoon != null, onClick = {
                    onSelectionsChange(weekDates.associate { it.toString() to listOfNotNull(morning?.id, afternoon?.id) })
                }) { Text("Cả ngày cả tuần") }
                OutlinedButton(enabled = editable && copyRangeReady, onClick = {
                    onSelectionsChange(weekDates.associate { targetDate ->
                        val source = state.effectiveEmployeeSchedules.filter { it.date == targetDate.minusWeeks(1).toString() }
                        targetDate.toString() to employeeRegistrationShiftIds(source.flatMap(::employeeScheduleShiftIds), shiftsById)
                    })
                }) { Text("Sao chép tuần này") }
                TextButton(enabled = editable, onClick = {
                    onSelectionsChange(weekDates.associate { it.toString() to emptyList() })
                }) { Text("Xóa chọn") }
            }
            if (!copyRangeReady) {
                Text("Cần tải đầy đủ lịch tuần này để sao chép.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CalculationLoadingNotice(state, vm)
            }
            if (hasUnavailableSelections) Text("Một số ca đã chọn không còn hoạt động. Vui lòng chọn lại ca hoặc xóa chọn.", color = MaterialTheme.colorScheme.error)
            if (overdue) {
                Text("Đã quá hạn gửi (thứ Bảy 12:00). Vui lòng liên hệ Admin.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Hạn gửi: thứ Bảy trước 12:00. Admin duyệt trước 17:00.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) onNoteChange(it) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ghi chú (không bắt buộc)") },
                minLines = 1,
                maxLines = 3
            )
            Button(
                onClick = { vm.submitWeeklySchedule(selections, note) },
                modifier = Modifier.fillMaxWidth(),
                enabled = editable && mainShifts.isNotEmpty() && !hasUnavailableSelections
            ) {
                Text(if (state.saving) "Đang gửi…" else if (request == null) "Gửi đăng ký" else "Cập nhật đăng ký")
            }
        }
    }
}

@Composable
private fun EmployeeScheduleDayCard(
    date: LocalDate,
    schedules: List<WorkSchedule>,
    shiftsById: Map<String, WorkShift>,
    overtimeRequests: List<OvertimeRequest>,
    attendance: List<Attendance>,
    now: Instant
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Text(
                employeeScheduleFullDate(date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val assignedSchedules = schedules.filter { employeeScheduleShiftIds(it).isNotEmpty() }
            if (assignedSchedules.isEmpty()) {
                Text(
                    "Chưa được phân ca",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                assignedSchedules.forEach { schedule ->
                    val shiftIds = employeeScheduleShiftIds(schedule)
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
                            if (shift != null) {
                                ScheduleStatusPill(
                                    scheduleShiftStatus(
                                        employeeId = schedule.employeeId,
                                        date = date,
                                        shift = shift,
                                        attendance = attendance,
                                        zoneId = employeeScheduleZone,
                                        now = now
                                    )
                                )
                            }
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

@Composable
private fun ScheduleStatusPill(status: ScheduleShiftStatus) {
    val colors = MaterialTheme.colorScheme
    val container = when (status.tone) {
        ScheduleStatusTone.NEUTRAL -> colors.surfaceVariant
        ScheduleStatusTone.ACTIVE -> colors.primaryContainer
        ScheduleStatusTone.SUCCESS -> colors.secondaryContainer
        ScheduleStatusTone.WARNING -> colors.tertiaryContainer
        ScheduleStatusTone.ERROR -> colors.errorContainer
    }
    val content = when (status.tone) {
        ScheduleStatusTone.NEUTRAL -> colors.onSurfaceVariant
        ScheduleStatusTone.ACTIVE -> colors.onPrimaryContainer
        ScheduleStatusTone.SUCCESS -> colors.onSecondaryContainer
        ScheduleStatusTone.WARNING -> colors.onTertiaryContainer
        ScheduleStatusTone.ERROR -> colors.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = AppSpacing.small, vertical = AppSpacing.xSmall),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Preview(name = "Lịch tháng • điện thoại", widthDp = 375, heightDp = 812, showBackground = true)
@Preview(name = "Lịch tháng • chữ lớn", widthDp = 375, heightDp = 812, fontScale = 1.5f, showBackground = true)
@Preview(name = "Lịch tháng • ngang", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
private fun EmployeeScheduleScreenPreview() {
    PreviewStateScreen { state, vm -> EmployeeScheduleScreen(state, vm) }
}

@Preview(name = "Đăng ký • điện thoại", widthDp = 375, heightDp = 900, showBackground = true)
@Preview(name = "Đăng ký • chữ lớn", widthDp = 375, heightDp = 900, fontScale = 1.5f, showBackground = true)
@Composable
private fun EmployeeWeeklyScheduleRegistrationPreview() {
    PreviewStateScreen { state, vm ->
        val shifts = remember { defaultShiftTemplates().map { it.resolve() } }
        val weekStart = state.employeeWeeklyTargetWeekStart
        var selectedDate by remember { mutableStateOf(weekStart) }
        var selections by remember {
            mutableStateOf(mapOf(weekStart.toString() to shifts.map(WorkShift::id)))
        }
        var note by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmployeeWeeklyScheduleRegistration(
                state = state.copy(shifts = shifts, employeeWeeklyScheduleRequestLoadedWeekStart = weekStart),
                vm = vm,
                now = weekStart.minusDays(4).atStartOfDay(employeeScheduleZone).toInstant(),
                selectedDate = selectedDate,
                onDateSelected = { selectedDate = it },
                selections = selections,
                onSelectionsChange = { selections = it },
                note = note,
                onNoteChange = { note = it }
            )
        }
    }
}
