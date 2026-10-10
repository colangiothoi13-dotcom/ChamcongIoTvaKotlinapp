package vn.chamcong.iot.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal const val TRANSIENT_FEEDBACK_DURATION_MS = 5_000L

private data class TransientFeedbackSnapshot(
    val generation: Long,
    val message: String?,
    val error: String?
) {
    val hasFeedback: Boolean get() = message != null || error != null
}

private fun MainUiState.transientFeedbackSnapshot() = TransientFeedbackSnapshot(
    generation = feedbackGeneration,
    message = message,
    error = error
)

/** One lifecycle-bound timer covers action feedback from every screen and operation helper. */
internal suspend fun expireTransientFeedback(
    state: MutableStateFlow<MainUiState>,
    waitForExpiry: suspend (Long) -> Unit = { delay(it) }
) {
    state.map { it.transientFeedbackSnapshot() }
        .distinctUntilChanged()
        .collectLatest { feedback ->
            if (!feedback.hasFeedback) return@collectLatest
            waitForExpiry(TRANSIENT_FEEDBACK_DURATION_MS)
            state.update { current ->
                // A cancelled/late timer must never clear a new action or another account's feedback.
                if (current.transientFeedbackSnapshot() != feedback) current
                else current.copy(message = null, error = null)
            }
        }
}
