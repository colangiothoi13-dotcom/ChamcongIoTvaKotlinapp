// Chức năng: Theo dõi hồ sơ, dữ liệu quản trị và dữ liệu nhân viên; hủy đăng ký khi đổi tài khoản.
package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.canAccessAdmin
import vn.chamcong.iot.domain.canAccessEmployee
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.UserProfile
import java.time.LocalDate

internal fun MainViewModel.subscribe() {
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

internal fun MainViewModel.cancelDataSubscriptions() {
    attendanceHistoryJob?.cancel()
    attendanceHistoryJob = null
    attendanceHistoryRequestedKey = null
    lastAttendanceHistoryRequest = null
    employeeAttendanceHistoryJob?.cancel()
    employeeAttendanceHistoryJob = null
    employeeAttendanceHistoryCursor = null
    employeeAttendanceHistoryHasMore = false
    employeeAttendanceHistoryEmployeeId = null
    dataSubscriptions.forEach { it.cancel() }
    dataSubscriptions.clear()
    scheduleSubscription?.cancel()
    scheduleSubscription = null
    _state.update {
        it.copy(
            employees = emptyList(),
            attendance = emptyList(),
            historicalAttendance = emptyList(),
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
            employeeAttendanceHistory = emptyList(),
            employeeAttendanceHistoryLoading = false,
            employeeAttendanceHistoryHasMore = false,
            employeeSchedules = emptyList(),
            employeeRequests = emptyList(),
            employeeOvertimeRequests = emptyList(),
            employeeNotifications = emptyList()
        )
    }
}

internal fun MainViewModel.subscribeAdmin() {
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

internal fun MainViewModel.subscribeEmployee(profile: UserProfile) {
    val employeeId = profile.employeeId.orEmpty()
    val mode = "EMPLOYEE:$employeeId"
    if (subscriptionMode == mode) return
    cancelDataSubscriptions()
    subscriptionMode = mode
    employeeAttendanceHistoryEmployeeId = employeeId.takeIf(String::isNotBlank)
    employeeAttendanceHistoryHasMore = employeeId.isNotBlank()
    _state.update {
        it.copy(
            currentEmployee = null,
            employeeAttendance = emptyList(),
            employeeAttendanceHistory = emptyList(),
            employeeAttendanceHistoryLoading = false,
            employeeAttendanceHistoryHasMore = employeeId.isNotBlank(),
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
        repository.observeEmployeeOffScheduleAttendanceReviews(employeeId)
            .catch { e -> setError(e) }
            .collect { rows -> _state.update { it.copy(offScheduleAttendanceReviews = rows) } }
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
    dataSubscriptions += viewModelScope.launch {
        flow {
            while (true) {
                val now = java.time.ZonedDateTime.now(zoneId)
                val target = mondayOfWeek(now.toLocalDate()).plusWeeks(1)
                emit(target)
                val rollover = target.atStartOfDay(zoneId).toInstant()
                delay(java.time.Duration.between(now.toInstant(), rollover).toMillis().coerceAtLeast(1L))
            }
        }.collectLatest { target ->
            _state.update { it.copy(employeeWeeklyTargetWeekStart = target, employeeWeeklyScheduleRequest = null) }
            repository.observeEmployeeWeeklyScheduleRequest(employeeId, target.toString())
                .catch { e -> setError(e) }
                .collect { request -> _state.update { it.copy(employeeWeeklyScheduleRequest = request) } }
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

internal fun MainViewModel.subscribeSchedules() {
    if (subscriptionMode != "ADMIN") return
    scheduleSubscription?.cancel()
    scheduleSubscription = viewModelScope.launch {
        repository.observeSchedules()
            .catch { e -> setError(e) }
            .collect { schedules -> _state.update { it.copy(schedules = schedules) } }
    }
}
