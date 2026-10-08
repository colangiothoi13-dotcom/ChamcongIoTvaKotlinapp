// Chức năng: Điều phối thao tác từ giao diện, đăng nhập và các nhóm nghiệp vụ của ViewModel.
package vn.chamcong.iot.ui

import android.app.Application
import com.google.firebase.firestore.DocumentSnapshot
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.canAccessAdmin
import vn.chamcong.iot.domain.canAccessEmployee
import vn.chamcong.iot.domain.employeeRequestDraft
import vn.chamcong.iot.domain.createOvertimeRequest
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.DeviceCommandType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeResource
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.work.AttendanceSyncWorker
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MainViewModel private constructor(application: Application, private val previewMode: Boolean) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, false)

    internal val repository by lazy(LazyThreadSafetyMode.NONE) { FirebaseRepository(application) }
    internal val _state = MutableStateFlow(MainUiState(signedIn = if (previewMode) false else repository.isSignedIn))
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    internal val zoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    fun signIn(email: String, password: String) = viewModelScope.launch {
        if (_state.value.loading || _state.value.saving) return@launch
        _state.update { it.copy(loading = true, error = null, message = null) }
        try {
            repository.signIn(email, password)
            _state.update { it.copy(signedIn = true, loading = false) }
            subscribe()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = userFacingErrorMessage(error)) }
        }
    }

    internal val dataSubscriptions = mutableListOf<Job>()
    internal var profileSubscription: Job? = null
    internal var scheduleSubscription: Job? = null
    internal var weeklyScheduleRequestsSubscription: Job? = null
    internal var employeeWeeklyScheduleRequestSubscription: Job? = null
    internal var employeeResourcesSubscription: Job? = null
    internal var subscriptionMode: String? = null
    internal var attendanceHistoryJob: Job? = null
    internal var attendanceHistoryRequestedKey: String? = null
    internal var lastAttendanceHistoryRequest: AttendanceHistoryRequest? = null
    internal var employeeAttendanceHistoryJob: Job? = null
    internal var employeeAttendanceHistoryCursor: DocumentSnapshot? = null
    internal var employeeAttendanceHistoryHasMore = false
    internal var employeeAttendanceHistoryEmployeeId: String? = null

    init { if (!previewMode && repository.isSignedIn) subscribe() }

    companion object {
        internal fun forPreview(state: MainUiState): MainViewModel = MainViewModel(Application(), true).apply {
            _state.value = state
        }
    }

    private fun perform(
        onSuccess: () -> Unit,
        errorContext: String? = null,
        block: suspend () -> String
    ) = viewModelScope.launch {
        if (_state.value.saving) return@launch
        _state.update { it.copy(saving=true, error=null, message=null) }
        try {
            val message = block()
            _state.update { it.copy(saving=false, message=message) }
            onSuccess()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val error = userFacingErrorMessage(e)
            _state.update {
                it.copy(saving=false, error=errorContext?.let { context -> "$context: $error" } ?: error)
            }
        }
    }
    fun adjustAttendance(adjustment: AttendanceAdjustment, done: () -> Unit) = perform(done) {
        repository.saveAttendanceAdjustment(adjustment)
        retryAttendanceRange()
        "Đã lưu điều chỉnh chấm công"
    }
    fun correctAttendanceClassification(
        correction: AttendanceClassificationOverride,
        done: () -> Unit
    ) = perform(done) {
        repository.saveAttendanceClassificationOverride(correction)
        retryAttendanceRange()
        "Đã lưu phân loại lượt chấm; dữ liệu gốc được giữ nguyên"
    }
    fun reviewOffScheduleAttendance(
        employeeId: String,
        employeeName: String,
        scheduleDate: String,
        shiftId: String,
        decision: String,
        reason: String,
        done: () -> Unit = {}
    ) = perform(done) {
        val shift = _state.value.shifts.firstOrNull { it.id == shiftId }
            ?: error("Không tìm thấy ca được chọn")
        repository.submitOffScheduleAttendanceReview(
            employeeId, employeeName, scheduleDate, shift, decision, reason
        )
        retryAttendanceRange()
        if (decision == "APPROVE") "Đã duyệt lượt chấm ngoài lịch"
        else "Đã gửi từ chối lượt chấm ngoài lịch"
    }
    fun saveEmployee(employee: Employee, account: EmployeeAccountInput? = null, done: () -> Unit) = perform(done) {
        val code = repository.saveEmployee(employee, account)
        if (account == null) "Đã lưu nhân viên $code" else "Đã lưu nhân viên $code và tạo tài khoản đăng nhập"
    }
    fun requestFingerprint(employee: Employee, deviceId: String, account: EmployeeAccountInput? = null, done: () -> Unit) = perform(done) {
        val code = repository.saveAndRequestFingerprint(employee, deviceId, account)
        if (account == null) "Đã gửi đăng ký cho $code. Đặt ngón tay tại thiết bị."
        else "Đã lưu nhân viên $code, tạo tài khoản và gửi đăng ký vân tay. Đặt ngón tay tại thiết bị."
    }
    fun remove(employeeId: String, retire: Boolean, done: () -> Unit) = perform(done) {
        repository.removeEmployeeOrFingerprint(employeeId, retire)
        if (retire) "Đã chuyển nhân viên sang đã nghỉ và ghi nhận ngày nghỉ việc. Lịch sử lương được giữ lại; xem trạng thái xóa vân tay bên dưới."
        else "Đã gửi yêu cầu xóa vân tay. Xem trạng thái thiết bị bên dưới."
    }
    fun setSalary(employeeId: String, salary: Long, done: () -> Unit) = perform(done) {
        repository.setSalary(employeeId, salary)
        "Đã lưu mức lương"
    }
    fun savePayroll(employeeId: String, month: String, hoursWorked: Double, bonus: Long, deduction: Long, done: () -> Unit) = perform(done) {
        val period = YearMonth.parse(month)
        require(_state.value.hasCompleteCalculationRange(period.atDay(1), period.atEndOfMonth())) {
            "Cần tải đầy đủ dữ liệu tháng trước khi lưu phiếu lương"
        }
        repository.savePayroll(employeeId, month, hoursWorked, bonus, deduction)
        "Đã lưu phiếu lương tháng $month"
    }
    fun saveShift(shift: WorkShift, done: () -> Unit) = perform(done) {
        repository.saveShift(shift)
        "Đã lưu ca ${shift.name}"
    }
    fun assignOvertimeToEmployee(employeeId: String, workDate: String, reason: String, done: () -> Unit) = perform(done) {
        repository.assignOvertimeToEmployee(employeeId, workDate, reason)
        "Đã phân ca tăng ca 18:00–22:00 cho nhân viên"
    }
    fun assignShift(schedule: WorkSchedule, done: () -> Unit) = perform(done) {
        repository.saveSchedule(schedule)
        "Đã phân ca ${schedule.shiftName} cho ${schedule.employeeName}"
    }
    fun assignWeeklyShift(employeeIds: Set<String>, week: LocalDate, dates: Set<LocalDate>,
        template: vn.chamcong.iot.domain.ShiftTemplate, start: String, end: String, done: () -> Unit) = perform(done) {
        val shift = template.resolve(start, end)
        val schedules = vn.chamcong.iot.domain.weeklyAssignmentPayload(
            _state.value.employees, employeeIds, week, dates, shift, repository.currentUserId)
        repository.saveWeeklySchedules(shift, schedules)
        "Đã lưu ${schedules.size} lịch phân ca"
    }
    fun assignShiftToDepartment(department: String, dates: List<String>, shift: WorkShift, overtimeHours: Int, done: () -> Unit) =
        assignShiftToDepartment(department, dates, listOf(shift), overtimeHours, done)

    fun assignShiftToDepartment(department: String, dates: List<String>, shifts: List<WorkShift>, overtimeHours: Int, done: () -> Unit) = perform(done) {
        repository.assignShiftToDepartment(department, dates, shifts, overtimeHours, repository.currentUserId)
        "Đã phân ca ${shifts.joinToString(" + ") { it.name }} cho phòng ban $department"
    }
    fun copyPreviousWeek(done: () -> Unit) = perform(done) {
        val target = _state.value.selectedWeekStart
        val created = repository.copyPreviousWeek(target.minusWeeks(1).toString(), target.toString(), repository.currentUserId)
        "Đã sao chép $created lịch từ tuần trước"
    }
    fun requestDeviceCommand(deviceId: String, type: DeviceCommandType, done: () -> Unit = {}) = perform(done) {
        repository.requestDeviceCommand(deviceId, type)
        "Đã gửi lệnh ${vn.chamcong.iot.model.deviceCommandLabel(type)}"
    }
    fun submitWeeklySchedule(shiftsByDate: Map<String, List<String>>, note: String = "", done: () -> Unit = {}) =
        perform(done, errorContext = "Không gửi được đăng ký ca") {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        require(_state.value.employeeWeeklyScheduleRequestReady) { "Cần tải đăng ký hiện tại trước khi gửi" }
        val weekStart = mondayOfWeek(LocalDate.now(zoneId)).plusWeeks(1)
        require(weekStart == _state.value.employeeWeeklyTargetWeekStart) { "Tuần đăng ký đã thay đổi. Vui lòng tải lại đăng ký ca" }
        val currentShifts = _state.value.shifts.associateBy(WorkShift::id)
        val orderedShiftsByDate = shiftsByDate.mapValues { (_, ids) ->
            ids.distinct().sortedBy { currentShifts[it]?.startTime ?: it }
        }
        repository.submitWeeklyScheduleRequest(
            WeeklyScheduleRequest(
                employeeId = employee.id,
                employeeName = employee.fullName,
                department = employee.department,
                weekStart = weekStart.toString(),
                shiftsByDate = orderedShiftsByDate,
                reason = note.trim()
            )
        )
        "Đã gửi đăng ký tuần bắt đầu $weekStart, đang chờ Admin duyệt"
    }
    fun reviewWeeklyScheduleRequest(
        requestId: String,
        status: WeeklyScheduleRequestStatus,
        reviewNote: String,
        done: () -> Unit = {}
    ) = perform(done) {
        repository.reviewWeeklyScheduleRequest(requestId, status, reviewNote)
        if (status == WeeklyScheduleRequestStatus.APPROVED) "Đã duyệt đăng ký lịch tuần" else "Đã yêu cầu nhân viên chỉnh sửa lịch tuần"
    }
    fun approveWeeklySchedulesForWeek(weekStart: String, done: () -> Unit = {}) = perform(done) {
        val state = _state.value
        require(state.weeklyScheduleRequestsReady && state.selectedWeekStart.toString() == weekStart) {
            "Cần tải đăng ký của đúng tuần trước khi duyệt"
        }
        val activeEmployeeIds = state.operationalEmployees.filter { it.active }.mapTo(mutableSetOf()) { it.id }
        val pending = state.weeklyScheduleRequests.filter {
            it.weekStart == weekStart && it.status == WeeklyScheduleRequestStatus.PENDING &&
                it.employeeId in activeEmployeeIds
        }
        require(pending.isNotEmpty()) { "Không có đăng ký đang chờ duyệt trong tuần này" }
        var approvedCount = 0
        try {
            pending.forEach { request ->
                repository.reviewWeeklyScheduleRequest(
                    request.id,
                    WeeklyScheduleRequestStatus.APPROVED,
                    ""
                )
                approvedCount++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error("Đã duyệt $approvedCount/${pending.size} đăng ký. Các đơn còn lại chưa được xử lý. ${userFacingErrorMessage(error)}")
        }
        "Đã duyệt ${pending.size} đăng ký lịch tuần"
    }
    fun reviewRequest(requestId: String, status: RequestStatus, note: String, done: () -> Unit) = perform(done) {
        repository.reviewLeaveRequest(requestId, status, repository.currentUserId, repository.currentUserName, note)
        if (status == RequestStatus.APPROVED) "Đã duyệt đơn" else "Đã từ chối đơn"
    }
    fun submitOvertimeRequest(workDate: String, reason: String, done: () -> Unit) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        val date = runCatching { LocalDate.parse(workDate.trim()) }
            .getOrElse { error("Ngày tăng ca không hợp lệ") }
        repository.submitOvertimeRequest(createOvertimeRequest(employee, date, reason))
        "Đã gửi đơn tăng ca, đang chờ Admin duyệt"
    }
    fun reviewOvertimeRequest(
        requestId: String,
        status: OvertimeRequestStatus,
        reason: String,
        done: () -> Unit
    ) = perform(done) {
        repository.reviewOvertimeRequest(requestId, status, reason)
        if (status == OvertimeRequestStatus.APPROVED) "Đã duyệt đơn tăng ca" else "Đã từ chối đơn tăng ca"
    }
    fun sendPasswordReset(email: String, done: () -> Unit = {}) = perform(done) {
        repository.sendPasswordReset(email)
        "Đã gửi email đặt lại mật khẩu"
    }
    fun changePassword(newPassword: String, done: () -> Unit = {}) = perform(done) {
        if (repository.changePassword(newPassword)) "Đã đổi mật khẩu"
        else "Đã đổi mật khẩu; chưa ghi được nhật ký thao tác"
    }
    fun updateDeviceConfiguration(deviceId: String, name: String, location: String, done: () -> Unit = {}) = perform(done) {
        repository.updateDeviceConfiguration(deviceId, name, location)
        "Đã lưu cấu hình thiết bị"
    }
    fun submitEmployeeRequest(
        type: RequestType,
        startDate: String,
        endDate: String,
        reason: String,
        proposedCheckIn: String? = null,
        proposedCheckOut: String? = null,
        requestedShiftId: String? = null,
        requestedShiftName: String? = null,
        leaveShiftsByDate: Map<String, List<String>>? = null,
        done: () -> Unit = {}
    ) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        val shiftName = requestedShiftName ?: _state.value.shifts.firstOrNull { it.id == requestedShiftId }?.name
        val request = employeeRequestDraft(
            employee, type, startDate, endDate, reason,
            proposedCheckIn, proposedCheckOut, requestedShiftId, shiftName, leaveShiftsByDate
        )
        repository.submitEmployeeRequest(request)
        "Đã gửi đơn, đang chờ Admin duyệt"
    }
    fun cancelEmployeeLeaveRequest(requestId: String, done: () -> Unit = {}) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        repository.cancelEmployeeLeaveRequest(requestId, employee.id)
        "Đã hủy đơn nghỉ phép đang chờ duyệt"
    }
    fun markNotificationRead(notificationId: String) = viewModelScope.launch {
        runCatching { repository.markNotificationRead(notificationId) }.onFailure(::setError)
    }
    fun markEmployeeNotificationRead(notificationId: String) = viewModelScope.launch {
        runCatching { repository.markNotificationRead(notificationId) }.onFailure(::setError)
    }
    fun updateEmployeeContact(phone: String, address: String, done: () -> Unit = {}) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Ch\u01b0a t\u1ea3i \u0111\u01b0\u1ee3c h\u1ed3 s\u01a1 nh\u00e2n vi\u00ean")
        repository.updateEmployeeContact(employee.id, phone, address)
        "\u0110\u00e3 c\u1eadp nh\u1eadt th\u00f4ng tin li\u00ean h\u1ec7"
    }
    fun saveEmployeeResource(resource: EmployeeResource, done: () -> Unit = {}) = perform(done) {
        repository.saveEmployeeResource(resource)
        "Đã lưu nội dung tiện ích"
    }
    fun deleteEmployeeResource(resourceId: String, done: () -> Unit = {}) = perform(done) {
        repository.deleteEmployeeResource(resourceId)
        "Đã xóa nội dung tiện ích"
    }
    fun retryEmployeeResources() = subscribeEmployeeResources()
    fun submitFingerprintSupportRequest(reason: String, done: () -> Unit = {}) {
        val today = LocalDate.now(zoneId).toString()
        submitEmployeeRequest(RequestType.FINGERPRINT_SUPPORT, today, today, reason, done = done)
    }
    fun saveDepartment(departmentId: String?, name: String, done: () -> Unit = {}) = perform(done) {
        repository.saveDepartment(departmentId, name)
        "\u0110\u00e3 l\u01b0u ph\u00f2ng ban"
    }
    fun setDepartmentActive(departmentId: String, active: Boolean, done: () -> Unit = {}) = perform(done) {
        repository.setDepartmentActive(departmentId, active)
        if (active) "\u0110\u00e3 k\u00edch ho\u1ea1t ph\u00f2ng ban" else "\u0110\u00e3 ng\u1eebng ph\u00f2ng ban"
    }
    fun sendAnnouncement(targetDepartment: String?, title: String, body: String, done: () -> Unit = {}) = perform(done) {
        val count = repository.sendAnnouncement(targetDepartment, title, body)
        "\u0110\u00e3 g\u1eedi th\u00f4ng b\u00e1o cho $count nh\u00e2n vi\u00ean"
    }
    fun setEmployeeQuery(value: String) = _state.update { it.copy(employeeQuery = value) }
    fun setDepartmentFilter(value: String?) = _state.update { it.copy(departmentFilter = value?.takeIf(String::isNotBlank)) }
    fun setShowRetired(value: Boolean) = _state.update { it.copy(showRetired = value) }
    fun setAttendanceFilters(status: String?, type: String?) = _state.update {
        it.copy(
            attendanceStatusFilter = status?.takeIf(String::isNotBlank),
            attendanceTypeFilter = type?.takeIf(String::isNotBlank)
        )
    }
    fun setAttendanceDateFilter(value: String) = _state.update {
        it.copy(
            attendanceDateFilter = value,
            attendanceDatePreset = value.takeIf(String::isNotBlank)?.let { "SINGLE" }
        )
    }
    fun setAttendanceDatePreset(value: String?) = _state.update {
        it.copy(
            attendanceDatePreset = value,
            attendanceDateFilter = if (value == "SINGLE") it.attendanceDateFilter else ""
        )
    }
    fun setAttendanceRangeStart(value: String) = _state.update {
        it.copy(attendanceDatePreset = "CUSTOM", attendanceRangeStart = value, attendanceDateFilter = "")
    }
    fun setAttendanceRangeEnd(value: String) = _state.update {
        it.copy(attendanceDatePreset = "CUSTOM", attendanceRangeEnd = value, attendanceDateFilter = "")
    }
    fun setAttendanceEmployeeFilter(value: String?) = _state.update { it.copy(attendanceEmployeeFilter = value?.takeIf(String::isNotBlank)) }
    fun setAttendanceDepartmentFilter(value: String?) = _state.update { it.copy(attendanceDepartmentFilter = value?.takeIf(String::isNotBlank)) }
    fun moveAdminTimesheetMonth(delta: Long) = _state.update {
        it.copy(selectedAdminTimesheetMonth = it.selectedAdminTimesheetMonth.plusMonths(delta))
    }
    fun selectWeek(value: LocalDate) {
        val monday = mondayOfWeek(value)
        _state.update {
            if (it.selectedWeekStart == monday) it
            else it.copy(
                selectedWeekStart = monday,
                weeklyScheduleRequests = emptyList(),
                weeklyScheduleRequestsLoading = subscriptionMode == "ADMIN",
                weeklyScheduleRequestsError = null,
                weeklyScheduleRequestsLoadedWeekStart = null
            )
        }
    }
    fun selectWeeklyRegistrationWeek() = selectWeek(mondayOfWeek(LocalDate.now(zoneId)).plusWeeks(1))
    fun retryWeeklyScheduleRequests() {
        if (subscriptionMode == "ADMIN") subscribeAdminWeeklyScheduleRequests()
    }
    fun retryEmployeeWeeklyScheduleRequest() = subscribeEmployeeWeeklyScheduleRequest()
    fun retryUserProfile() {
        if (_state.value.signedIn) subscribe()
    }
    fun moveWeek(delta: Long) = selectWeek(_state.value.selectedWeekStart.plusWeeks(delta))
    fun selectPayrollMonth(month: YearMonth) = _state.update { it.copy(selectedPayrollMonth = month) }
    fun selectEmployeeScheduleMonth(month: YearMonth) = _state.update { it.copy(selectedEmployeeScheduleMonth = month) }
    fun selectPresenceDate(value: LocalDate) = _state.update { it.copy(selectedPresenceDate = value) }
    fun setRequestFilter(value: String?) = _state.update { it.copy(selectedRequestFilter = value?.takeIf(String::isNotBlank)) }
    fun signOut() {
        profileSubscription?.cancel()
        profileSubscription = null
        cancelDataSubscriptions()
        subscriptionMode = null
        repository.signOut()
        _state.value = MainUiState()
    }

    fun clearError() = _state.update { it.copy(error = null) }
    internal fun setError(error: Throwable) = _state.update { it.copy(error = userFacingErrorMessage(error)) }

    fun hasAdminAccess(): Boolean = canAccessAdmin("password", _state.value.userProfile)
    fun hasEmployeeAccess(): Boolean = canAccessEmployee("password", _state.value.userProfile)

    private val reports = MainReportQueries(_state, zoneId)

    fun employeeMonthSummaries(month: LocalDate = LocalDate.now()): List<EmployeeDaySummary> =
        reports.employeeMonthSummaries(month)

    fun employeeMonthSummaries(employeeId: String, month: YearMonth): List<EmployeeDaySummary> =
        reports.employeeMonthSummaries(employeeId, month)

    fun reportAttendanceRows(filter: ReportFilter): List<AttendanceReportRow> = reports.reportAttendanceRows(filter)
    fun kpiBonusBreakdowns(month: YearMonth): Map<String, KpiBonusBreakdown> = reports.kpiBonusBreakdowns(month)
    fun reportDeviceRows(): List<DeviceActivityRow> = reports.reportDeviceRows()
    fun reportCsv(type: ReportType, filter: ReportFilter): String = reports.reportCsv(type, filter)

    internal fun enqueueReceipt(eventId: String) {
        val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("eventId", eventId).build())
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "attendance-receipt-$eventId", ExistingWorkPolicy.KEEP, request
        )
    }
}
