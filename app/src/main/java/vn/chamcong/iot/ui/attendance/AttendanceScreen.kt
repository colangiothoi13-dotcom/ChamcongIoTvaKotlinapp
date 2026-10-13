package vn.chamcong.iot.ui.attendance

import androidx.compose.ui.tooling.preview.Preview
import vn.chamcong.iot.ui.PreviewStateScreen
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.AdminPageHeader
import vn.chamcong.iot.ui.AdminSectionCard
import vn.chamcong.iot.ui.AdminMetricCard
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.AttendanceList
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import vn.chamcong.iot.ui.CalculationLoadingNotice
import vn.chamcong.iot.ui.loadAttendanceRange
import vn.chamcong.iot.ui.loadAttendanceRecordHistory
import vn.chamcong.iot.ui.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import com.google.firebase.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import vn.chamcong.iot.domain.assignAttendanceScheduleDates
import vn.chamcong.iot.domain.employeeDaySummary
import vn.chamcong.iot.domain.employeeDaySummaryForSchedule
import vn.chamcong.iot.domain.scheduledShifts
import vn.chamcong.iot.domain.latestAdjustment
import vn.chamcong.iot.domain.nearestScheduledShift
import vn.chamcong.iot.domain.approvedLeaveShiftIdsForDate
import vn.chamcong.iot.domain.canCorrectAttendanceClassification
import vn.chamcong.iot.domain.latestAttendanceClassificationOverride
import vn.chamcong.iot.domain.parseAttendanceDateRange
import vn.chamcong.iot.domain.isAcceptedAttendance
import vn.chamcong.iot.domain.isAbnormalAttendance
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.AttendanceRecordHistory

@Composable
fun AttendanceScreen(state: MainUiState, vm: MainViewModel, initialAdjustmentRequest: Int = 0, onAdjustmentRequestHandled: () -> Unit = {}) {
    val range = state.attendanceSelectedRange
    val inPreview = LocalInspectionMode.current
    val employeeId = state.attendanceEmployeeFilter
    var manualAdjustment by remember { mutableStateOf(false) }
    LaunchedEffect(initialAdjustmentRequest) {
        if (initialAdjustmentRequest > 0 && vm.hasAdminAccess()) {
            vm.clearError()
            manualAdjustment = true
            onAdjustmentRequestHandled()
        }
    }
    LaunchedEffect(range, employeeId, manualAdjustment) {
        if (!inPreview && !manualAdjustment) range?.let { vm.loadAttendanceRange(it.start, it.endInclusive, employeeId, force = true) }
    }
    val ready = range == null || state.hasCompleteCalculationRange(range.start, range.endInclusive, employeeId)
    val visibleRows = if (ready) state.visibleAttendance else emptyList()
    var target by remember { mutableStateOf<AttendanceAdjustmentTarget?>(null) }
    var scanReview by remember { mutableStateOf<Pair<Attendance, Attendance>?>(null) }
    var previousClassification by remember { mutableStateOf<AttendanceClassificationOverride?>(null) }
    var classificationRequest by remember { mutableStateOf<Attendance?>(null) }
    var classificationLoading by remember { mutableStateOf(false) }
    var classificationError by remember { mutableStateOf<String?>(null) }
    var classificationRetry by remember { mutableStateOf(0) }
    var detailTarget by remember { mutableStateOf<Attendance?>(null) }
    var offScheduleTarget by remember { mutableStateOf<Attendance?>(null) }
    var employeeMenu by remember { mutableStateOf(false) }
    var departmentMenu by remember { mutableStateOf(false) }
    val statuses = listOf("Tất cả" to null, "Đúng giờ" to "NORMAL", "Đi trễ" to "LATE", "Về sớm" to "EARLY_LEAVE")
    val types = listOf("Tất cả loại" to null, "Vào ca" to "CHECK_IN", "Ra ca" to "CHECK_OUT")
    LaunchedEffect(classificationRequest?.id, classificationRetry) {
        val requested = classificationRequest ?: return@LaunchedEffect
        classificationLoading = true; classificationError = null
        try {
            val history = if (inPreview) AttendanceRecordHistory(
                (state.attendance + state.historicalAttendance).firstOrNull { it.id == requested.id } ?: requested,
                attendanceAdjustmentDate(requested).orEmpty(),
                classifications = state.calculationClassificationOverrides.filter { it.attendanceId == requested.id }
            ) else vm.loadAttendanceRecordHistory(requested)
            previousClassification = latestAttendanceClassificationOverride(history.classifications, history.source.id)
            scanReview = requested.copy(scheduleDate = history.scheduleDate) to history.source
            classificationRequest = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { classificationError = userFacingErrorMessage(failure) }
        finally { classificationLoading = false }
    }
    val filters: @Composable ColumnScope.() -> Unit = {
        AdminPageHeader("Chấm công", "Xem lượt quét, phân loại và điều chỉnh công.")
        if (vm.hasAdminAccess()) Button(onClick = { vm.clearError(); manualAdjustment = true }, enabled = !state.saving, shape = MaterialTheme.shapes.small) {
            Text("Điều chỉnh công")
        }
        AdminSectionCard {
            Text("Bộ lọc chấm công", style = MaterialTheme.typography.titleMedium)
            AttendanceDateRangeControls(state, vm)
            if (state.attendanceDatePreset == "SINGLE" || state.attendanceDateFilter.isNotBlank()) {
            OutlinedTextField(
                value = state.attendanceDateFilter,
                onValueChange = vm::setAttendanceDateFilter,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ng\u00e0y l\u1ecdc (yyyy-MM-dd)") },
                singleLine = true,
                isError = state.attendanceDateFilter.isNotBlank() && runCatching { LocalDate.parse(state.attendanceDateFilter.trim()) }.isFailure,
                supportingText = {
                    if (state.attendanceDateFilter.isNotBlank() && runCatching { LocalDate.parse(state.attendanceDateFilter.trim()) }.isFailure) {
                        Text("Nh\u1eadp ng\u00e0y theo d\u1ea1ng yyyy-MM-dd")
                    }
                }
            )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                androidx.compose.foundation.layout.Box {
                    FilterChip(
                        selected = state.attendanceDepartmentFilter != null,
                        onClick = { departmentMenu = true },
                        label = { Text(state.attendanceDepartmentFilter ?: "T\u1ea5t c\u1ea3 ph\u00f2ng ban") }
                    )
                    DropdownMenu(expanded = departmentMenu, onDismissRequest = { departmentMenu = false }) {
                        DropdownMenuItem(text = { Text("T\u1ea5t c\u1ea3 ph\u00f2ng ban") }, onClick = {
                            vm.setAttendanceDepartmentFilter(null)
                            departmentMenu = false
                        })
                        state.attendanceFilterEmployees.map { it.department.trim() }.filter(String::isNotBlank).distinct().sorted().forEach { department ->
                            DropdownMenuItem(text = { Text(department) }, onClick = {
                                vm.setAttendanceDepartmentFilter(department)
                                departmentMenu = false
                            })
                        }
                    }
                }
                androidx.compose.foundation.layout.Box {
                    val employeeName = state.attendanceFilterEmployees.firstOrNull { it.id == state.attendanceEmployeeFilter }?.fullName
                    FilterChip(
                        selected = employeeName != null,
                        onClick = { employeeMenu = true },
                        label = { Text(employeeName ?: "T\u1ea5t c\u1ea3 nh\u00e2n vi\u00ean") }
                    )
                    DropdownMenu(expanded = employeeMenu, onDismissRequest = { employeeMenu = false }) {
                        DropdownMenuItem(text = { Text("T\u1ea5t c\u1ea3 nh\u00e2n vi\u00ean") }, onClick = {
                            vm.setAttendanceEmployeeFilter(null)
                            employeeMenu = false
                        })
                        state.attendanceFilterEmployees.sortedBy { it.fullName }.forEach { employee ->
                            DropdownMenuItem(text = { Text(employee.fullName.ifBlank { employee.code }) }, onClick = {
                                vm.setAttendanceEmployeeFilter(employee.id)
                                employeeMenu = false
                            })
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                statuses.forEach { (label, value) ->
                    FilterChip(
                        selected = state.attendanceStatusFilter == value,
                        onClick = { vm.setAttendanceFilters(value, state.attendanceTypeFilter) },
                        label = { Text(label) }
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                types.forEach { (label, value) ->
                    FilterChip(
                        selected = state.attendanceTypeFilter == value,
                        onClick = { vm.setAttendanceFilters(state.attendanceStatusFilter, value) },
                        label = { Text(label) }
                    )
                }
            }
        }
        if (range != null) CalculationLoadingNotice(state, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            AdminMetricCard("Lượt hợp lệ", if (ready) visibleRows.count(::isAcceptedAttendance).toString() else "—", "Theo bộ lọc đang chọn", Modifier.weight(1f))
            AdminMetricCard("Đi trễ", if (ready) visibleRows.count { it.status == "LATE" }.toString() else "—", "Lượt vào ca đi trễ", Modifier.weight(1f))
        }
        AdminMetricCard("Cần kiểm tra", if (ready) visibleRows.count(::isAbnormalAttendance).toString() else "—", "Chờ xử lý hoặc bất thường")
        val rejectedReviewTargets = state.calculationOffScheduleReviews
            .filter { it.decision == "REJECT" && it.status in setOf("PENDING", "REJECTED") }
            .map { "${it.employeeId}|${it.scheduleDate}|${it.shiftId}" }
            .toSet()
        val unresolvedReviews = state.calculationOffScheduleReviews.filter { review ->
            val targetKey = "${review.employeeId}|${review.scheduleDate}|${review.shiftId}"
            val alreadyRejected = targetKey in rejectedReviewTargets
            !alreadyRejected && (review.status == "FAILED" ||
                (review.status == "PENDING" && review.decision == "APPROVE"))
        }
        if (unresolvedReviews.isNotEmpty()) {
            AdminSectionCard {
                Text("Xử lý chấm ngoài lịch", style = MaterialTheme.typography.titleSmall)
                unresolvedReviews.take(5).forEach { review ->
                    val stateLabel = if (review.status == "FAILED") "Thất bại" else "Đang xử lý"
                    Text(
                        "$stateLabel • ${review.employeeName} • ${review.scheduleDate} • ${review.shiftName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    review.failureReason?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Lượt chấm công", style = MaterialTheme.typography.titleMedium)
        Text("${visibleRows.size} lượt theo bộ lọc đang chọn", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AttendanceList(visibleRows, Modifier.fillMaxSize(),
        adjustmentEnabled = !state.saving,
        scanReviewIds = (state.attendance + state.historicalAttendance)
            .filter(::canCorrectAttendanceClassification)
            .map { it.id }.toSet(),
        onAdjust = if (vm.hasAdminAccess()) { row ->
            vm.clearError()
            target = attendanceAdjustmentTarget(row, state)
        } else null,
        onReviewScan = if (vm.hasAdminAccess()) { row ->
            vm.clearError()
            classificationRequest = row
        } else null,
        onDetail = if (vm.hasAdminAccess()) { row -> vm.clearError(); detailTarget = row } else null,
        onReviewUnscheduled = if (vm.hasAdminAccess()) { row ->
            vm.clearError()
            offScheduleTarget = row
        } else null,
        header = filters)
    target?.let { selected ->
        AttendanceAdjustmentDialog(selected, state,
            onDismiss = { if (!state.saving) target = null },
            onSubmit = { adjustment ->
                if (vm.hasAdminAccess() && !state.saving) vm.adjustAttendance(adjustment) { target = null }
            })
    }
    scanReview?.let { (row, source) ->
        AttendanceScanReviewDialog(row, source, state.saving, state.error,
            onDismiss = { if (!state.saving) scanReview = null },
            onSubmit = { correction -> if (vm.hasAdminAccess() && !state.saving) vm.correctAttendanceClassification(correction) { scanReview = null } },
            previousOverride = previousClassification)
    }
    classificationRequest?.let {
        AlertDialog(onDismissRequest = { if (!state.saving) classificationRequest = null },
            title = { Text("Tải phân loại hiện tại") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                if (classificationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(classificationError ?: "Đang tải lượt chấm gốc và lần sửa gần nhất…", color = if (classificationError == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            } },
            confirmButton = { if (classificationError != null) TextButton(onClick = { classificationRetry++ }) { Text("Thử lại") } },
            dismissButton = { TextButton(onClick = { classificationRequest = null }, enabled = !state.saving) { Text("Hủy") } })
    }
    detailTarget?.let { selected ->
        val loadRecordHistory: suspend () -> AttendanceRecordHistory = { vm.loadAttendanceRecordHistory(selected) }
        AttendanceRecordHistoryDialog(selected, state.saving,
            loadHistory = loadRecordHistory,
            onDismiss = { if (!state.saving) detailTarget = null },
            onAdjust = { row: Attendance, history: AttendanceRecordHistory ->
                vm.clearError(); detailTarget = null
                target = attendanceAdjustmentTarget(row, state.copy(
                    attendanceAdjustments = state.attendanceAdjustments + history.adjustments,
                    attendanceClassificationOverrides = state.attendanceClassificationOverrides + history.classifications
                ))
            },
            onClassify = { row, history ->
                vm.clearError(); detailTarget = null
                previousClassification = latestAttendanceClassificationOverride(history.classifications, history.source.id)
                scanReview = row to history.source
            },
            onReviewUnscheduled = { row -> vm.clearError(); detailTarget = null; offScheduleTarget = row })
    }
    offScheduleTarget?.let { selected ->
        OffScheduleReviewDialog(
            row = selected,
            attendance = state.historicalAttendanceForSummaries,
            shifts = state.calculationShifts,
            busy = state.saving,
            error = state.error,
            onDismiss = { if (!state.saving) offScheduleTarget = null },
            onSubmit = { shiftId, decision, reason ->
                if (vm.hasAdminAccess() && !state.saving) vm.reviewOffScheduleAttendance(
                    employeeId = selected.employeeId,
                    employeeName = selected.employeeName,
                    scheduleDate = attendanceAdjustmentDate(selected) ?: return@OffScheduleReviewDialog,
                    shiftId = shiftId,
                    decision = decision,
                    reason = reason
                ) { offScheduleTarget = null }
            }
        )
    }
    if (manualAdjustment && vm.hasAdminAccess()) {
        ManualAttendanceAdjustmentDialog(state, vm, inPreview) { manualAdjustment = false }
    }
}

@Composable
private fun ManualAttendanceAdjustmentDialog(state: MainUiState, vm: MainViewModel, inPreview: Boolean, onDismiss: () -> Unit) {
    var day by rememberSaveable { mutableStateOf(LocalDate.now(attendanceZone).toString()) }
    var employeeId by rememberSaveable { mutableStateOf("") }
    var employeeMenu by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(day.trim()) }.getOrNull()
    val employees = state.historicalEmployees(YearMonth.from(date ?: LocalDate.now(attendanceZone))).sortedBy { it.fullName }
    val employee = employees.firstOrNull { it.id == employeeId }
    LaunchedEffect(day, employees.map { it.id }) {
        if (employeeId.isNotBlank() && employees.none { it.id == employeeId }) employeeId = ""
    }
    LaunchedEffect(date, employee?.id) {
        if (!inPreview && date != null && employee != null && vm.hasAdminAccess()) {
            vm.loadAttendanceRange(date, date, employee.id, force = true)
        }
    }
    val dataReady = date != null && employee != null &&
        state.hasCompleteCalculationRange(date, date, employee.id) && vm.hasAdminAccess()
    val target = if (dataReady) manualAdjustmentTarget(employee!!.id, employee.fullName, date!!, state)
        else AttendanceAdjustmentTarget(employeeId, employee?.fullName.orEmpty(), day, null, null, null)
    AttendanceAdjustmentDialog(
        target = target,
        state = state,
        onDismiss = onDismiss,
        onSubmit = { adjustment ->
            if (dataReady && !state.saving && vm.hasAdminAccess()) vm.adjustAttendance(adjustment, onDismiss)
        },
        dataReady = dataReady,
        keepDraftOnTargetChange = true,
        selectorContent = {
            OutlinedTextField(day, { day = it; vm.clearError() }, Modifier.fillMaxWidth(), label = { Text("Ngày ca (yyyy-MM-dd)") }, singleLine = true, enabled = !state.saving, isError = date == null)
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { employeeMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving && date != null, shape = MaterialTheme.shapes.small) {
                    Text(employee?.let { "${it.code} · ${it.fullName}${if (!it.active) " · Đã nghỉ" else ""}" } ?: "Chọn nhân viên")
                }
                DropdownMenu(expanded = employeeMenu, onDismissRequest = { employeeMenu = false }) {
                    employees.forEach { candidate ->
                        DropdownMenuItem(text = { Text("${candidate.code} · ${candidate.fullName}${if (!candidate.active) " · Đã nghỉ" else ""}") },
                            onClick = { employeeId = candidate.id; employeeMenu = false; vm.clearError() })
                    }
                    if (employees.isEmpty()) DropdownMenuItem(text = { Text("Chưa có nhân viên trong kỳ này") }, onClick = { employeeMenu = false }, enabled = false)
                }
            }
            Text("Chỉ hiện nhân viên đang làm hoặc còn thuộc tháng đã chọn. Đổi ngày sẽ giữ các nội dung bạn đang nhập.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (employee != null && date != null && !dataReady) CalculationLoadingNotice(state, vm)
            if (employee == null) Text("Chọn nhân viên để tải công hiện tại trước khi lưu.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    )
}

@Composable
private fun AttendanceDateRangeControls(state: MainUiState, vm: MainViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        listOf(
            "Tất cả" to null,
            "Hôm nay" to "TODAY",
            "Hôm qua" to "YESTERDAY",
            "Tuần này" to "THIS_WEEK",
            "Tháng này" to "THIS_MONTH",
            "Ngày cụ thể" to "SINGLE",
            "Khoảng tùy chọn" to "CUSTOM"
        ).forEach { (label, preset) ->
            FilterChip(
                selected = state.attendanceDatePreset == preset,
                onClick = { vm.setAttendanceDatePreset(preset) },
                label = { Text(label) }
            )
        }
    }
    if (state.attendanceDatePreset == "CUSTOM") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            OutlinedTextField(
                value = state.attendanceRangeStart,
                onValueChange = vm::setAttendanceRangeStart,
                modifier = Modifier.weight(1f),
                label = { Text("Từ ngày (yyyy-MM-dd)") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.attendanceRangeEnd,
                onValueChange = vm::setAttendanceRangeEnd,
                modifier = Modifier.weight(1f),
                label = { Text("Đến ngày (yyyy-MM-dd)") },
                singleLine = true
            )
        }
        if (parseAttendanceDateRange(state.attendanceRangeStart, state.attendanceRangeEnd) == null) {
            Text(
                "Khoảng ngày không hợp lệ hoặc chưa đủ hai đầu mút",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

internal fun attendanceAdjustmentTarget(row: Attendance, state: MainUiState): AttendanceAdjustmentTarget? {
    val date = attendanceAdjustmentDate(row)?.let(LocalDate::parse) ?: return null
    if (row.employeeId.isBlank()) return null
    val schedule = state.effectiveSchedules.firstOrNull { it.employeeId == row.employeeId && it.date == date.toString() }
    val selectedShifts = schedule?.let { scheduledShifts(it, state.calculationShifts.associateBy { shift -> shift.id }) }.orEmpty()
    val shift = if (row.shiftId != null) selectedShifts.firstOrNull { it.id == row.shiftId } else selectedShifts.singleOrNull()
    val assigned = assignAttendanceScheduleDates(state.historicalAttendanceForSummaries, state.effectiveSchedules, state.calculationShifts, attendanceZone)
    val approvedLeaveIds = approvedLeaveShiftIdsForDate(row.employeeId, date, schedule, state.calculationShifts, state.calculationLeaveRequests)
    val day = employeeDaySummaryForSchedule(
        employeeId = row.employeeId, date = date, attendance = assigned, schedule = schedule,
        shifts = state.calculationShifts, approvedLeave = false, zoneId = attendanceZone,
        adjustments = state.calculationAdjustments, approvedLeaveShiftIds = approvedLeaveIds
    )
    val previous = latestAdjustment(state.calculationAdjustments, row.employeeId, date)
    val adjustedShiftId = (previous?.checkInAt ?: previous?.checkOutAt)?.let { instant ->
        nearestScheduledShift(date, selectedShifts, instant, if (previous?.checkInAt != null) "CHECK_IN" else "CHECK_OUT", attendanceZone)?.id
    }
    val shiftAdjustments = if (selectedShifts.size > 1 && shift?.id != adjustedShiftId) emptyList() else state.calculationAdjustments
    val summary = if (shift != null || selectedShifts.isEmpty()) employeeDaySummary(
        employeeId = row.employeeId, date = date,
        attendance = assigned,
        schedule = schedule, shift = shift, approvedLeave = false, zoneId = attendanceZone,
        adjustments = shiftAdjustments, approvedLeaveShiftIds = approvedLeaveIds
    ) else null
    return AttendanceAdjustmentTarget(
        employeeId = row.employeeId,
        employeeName = row.employeeName.ifBlank {
            state.employees.firstOrNull { it.id == row.employeeId }?.fullName?.takeIf(String::isNotBlank) ?: row.employeeId
        },
        scheduleDate = date.toString(),
        currentCheckIn = summary?.checkIn?.let { Timestamp(Date.from(it)) },
        currentCheckOut = summary?.checkOut?.let { Timestamp(Date.from(it)) },
        currentWorkedHours = day.workedHours,
        currentWorkedHoursOverride = previous?.workedHoursOverride
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AttendanceScreenPreview() {
    PreviewStateScreen { state, vm -> AttendanceScreen(state, vm) }
}
