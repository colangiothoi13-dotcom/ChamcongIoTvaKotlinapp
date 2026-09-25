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
import vn.chamcong.iot.domain.applyAttendanceClassificationOverrides
import vn.chamcong.iot.domain.applyOffScheduleReviewDecisions
import vn.chamcong.iot.domain.resolveSparkPendingAttendance
import vn.chamcong.iot.domain.classifyPresenceForEmployees
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.summarizeDashboard
import vn.chamcong.iot.domain.summarizeDailyDashboard
import vn.chamcong.iot.domain.AttendanceDateRange
import vn.chamcong.iot.domain.filterAttendanceByDateRange
import vn.chamcong.iot.domain.parseAttendanceDateRange
import vn.chamcong.iot.domain.summarizeWeeklyWork
import vn.chamcong.iot.domain.canAccessAdmin
import vn.chamcong.iot.domain.canAccessEmployee
import vn.chamcong.iot.domain.employeeMonthSummaries as buildEmployeeMonthSummaries
import vn.chamcong.iot.domain.employeeApprovedLeaveShifts
import vn.chamcong.iot.domain.employeeRequestDraft
import vn.chamcong.iot.domain.createOvertimeRequest
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.data.FirebaseRepository
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DailyDashboardSummary
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.DeviceCommandType
import vn.chamcong.iot.model.Department
import vn.chamcong.iot.model.Announcement
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
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.model.WeeklyWorkSummary
import vn.chamcong.iot.work.AttendanceSyncWorker
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MainUiState(
    val signedIn: Boolean = false,
    val profileResolved: Boolean = false,
    val loading: Boolean = false,
    val employees: List<Employee> = emptyList(),
    val attendance: List<Attendance> = emptyList(),
    val attendanceAdjustments: List<AttendanceAdjustment> = emptyList(),
    val attendanceClassificationOverrides: List<AttendanceClassificationOverride> = emptyList(),
    val offScheduleAttendanceReviews: List<OffScheduleAttendanceReview> = emptyList(),
    val payroll: List<Payroll> = emptyList(),
    val employeePayroll: List<Payroll> = emptyList(),
    val commands: List<Map<String, Any>> = emptyList(),
    val devices: List<DeviceSnapshot> = emptyList(),
    val departments: List<Department> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val selectedWeekStart: LocalDate = mondayOfWeek(LocalDate.now()),
    val selectedPresenceDate: LocalDate = LocalDate.now(),
    val shifts: List<WorkShift> = emptyList(),
    val schedules: List<WorkSchedule> = emptyList(),
    val weeklyScheduleRequests: List<WeeklyScheduleRequest> = emptyList(),
    val employeeWeeklyScheduleRequest: WeeklyScheduleRequest? = null,
    val leaveRequests: List<LeaveRequest> = emptyList(),
    val overtimeRequests: List<OvertimeRequest> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val auditLogs: List<AuditLog> = emptyList(),
    val userProfile: UserProfile? = null,
    val currentEmployee: Employee? = null,
    val employeeAttendance: List<Attendance> = emptyList(),
    val employeeSchedules: List<WorkSchedule> = emptyList(),
    val employeeRequests: List<LeaveRequest> = emptyList(),
    val employeeOvertimeRequests: List<OvertimeRequest> = emptyList(),
    val employeeNotifications: List<AppNotification> = emptyList(),
    val selectedRequestFilter: String? = null,
    val selectedAdminTimesheetMonth: YearMonth = YearMonth.now(),
    val employeeQuery: String = "",
    val departmentFilter: String? = null,
    val showRetired: Boolean = false,
    val attendanceStatusFilter: String? = null,
    val attendanceTypeFilter: String? = null,
    val attendanceDateFilter: String = "",
    val attendanceDatePreset: String? = null,
    val attendanceRangeStart: String = "",
    val attendanceRangeEnd: String = "",
    val attendanceEmployeeFilter: String? = null,
    val attendanceDepartmentFilter: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    /** Effective rows are derived locally in Spark mode; Firestore raw scans stay immutable. */
    val sparkResolvedAttendance: List<Attendance>
        get() = resolveSparkPendingAttendance(attendance, schedules, shifts, ZoneId.of("Asia/Ho_Chi_Minh"))
    val employeeSparkResolvedAttendance: List<Attendance>
        get() = resolveSparkPendingAttendance(employeeAttendance, employeeSchedules, shifts, ZoneId.of("Asia/Ho_Chi_Minh"))
    val employeeAttendanceForSummaries: List<Attendance>
        get() = applyAttendanceClassificationOverrides(employeeSparkResolvedAttendance, attendanceClassificationOverrides)
    val attendanceForSummaries: List<Attendance>
        get() = applyOffScheduleReviewDecisions(
            applyAttendanceClassificationOverrides(sparkResolvedAttendance, attendanceClassificationOverrides),
            offScheduleAttendanceReviews,
            ZoneId.of("Asia/Ho_Chi_Minh")
        )

    // Derived on every state snapshot, including schedule, shift and adjustment emissions.
    val dashboard: DashboardSummary
        get() = summarizeDashboard(employees, attendanceForSummaries, selectedWeekStart,
            schedules = schedules, shifts = shifts, adjustments = attendanceAdjustments)

    val dailyDashboard: DailyDashboardSummary
        get() = summarizeDailyDashboard(
            employees = employees,
            attendance = attendanceForSummaries,
            schedules = schedules,
            shifts = shifts,
            requests = leaveRequests,
            adjustments = attendanceAdjustments,
            date = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        )

    val visibleEmployees: List<Employee>
        get() = filterEmployees(employees, employeeQuery, departmentFilter, showRetired)

    val visibleAttendance: List<Attendance>
        get() {
            val byEmployee = employees.associateBy(Employee::id)
            val zone = ZoneId.of("Asia/Ho_Chi_Minh")
            val today = LocalDate.now(zone)
            val selectedRange: AttendanceDateRange? = when (attendanceDatePreset) {
                "TODAY" -> AttendanceDateRange(today, today)
                "YESTERDAY" -> AttendanceDateRange(today.minusDays(1), today.minusDays(1))
                "THIS_WEEK" -> AttendanceDateRange(mondayOfWeek(today), mondayOfWeek(today).plusDays(6))
                "THIS_MONTH" -> AttendanceDateRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
                "CUSTOM" -> parseAttendanceDateRange(attendanceRangeStart, attendanceRangeEnd)
                "SINGLE" -> parseAttendanceDateRange(attendanceDateFilter, attendanceDateFilter)
                else -> if (attendanceDateFilter.isBlank()) null else parseAttendanceDateRange(attendanceDateFilter, attendanceDateFilter)
            }
            if ((attendanceDatePreset != null || attendanceDateFilter.isNotBlank()) && selectedRange == null) return emptyList()
            // Spark mode keeps Firestore scans immutable, so the list screen
            // must use the locally resolved copies to show CHECK_IN/CHECK_OUT
            // instead of exposing the raw SCAN/PENDING device event.
            val resolvedAttendance = attendanceForSummaries
            val rangedAttendance = selectedRange?.let {
                filterAttendanceByDateRange(resolvedAttendance, it, schedules, shifts, zone)
            } ?: resolvedAttendance
            return filterAttendance(rangedAttendance, attendanceStatusFilter, attendanceTypeFilter,
                schedules, shifts, attendanceAdjustments)
                .filter { row -> attendanceEmployeeFilter.isNullOrBlank() || row.employeeId == attendanceEmployeeFilter }
                .filter { row -> attendanceDepartmentFilter.isNullOrBlank() || byEmployee[row.employeeId]?.department == attendanceDepartmentFilter }
        }

    val visibleLeaveRequests: List<LeaveRequest>
        get() = leaveRequests.filter { selectedRequestFilter.isNullOrBlank() || it.status == selectedRequestFilter }

    val presenceRecords: List<PresenceRecord>
        get() = classifyPresenceForEmployees(
            employees = employees,
            attendance = attendanceForSummaries,
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
            attendance = attendanceForSummaries,
            schedules = schedules,
            shifts = shifts.associateBy { it.id },
            approvedRequests = leaveRequests,
            weekStart = selectedWeekStart,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            adjustments = attendanceAdjustments,
            overtimeRequests = (overtimeRequests + employeeOvertimeRequests).distinctBy { it.id }
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
            .onFailure { e -> _state.update { it.copy(loading = false, error = userFacingErrorMessage(e)) } }
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
                if (profile != _state.value.userProfile) {
                    cancelDataSubscriptions()
                    subscriptionMode = null
                }
                _state.update { it.copy(userProfile = profile, profileResolved = true) }
                if (profile?.role == "EMPLOYEE") {
                    if (canAccessEmployee("password", profile)) subscribeEmployee(profile)
                    else {
                        cancelDataSubscriptions()
                        subscriptionMode = "BLOCKED"
                    }
                } else if (canAccessAdmin("password", profile)) subscribeAdmin()
                else {
                    cancelDataSubscriptions()
                    subscriptionMode = "BLOCKED"
                }
            }
        }
    }

    private fun cancelDataSubscriptions() {
        dataSubscriptions.forEach { it.cancel() }
        dataSubscriptions.clear()
        scheduleSubscription?.cancel()
        scheduleSubscription = null
        _state.update {
            it.copy(
                employees = emptyList(),
                attendance = emptyList(),
                attendanceAdjustments = emptyList(),
                attendanceClassificationOverrides = emptyList(),
                offScheduleAttendanceReviews = emptyList(),
                payroll = emptyList(),
                employeePayroll = emptyList(),
                commands = emptyList(),
                devices = emptyList(),
                departments = emptyList(),
                announcements = emptyList(),
                shifts = emptyList(),
                schedules = emptyList(),
                weeklyScheduleRequests = emptyList(),
                employeeWeeklyScheduleRequest = null,
                leaveRequests = emptyList(),
                overtimeRequests = emptyList(),
                notifications = emptyList(),
                auditLogs = emptyList(),
                currentEmployee = null,
                employeeAttendance = emptyList(),
                employeeSchedules = emptyList(),
                employeeRequests = emptyList(),
                employeeOvertimeRequests = emptyList(),
                employeeNotifications = emptyList()
            )
        }
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
            repository.observeAttendanceClassificationOverrides().catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(attendanceClassificationOverrides = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeOffScheduleAttendanceReviews().catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(offScheduleAttendanceReviews = rows) }
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
            repository.observeDepartments().catch { e -> setError(e) }.collect { departments ->
                _state.update { it.copy(departments = departments) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeAnnouncements().catch { e -> setError(e) }.collect { announcements ->
                _state.update { it.copy(announcements = announcements) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeAllAttendance().catch { e -> setError(e) }.collect { attendance ->
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
            repository.observeWeeklyScheduleRequests().catch { e -> setError(e) }.collect { requests ->
                _state.update { it.copy(weeklyScheduleRequests = requests) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeOvertimeRequests().catch { e -> setError(e) }.collect { requests ->
                _state.update { it.copy(overtimeRequests = requests) }
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
                employeeWeeklyScheduleRequest = null,
                employeePayroll = emptyList(),
                employeeRequests = emptyList(),
                employeeOvertimeRequests = emptyList(),
                employeeNotifications = emptyList()
            )
        }
        if (employeeId.isBlank()) return
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeAttendanceAdjustments(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(attendanceAdjustments = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeAttendanceClassificationOverrides(employeeId)
                .catch { e -> setError(e) }
                .collect { rows -> _state.update { it.copy(attendanceClassificationOverrides = rows) } }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployee(employeeId).catch { e -> setError(e) }.collect { employee ->
                _state.update { it.copy(currentEmployee = employee) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeePayroll(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeePayroll = rows) }
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
        val requestedWeekStart = mondayOfWeek(LocalDate.now(zoneId)).plusWeeks(1).toString()
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeWeeklyScheduleRequest(employeeId, requestedWeekStart)
                .catch { e -> setError(e) }
                .collect { request -> _state.update { it.copy(employeeWeeklyScheduleRequest = request) } }
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
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeOvertimeRequests(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeeOvertimeRequests = rows) }
            }
        }
        dataSubscriptions += viewModelScope.launch {
            repository.observeEmployeeNotifications(employeeId).catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(employeeNotifications = rows) }
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
        catch (e: Exception) { _state.update { it.copy(saving=false, error=userFacingErrorMessage(e)) } }
    }
    fun adjustAttendance(adjustment: AttendanceAdjustment, done: () -> Unit) = perform(done) {
        repository.saveAttendanceAdjustment(adjustment)
        "Đã lưu điều chỉnh chấm công"
    }
    fun correctAttendanceClassification(
        correction: AttendanceClassificationOverride,
        done: () -> Unit
    ) = perform(done) {
        repository.saveAttendanceClassificationOverride(correction)
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
        if (decision == "REJECT") {
            // The attendance document is immutable on Spark. Mark matching
            // rows locally so the admin sees the rejection immediately; the
            // review listener also restores this view after a refresh.
            _state.update { current ->
                current.copy(
                    attendance = current.attendance.map { row ->
                        val rowDate = row.scheduleDate ?: row.timestamp.toDate().toInstant()
                            .atZone(zoneId).toLocalDate().toString()
                        if (row.employeeId == employeeId && rowDate == scheduleDate &&
                            (row.type == "UNSCHEDULED" || row.resolutionStatus == "UNSCHEDULED")) {
                            row.copy(offScheduleReviewStatus = "REJECTED")
                        } else row
                    }
                )
            }
        }
        if (decision == "APPROVE") "Đã gửi duyệt lượt chấm ngoài lịch; chờ hệ thống cập nhật"
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
    fun assignWeeklyShift(employeeIds: Set<String>, week: LocalDate, dates: Set<LocalDate>,
        template: vn.chamcong.iot.domain.ShiftTemplate, start: String, end: String, done: () -> Unit) = perform(done) {
        val shift = template.resolve(start, end)
        val schedules = vn.chamcong.iot.domain.weeklyAssignmentPayload(
            _state.value.employees, employeeIds, week, dates, shift, repository.currentUserId)
        repository.saveWeeklySchedules(shift, schedules)
        "Đã lưu ${schedules.size} lịch phân ca"
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
    fun requestDeviceCommand(deviceId: String, type: DeviceCommandType, done: () -> Unit = {}) = perform(done) {
        repository.requestDeviceCommand(deviceId, type)
        "Đã gửi lệnh ${vn.chamcong.iot.model.deviceCommandLabel(type)}"
    }
    fun submitWeeklySchedule(shiftsByDate: Map<String, List<String>>, note: String = "", done: () -> Unit = {}) = perform(done) {
        val employee = _state.value.currentEmployee ?: error("Chưa tải được hồ sơ nhân viên")
        val weekStart = mondayOfWeek(LocalDate.now(zoneId)).plusWeeks(1)
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
        val pending = _state.value.weeklyScheduleRequests.filter {
            it.weekStart == weekStart && it.status == WeeklyScheduleRequestStatus.PENDING
        }
        require(pending.isNotEmpty()) { "Không có đăng ký đang chờ duyệt trong tuần này" }
        pending.forEach { request ->
            repository.reviewWeeklyScheduleRequest(
                request.id,
                WeeklyScheduleRequestStatus.APPROVED,
                ""
            )
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
        repository.changePassword(newPassword)
        "Đã đổi mật khẩu"
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
    private fun setError(error: Throwable) = _state.update { it.copy(error = userFacingErrorMessage(error)) }

    fun hasAdminAccess(): Boolean = canAccessAdmin("password", _state.value.userProfile)
    fun hasEmployeeAccess(): Boolean = canAccessEmployee("password", _state.value.userProfile)

    fun employeeMonthSummaries(month: LocalDate = LocalDate.now()): List<EmployeeDaySummary> {
        val employee = _state.value.currentEmployee ?: return emptyList()
        val approvedLeaveDates = _state.value.employeeRequests
            .filter { it.status == RequestStatus.APPROVED.name && it.type == RequestType.LEAVE.name && it.leaveShiftsByDate == null }
            .flatMap { request ->
                val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
                if (start == null || end == null || end.isBefore(start)) emptyList()
                else generateSequence(start) { current ->
                    current.plusDays(1).takeUnless { it.isAfter(end) }
                }.toList()
            }
            .toSet()
        val approvedLeaveShifts = employeeApprovedLeaveShifts(
            employee.id, _state.value.employeeSchedules, _state.value.shifts, _state.value.employeeRequests
        )
        return buildEmployeeMonthSummaries(
            employeeId = employee.id,
            month = month,
            attendance = _state.value.employeeAttendanceForSummaries,
            schedules = _state.value.employeeSchedules,
            shifts = _state.value.shifts,
            approvedLeaveDates = approvedLeaveDates,
            approvedLeaveShiftsByDate = approvedLeaveShifts,
            zoneId = zoneId,
            adjustments = _state.value.attendanceAdjustments,
            overtimeRequests = _state.value.employeeOvertimeRequests
        )
    }

    fun employeeMonthSummaries(employeeId: String, month: YearMonth): List<EmployeeDaySummary> {
        val current = _state.value
        val leaveDates = current.leaveRequests
            .asSequence()
            .filter { it.employeeId == employeeId && it.status == RequestStatus.APPROVED.name && it.type == RequestType.LEAVE.name && it.leaveShiftsByDate == null }
            .flatMap { request ->
                val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
                if (start == null || end == null || end.isBefore(start)) emptySequence()
                else generateSequence(start) { date -> date.plusDays(1).takeUnless { it.isAfter(end) } }
            }
            .filter { YearMonth.from(it) == month }
            .toSet()
        val approvedLeaveShifts = employeeApprovedLeaveShifts(
            employeeId, current.schedules, current.shifts, current.leaveRequests
        )
        return buildEmployeeMonthSummaries(
            employeeId = employeeId,
            month = month.atDay(1),
            attendance = current.attendanceForSummaries,
            schedules = current.schedules,
            shifts = current.shifts,
            approvedLeaveDates = leaveDates,
            approvedLeaveShiftsByDate = approvedLeaveShifts,
            zoneId = zoneId,
            adjustments = current.attendanceAdjustments,
            overtimeRequests = current.overtimeRequests
        )
    }

    fun reportAttendanceRows(filter: ReportFilter): List<AttendanceReportRow> = vn.chamcong.iot.domain.attendanceReportRows(
        filter = filter,
        employees = _state.value.employees,
        attendance = _state.value.attendanceForSummaries,
        schedules = _state.value.schedules,
        shifts = _state.value.shifts,
        approvedRequests = _state.value.leaveRequests,
        zoneId = zoneId,
        adjustments = _state.value.attendanceAdjustments
    )

    fun kpiBonusBreakdowns(month: YearMonth): Map<String, KpiBonusBreakdown> {
        val current = _state.value
        return calculateMonthlyKpiBonuses(
            employees = current.employees,
            month = month,
            attendance = current.attendanceForSummaries,
            schedules = current.schedules,
            shifts = current.shifts,
            overtimeRequests = current.overtimeRequests,
            adjustments = current.attendanceAdjustments,
            zoneId = zoneId
        )
    }

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
