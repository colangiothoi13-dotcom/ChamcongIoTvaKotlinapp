package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.observeFaceEnrollmentCommands
import vn.chamcong.iot.data.observeFaceProfiles
import vn.chamcong.iot.data.observeFaceStations

/** These collections are Admin-only; employee sessions never open their listeners. */
internal fun MainViewModel.subscribeAdminFaceEnrollment() {
    faceEnrollmentSubscription?.let { it.cancel(); dataSubscriptions.remove(it) }
    _state.update { it.copy(faceEnrollmentLoading = true, faceEnrollmentError = null) }
    faceEnrollmentSubscription = viewModelScope.launch {
        combine(repository.observeFaceProfiles(), repository.observeFaceStations(), repository.observeFaceEnrollmentCommands()) {
            profiles, stations, commands -> Triple(profiles, stations, commands)
        }.catch { error ->
            if (error is CancellationException) throw error
            _state.update { it.copy(faceEnrollmentLoading = false,
                faceEnrollmentError = "Không tải được đăng ký khuôn mặt: ${userFacingErrorMessage(error)}") }
        }.collect { (profiles, stations, commands) ->
            _state.update { it.copy(faceProfiles = profiles, faceStations = stations,
                faceEnrollmentCommands = commands, faceEnrollmentLoading = false, faceEnrollmentError = null) }
        }
    }
    faceEnrollmentSubscription?.let { dataSubscriptions += it }
}
