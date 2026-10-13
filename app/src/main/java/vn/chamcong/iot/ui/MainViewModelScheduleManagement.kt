package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.removeWorkSchedule
import vn.chamcong.iot.data.setSupplementaryShiftActive
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift

fun MainViewModel.setSupplementaryShiftActive(shift: WorkShift, active: Boolean, done: () -> Unit = {}) = manageSchedule(done) {
    repository.setSupplementaryShiftActive(shift, active)
    "Đã ${if (active) "kích hoạt" else "ngừng"} cấu hình ca ${shift.name}"
}

fun MainViewModel.removeWorkSchedule(schedule: WorkSchedule, done: () -> Unit = {}) = manageSchedule(done) {
    repository.removeWorkSchedule(schedule)
    retryAttendanceRange()
    "Đã bỏ lịch làm việc ngày ${schedule.date}"
}

private fun MainViewModel.manageSchedule(done: () -> Unit = {}, action: suspend () -> String) = viewModelScope.launch {
    if (_state.value.saving || !hasAdminAccess()) return@launch
    _state.update { it.copy(saving = true, error = null, message = null, feedbackGeneration = it.feedbackGeneration + 1) }
    try {
        val message = action()
        _state.update { it.copy(saving = false, message = message) }
        done()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failed: Exception) { _state.update { it.copy(saving = false, error = userFacingErrorMessage(failed)) } }
}
