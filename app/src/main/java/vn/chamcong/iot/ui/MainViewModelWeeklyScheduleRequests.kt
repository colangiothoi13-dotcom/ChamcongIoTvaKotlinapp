package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.observeWeeklyScheduleRequests

internal fun MainViewModel.subscribeAdminWeeklyScheduleRequests() {
    weeklyScheduleRequestsSubscription?.cancel()
    _state.update {
        it.copy(
            weeklyScheduleRequests = emptyList(),
            weeklyScheduleRequestsLoading = true,
            weeklyScheduleRequestsError = null,
            weeklyScheduleRequestsLoadedWeekStart = null
        )
    }
    weeklyScheduleRequestsSubscription = viewModelScope.launch {
        _state.map { it.selectedWeekStart }.distinctUntilChanged().collectLatest { week ->
            _state.update {
                it.copy(
                    weeklyScheduleRequests = emptyList(),
                    weeklyScheduleRequestsLoading = true,
                    weeklyScheduleRequestsError = null,
                    weeklyScheduleRequestsLoadedWeekStart = null
                )
            }
            repository.observeWeeklyScheduleRequests(week.toString())
                .catch { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        if (subscriptionMode != "ADMIN" || it.selectedWeekStart != week) it
                        else it.copy(
                            weeklyScheduleRequests = emptyList(),
                            weeklyScheduleRequestsLoading = false,
                            weeklyScheduleRequestsError = userFacingErrorMessage(error),
                            weeklyScheduleRequestsLoadedWeekStart = null
                        )
                    }
                }
                .collect { requests ->
                    _state.update {
                        if (subscriptionMode != "ADMIN" || it.selectedWeekStart != week) it
                        else it.copy(
                            weeklyScheduleRequests = requests,
                            weeklyScheduleRequestsLoading = false,
                            weeklyScheduleRequestsError = null,
                            weeklyScheduleRequestsLoadedWeekStart = week
                        )
                    }
                }
        }
    }
}
