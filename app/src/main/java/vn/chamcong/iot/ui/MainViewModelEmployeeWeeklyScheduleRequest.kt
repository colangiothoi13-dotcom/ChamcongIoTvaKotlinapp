package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.observeEmployeeWeeklyScheduleRequest
import vn.chamcong.iot.domain.mondayOfWeek
import java.time.Duration
import java.time.ZonedDateTime

internal fun MainViewModel.subscribeEmployeeWeeklyScheduleRequest() {
    val employeeId = _state.value.userProfile?.employeeId?.takeIf(String::isNotBlank) ?: return
    val mode = "EMPLOYEE:$employeeId"
    if (subscriptionMode != mode) return
    employeeWeeklyScheduleRequestSubscription?.cancel()
    _state.update {
        it.copy(
            employeeWeeklyScheduleRequestLoading = true,
            employeeWeeklyScheduleRequestError = null,
            employeeWeeklyScheduleRequestLoadedWeekStart = null
        )
    }
    employeeWeeklyScheduleRequestSubscription = viewModelScope.launch {
        flow {
            while (true) {
                val now = ZonedDateTime.now(zoneId)
                val target = mondayOfWeek(now.toLocalDate()).plusWeeks(1)
                emit(target)
                val rollover = target.atStartOfDay(zoneId).toInstant()
                delay(Duration.between(now.toInstant(), rollover).toMillis().coerceAtLeast(1L))
            }
        }.collectLatest { target ->
            _state.update {
                it.copy(
                    employeeWeeklyTargetWeekStart = target,
                    employeeWeeklyScheduleRequest = null,
                    employeeWeeklyScheduleRequestLoading = true,
                    employeeWeeklyScheduleRequestError = null,
                    employeeWeeklyScheduleRequestLoadedWeekStart = null
                )
            }
            repository.observeEmployeeWeeklyScheduleRequest(employeeId, target.toString())
                .catch { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        if (subscriptionMode != mode || it.employeeWeeklyTargetWeekStart != target) it
                        else it.copy(
                            employeeWeeklyScheduleRequestLoading = false,
                            employeeWeeklyScheduleRequestError = userFacingErrorMessage(error),
                            employeeWeeklyScheduleRequestLoadedWeekStart = null
                        )
                    }
                }.collect { request ->
                    _state.update {
                        if (subscriptionMode != mode || it.employeeWeeklyTargetWeekStart != target) it
                        else it.copy(
                            employeeWeeklyScheduleRequest = request,
                            employeeWeeklyScheduleRequestLoading = false,
                            employeeWeeklyScheduleRequestError = null,
                            employeeWeeklyScheduleRequestLoadedWeekStart = target
                        )
                    }
                }
        }
    }
}
