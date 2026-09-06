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
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.data.FirebaseRepository
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.work.AttendanceSyncWorker

data class MainUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val employees: List<Employee> = emptyList(),
    val attendance: List<Attendance> = emptyList(),
    val payroll: List<Payroll> = emptyList(),
    val commands: List<Map<String, Any>> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FirebaseRepository()
    private val _state = MutableStateFlow(MainUiState(signedIn = repository.isSignedIn))
    val state: StateFlow<MainUiState> = _state.asStateFlow()



    fun signIn(email: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.signIn(email, password) }
            .onSuccess {
                _state.update { it.copy(signedIn = true, loading = false) }
                subscribe()
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.localizedMessage) } }
    }

    private val subscriptions = mutableListOf<Job>()

    init { if (repository.isSignedIn) subscribe() }

    private fun subscribe() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        subscriptions += viewModelScope.launch {
            repository.observePayroll().catch { e -> setError(e) }.collect { rows ->
                _state.update { it.copy(payroll=rows) }
            }
        }
        subscriptions += viewModelScope.launch {
            repository.observeEmployees().catch { e -> setError(e) }.collect { employees ->
                _state.update { it.copy(employees = employees) }
            }
        }
        subscriptions += viewModelScope.launch {
            repository.observeRecentAttendance().catch { e -> setError(e) }.collect { attendance ->
                _state.update { it.copy(attendance = attendance) }
                attendance.firstOrNull()?.id?.takeIf(String::isNotBlank)?.let(::enqueueReceipt)
            }
        }
        subscriptions += viewModelScope.launch {
            repository.observeEnrollmentCommands().catch { e -> setError(e) }.collect { commands ->
                _state.update { it.copy(commands = commands) }
                commands.filter { it["status"] == "COMPLETED" && it["applied"] != true }.forEach {
                    runCatching { repository.applyCompletedEnrollment(it) }.onFailure(::setError)
                }
            }
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
    fun saveEmployee(employee: Employee, done: () -> Unit) = perform(done) {
        "Đã lưu nhân viên ${repository.saveEmployee(employee)}"
    }
    fun requestFingerprint(employee: Employee, deviceId: String, done: () -> Unit) = perform(done) {
        "Đã gửi đăng ký cho ${repository.saveAndRequestFingerprint(employee, deviceId)}. Đặt ngón tay tại thiết bị."
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
    fun savePayroll(employeeId: String, month: String, bonus: Long, deduction: Long, done: () -> Unit) = perform(done) {
        repository.savePayroll(employeeId, month, bonus, deduction)
        "Đã lưu phiếu lương tháng $month"
    }
    fun signOut() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        repository.signOut()
        _state.value = MainUiState()
    }

    fun clearError() = _state.update { it.copy(error = null) }
    private fun setError(error: Throwable) = _state.update { it.copy(error = error.localizedMessage) }

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
