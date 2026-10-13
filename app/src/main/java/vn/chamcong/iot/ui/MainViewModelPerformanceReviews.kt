package vn.chamcong.iot.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.observePerformanceReviews
import vn.chamcong.iot.data.savePerformanceReview
import vn.chamcong.iot.model.PerformanceReview

internal fun MainViewModel.subscribePerformanceReviews() {
    performanceReviewsSubscription?.cancel()
    _state.update { it.copy(performanceReviewsLoading = true, performanceReviewsError = null) }
    performanceReviewsSubscription = viewModelScope.launch {
        repository.observePerformanceReviews().catch { error ->
            if (error is CancellationException) throw error
            _state.update { it.copy(performanceReviewsLoading = false, performanceReviewsError = userFacingErrorMessage(error)) }
        }.collect { reviews ->
            _state.update { it.copy(performanceReviews = reviews, performanceReviewsLoading = false, performanceReviewsError = null) }
        }
    }.also { dataSubscriptions += it }
}

fun MainViewModel.retryPerformanceReviews() { if (hasAdminAccess()) subscribePerformanceReviews() }

fun MainViewModel.savePerformanceReview(review: PerformanceReview, previous: PerformanceReview? = null, done: () -> Unit = {}) = viewModelScope.launch {
    if (_state.value.saving || !hasAdminAccess()) return@launch
    _state.update { it.copy(saving = true, error = null, message = null, feedbackGeneration = it.feedbackGeneration + 1) }
    try {
        repository.savePerformanceReview(review, previous)
        _state.update { it.copy(saving = false, message = "Đã lưu đánh giá hiệu suất") }
        done()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { _state.update { it.copy(saving = false, error = userFacingErrorMessage(error)) } }
}
