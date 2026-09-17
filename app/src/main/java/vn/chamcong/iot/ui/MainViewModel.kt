package vn.chamcong.iot.ui

import android.app.Application
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
import vn.chamcong.iot.domain.filterAttendance
import vn.chamcong.iot.domain.filterEmployees
import vn.chamcong.iot.domain.classifyPresenceForEmployees
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.summarizeDashboard
import vn.chamcong.iot.domain.summarizeWeeklyWork
import vn.chamcong.iot.domain.canAccessAdmin
import vn.chamcong.iot.domain.canAccessEmployee
import vn.chamcong.iot.domain.employeeMonthSummaries as buildEmployeeMonthSummaries
import vn.chamcong.iot.domain.employeeRequestDraft
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.data.FirebaseRepository
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.PresenceRecord
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WeeklyWorkSummary
import vn.chamcong.iot.work.AttendanceSyncWorker
import java.time.LocalDate
import java.time.ZoneId

data class MainUiState(
    val signedIn: Boolean = false,
    val profileResolved: Boolean = false,
    val loading: Boolean = false,
    val employees: List<Employee> = emptyList(),
    val attendance: List<Attendance> = emptyList(),
    val attendanceAdjustments: List<AttendanceAdjustment> = emptyList(),
    val payroll: List<Payroll> = emptyList(),
    val commands: List<Map<String, Any>> = emptyList(),
    val devices: List<DeviceSnapshot> = emptyList(),
    val selectedWeekStart: LocalDate = mondayOfWeek(LocalDate.now()),
    val selectedPresenceDate: LocalDate = LocalDate.now(),
    val shifts: List<WorkShift> = emptyList(),
    val schedules: List<WorkSchedule> = emptyList(),
    val leaveRequests: List<LeaveRequest> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val auditLogs: List<AuditLog> = emptyList(),
    val userProfile: UserProfile? = null,
    val currentEmployee: Employee? = null,
    val employeeAttendance: List<Attendance> = emptyList(),
    val employeeSchedules: List<WorkSchedule> = emptyList(),
    val employeeRequests: List<LeaveRequest> = emptyList(),
    val selectedRequestFilter: String? = null,
    val employeeQuery: String = "",
    val departmentFilter: String? = null,
    val showRetired: Boolean = false,
    val attendanceStatusFilter: String? = null,
    val attendanceTypeFilter: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    // Derived on every state snapshot, including schedule, shift and adjustment emissions.
    val dashboard: DashboardSummary
        get() = summarizeDashboard(employees, attendance, selectedWeekStart,
            schedules = schedules, shifts = shifts, adjustments = attendanceAdjustments)

    val visibleEmployees: List<Employee>
        get() = filterEmployees(employees, employeeQuery, departmentFilter, showRetired)

    val visibleAttendance: List<Attendance>
        get() = filterAttendance(attendance, attendanceStatusFilter, attendanceTypeFilter,
            schedules, shifts, attendanceAdjustments)

    val visibleLeaveRequests: List<LeaveRequest>
        get() = leaveRequests.filter { selectedRequestFilter.isNullOrBlank() || it.status == selectedRequestFilter }

    val presenceRecords: List<PresenceRecord>
        get() = classifyPresenceForEmployees(
            employees = employees,
            attendance = attendance,
            requests = leaveRequests,
            date = selectedPresenceDate,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            adjustments = attendanceAdjustments,
            schedules = schedules,
            shifts = shifts
        )

    val weeklyWorkSummary: WeeklyWorkSummary
        get() = summarizeWeeklyWork(
            employees = employees,
            attendance = attendance,
            schedules = schedules,
            shifts = shifts.associateBy { it.id },
            approvedRequests = leaveRequests,
            weekStart = selectedWeekStart,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            adjustments = attendanceAdjustments
        )
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FirebaseRepository(application)
    private val _state = MutableStateFlow(MainUiState(signedIn = repository.isSignedIn))
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private val zoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    fun signIn(email: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.signIn(email, password) }
            .onSuccess {
                _state.update { it.copy(signedIn = true, loading = false) }
                subscribe()
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.localizedMessage) } }
    }

    private val dataSubscriptions = mutableListOf<Job>()
    private var profileSubscription: Job? = null
    private var scheduleSubscription: Job? = null
    private var subscriptionMode: String? = null

    init { if (repository.isSignedIn) subscribe() }

    private fun subscribe() {
        profileSubscription?.cancel()
        cancelDataSubscriptions()
        subscriptionMode = null
        _state.update { it.copy(profileResolved = false) }
        profileSubscription = viewModelScope.launch {
            repository.observeUserProfile().catch { e -> setError(e) }.collect { profile ->
                _state.update { it.copy(userProfile = profile, profileResolved = true) }
                if (profile?.role == "EMPLOYEE") {
                    if (canAccessEmployee("password", profile)) subscribeEmployee(profile)
                    else {
                        cancelDataSubscriptions()
                        subscriptionMode = "BLOCKED"
                    }
                } else subscribeAdmin()
            }
        }
    }

    private fun cancelDataSubscriptions() {
        dataSubscriptions.forEach { it.cancel() }
        dataSubscriptions.clear()
        scheduleSubscription?.cancel()
        scheduleSubscription = null
        _state.update { it.copy(attendanceAdjustments = emptyList()) }
    }

    private fun subscribeAdmin() {
        if (subscriptionMode == "ADMIN") return
        cancelDataSubscriptions()
        subscriptionMode = "ADMIN"
        dataSubscriptions += viewModelScope.launch {
            repository.observeAttendanceAdjustments().catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(attendanceAdjustments = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observePayroll().catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(payroll=rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployees().catch { e -> setError(e) }.collect { employees ->
                _state.update { it.copy(employees = employees) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeRecentAttendance().catch { e -> setError(e) }.collect { attendance ->
                _state.update { it.copy(attendance = attendance) }
                attendance.firstOrNull()?.id?.takeIf(String::isNotBlank)?.let(::enqueueReceipt)
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeDevices().catch { e -> setError(e) }.collect { devices ->
                _state.update { it.copy(devices = devices) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEnrollmentCommands().catch { e -> setError(e) }.collect { commands ->
                _state.update { it.copy(commands = commands) }
                commands.filter { it["status"] == "COMPLETED" && it["applied"] != true }.forEach {
                    runCatching { repository.applyCompletedEnrollment(it) }.onFailure(::setError)
                }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeShifts().catch { e -> setError(e) }.collect { shifts ->
                _state.update { it.copy(shifts = shifts) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeLeaveRequests().catch { e -> setError(e) }.collect { requests ->
                _state.update { it.copy(leaveRequests = requests) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeNotifications().catch { e -> setError(e) }.collect { notifications ->
                _state.update { it.copy(notifications = notifications) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeAuditLogs().catch { e -> setError(e) }.collect { logs ->
                _state.update { it.copy(auditLogs = logs) }
            }
        }
        subscribeSchedules()
    }

    private fun subscribeEmployee(profile: UserProfile) {
        val employeeId = profile.employeeId.orEmpty()
        val mode = "EMPLOYEE:$employeeId"
        if (subscriptionMode == mode) return
        cancelDataSubscriptions()
        subscriptionMode = mode
        _state.update {
            it.copy(
                currentEmployee = null,
                employeeAttendance = emptyList(),
                employeeSchedules = emptyList(),
                employeeRequests = emptyList()
            )
        }
        if (employeeId.isBlank()) return
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeAttendanceAdjustments(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(attendanceAdjustments = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployee(employeeId).catch { e -> setError(e) }.collect { employee ->
                _state.update { it.copy(currentEmployee = employee) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeAttendance(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeeAttendance = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeSchedules(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeeSchedules = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeShifts().catch { e -> setError(e) }.collect { shifts ->
                _state.update { it.copy(shifts = shifts) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeRequests(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeeRequests = rows) }
            }
        }
    }

    private fun subscribeSchedules() {
        if (subscriptionMode != "ADMIN") return
        scheduleSubscription?.cancel()
        scheduleSubscription = viewModelScope.launch {
            repository.observeSchedules()
                .catch { e -> setError(e) }
                .collect { schedules -> _state.update { it.copy(schedules = schedules) } }
        }
    }

    private fun perform(onSuccess: () -> Unit, block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.saving) return@launch
        _state.update { it.copy(saving=true, error=null, message=null) }
        try {
            val message = block()
            _state.update { it.copy(saving=false, message=message) }
            onSuccess()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(saving=false, error=e.localizedMessage) } }
    }
    fun adjustAttendance(adjustment: AttendanceAdjustment, done: () -> Unit) = perform(done) {
        repository.saveAttendanceAdjustment(adjustment)
        "Đã lưu điều chỉnh chấm công"
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
        if (retire) "Đã chuyển nhân viên sang đã nghỉ. Lịch sử lương được giữ lại; xem trạng thái xóa vân tay bên dưới."
        else "Đã gửi yêu cầu xóa vân tay. Xem trạng thái thiết bị bên dưới."
    }
    fun setSalary(employeeId: String, salary: Long, done: () -> Unit) = perform(done) {
        repository.setSalary(employeeId, salary)
        "Đã lưu mức lương"
    }
    fun savePayroll(employeeId: String, month: String, hoursWorked: Double, bonus: Long, deduction: Long, done: () -> Unit) = perform(done) {
        repository.savePayroll(employeeId, month, hoursWorked, bonus, deduction)
        "Đã lưu phiếu lương tháng $month"
    }
    fun saveShift(shift: WorkShift, done: () -> Unit) = perform(done) {
        repository.saveShift(shift)
        "Đã lưu ca ${shift.name}"
    }
    fun assignShift(schedule: WorkSchedule, done: () -> Unit) = perform(done) {
        repository.saveSchedule(schedule)
        "Đã phân ca ${schedule.shiftName} cho ${schedule.employeeName}"
    }
    fun assignShiftToDepartment(department: String, dates: List<String>, shift: WorkShift, overtimeHours: Int, done: () -> Unit) = perform(done) {
        repository.assignShiftToDepartment(department, dates, shift, overtimeHours, repository.currentUserId)
        "Đã phân ca ${shift.name} cho phòng ban $department"
    }
    fun copyPreviousWeek(done: () -> Unit) = perform(done) {
        val target = _state.value.selectedWeekStart
        val created = repository.copyPreviousWeek(target.minusWeeks(1).toString(), target.toString(), repository.currentUserId)
        "Đã sao chép $created lịch từ tuần trước"
    }
    fun reviewRequest(requestId: String, status: RequestStatus, note: String, done: () -> Unit) = perform(done) {
        repository.reviewLeaveRequest(requestId, status, repository.currentUserId, repository.currentUserName, note)
        if (status == RequestStatus.APPROVED) "Đã duyệt đơn" else "Đã từ chối đơn"
    }
    fun sendPasswordReset(email: String, done: () -> Unit = {}) = perform(done) {
        repository.sendPasswordReset(email)
        "Đã gửi email đặt lại mật khẩu"
    }
    fun changePassword(newPassword: String, done: () -> Unit = {}) = perform(done) {
        repository.changePassword(newPassword)
        "Đã đổi mật khẩu"
    }
    fun updateDeviceConfiguration(deviceId: String, name: String, location: String, done: () -> Unit = {}) = perform(done) {
        repository.updateDeviceConfiguration(deviceId, name, location)
        "Đã lưu cấu hình thiết bị"
    }
    fun submitEmployeeRequest(type: RequestType, startDate: String, endDate: String, reason: String, done: () -> Unit = {}) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        val request = employeeRequestDraft(employee, type, startDate, endDate, reason)
        repository.submitEmployeeRequest(request)
        "Đã gửi đơn, đang chờ Admin duyệt"
    }
    fun markNotificationRead(notificationId: String) = viewModelScope.launch {
        runCatching { repository.markNotificationRead(notificationId) }.onFailure(::setError)
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
    fun selectWeek(value: LocalDate) {
        val monday = mondayOfWeek(value)
        _state.update { it.copy(selectedWeekStart = monday) }
    }
    fun moveWeek(delta: Long) = selectWeek(_state.value.selectedWeekStart.plusWeeks(delta))
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
    private fun setError(error: Throwable) = _state.update { it.copy(error = error.localizedMessage) }

    fun hasAdminAccess(): Boolean = canAccessAdmin("password", _state.value.userProfile)
    fun hasEmployeeAccess(): Boolean = canAccessEmployee("password", _state.value.userProfile)

    fun employeeMonthSummaries(month: LocalDate = LocalDate.now()): List<EmployeeDaySummary> {
        val employee = _state.value.currentEmployee ?: return emptyList()
        val approvedLeaveDates = _state.value.employeeRequests
            .filter { it.status == RequestStatus.APPROVED.name && it.type == RequestType.LEAVE.name }
            .flatMap { request ->
                val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
                if (start == null || end == null || end.isBefore(start)) emptyList()
                else generateSequence(start) { current ->
                    current.plusDays(1).takeUnless { it.isAfter(end) }
                }.toList()
            }
            .toSet()
        return buildEmployeeMonthSummaries(
            employeeId = employee.id,
            month = month,
            attendance = _state.value.employeeAttendance,
            schedules = _state.value.employeeSchedules,
            shifts = _state.value.shifts,
            approvedLeaveDates = approvedLeaveDates,
            zoneId = zoneId,
            adjustments = _state.value.attendanceAdjustments
        )
    }

    fun reportAttendanceRows(filter: ReportFilter): List<AttendanceReportRow> = vn.chamcong.iot.domain.attendanceReportRows(
        filter = filter,
        employees = _state.value.employees,
        attendance = _state.value.attendance,
        schedules = _state.value.schedules,
        shifts = _state.value.shifts,
        approvedRequests = _state.value.leaveRequests,
        zoneId = zoneId,
        adjustments = _state.value.attendanceAdjustments
    )

    fun reportDeviceRows(): List<DeviceActivityRow> = vn.chamcong.iot.domain.deviceActivityRows(
        devices = _state.value.devices,
        failedCommands = _state.value.commands
    )

    fun reportCsv(type: ReportType, filter: ReportFilter): String = when (type) {
        ReportType.DEVICE_ACTIVITY -> vn.chamcong.iot.data.deviceRowsToCsv(reportDeviceRows())
        ReportType.ATTENDANCE, ReportType.WORK_SUMMARY, ReportType.LATE_EARLY, ReportType.LEAVE, ReportType.OVERTIME -> {
            val rows = reportAttendanceRows(filter).filter { row ->
                when (type) {
                    ReportType.LATE_EARLY -> row.status == "LATE" || row.status == "EARLY_LEAVE"
                    ReportType.LEAVE -> row.status == "LEAVE"
                    ReportType.OVERTIME -> row.overtimeHours > 0
                    else -> true
                }
            }
            vn.chamcong.iot.data.attendanceRowsToCsv(rows)
        }
    }

    private fun enqueueReceipt(eventId: String) {
        val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("eventId", eventId).build())
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "attendance-receipt-$eventId", ExistingWorkPolicy.KEEP, request
        )
    }
}
