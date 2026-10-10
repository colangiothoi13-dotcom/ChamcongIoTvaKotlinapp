package vn.chamcong.iot.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientFeedbackTest {
    private class ExpiryRequest(val durationMs: Long) {
        val expire = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
    }

    /** Logical expiry gates exercise cancellation and races without sleeping for real seconds. */
    private class FakeExpiryClock {
        val requests = Channel<ExpiryRequest>(Channel.UNLIMITED)

        suspend fun waitForExpiry(durationMs: Long) {
            val request = ExpiryRequest(durationMs)
            requests.send(request)
            try {
                request.expire.await()
            } catch (cancelled: CancellationException) {
                request.cancelled.complete(Unit)
                throw cancelled
            }
        }

        suspend fun next(): ExpiryRequest = withTimeout(2_000) { requests.receive() }
    }

    private suspend fun MutableStateFlow<MainUiState>.awaitCleared() = withTimeout(2_000) {
        first { it.message == null && it.error == null }
    }

    @Test(timeout = 5_000)
    fun actionSuccessAndErrorExpireAfterFiveSecondsWithoutClearingDataErrors(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(
            message = "Đã lưu", error = "Không gửi được thao tác",
            profileError = "Chưa tải hồ sơ", workItemsError = "Chưa tải công việc",
            attendanceHistoryError = "Chưa tải bảng công"
        ))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val request = clock.next()
            assertEquals(5_000L, request.durationMs)
            assertEquals("Đã lưu", state.value.message)
            assertEquals("Không gửi được thao tác", state.value.error)
            request.expire.complete(Unit)
            val cleared = state.awaitCleared()
            assertEquals("Chưa tải hồ sơ", cleared.profileError)
            assertEquals("Chưa tải công việc", cleared.workItemsError)
            assertEquals("Chưa tải bảng công", cleared.attendanceHistoryError)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test(timeout = 5_000)
    fun newerFeedbackGetsItsOwnFiveSecondsAndCancelsTheOldTimer(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(message = "Cũ"))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val old = clock.next()
            state.update { it.copy(message = "Mới", error = "Lỗi mới", feedbackGeneration = it.feedbackGeneration + 1) }
            val newer = clock.next()
            withTimeout(2_000) { old.cancelled.await() }
            old.expire.complete(Unit)
            assertEquals("Mới", state.value.message)
            assertEquals("Lỗi mới", state.value.error)
            assertEquals(5_000L, newer.durationMs)
            newer.expire.complete(Unit)
            state.awaitCleared()
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test(timeout = 5_000)
    fun repeatingIdenticalFeedbackRestartsEvenWithoutAnObservedEmptyState(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(message = "Đã lưu", feedbackGeneration = 1L))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val old = clock.next()
            // StateFlow may conflate the operation's clear/result; its generation still identifies a new action.
            state.update { it.copy(feedbackGeneration = it.feedbackGeneration + 1) }
            val repeated = clock.next()
            withTimeout(2_000) { old.cancelled.await() }
            old.expire.complete(Unit)
            assertEquals("Đã lưu", state.value.message)
            repeated.expire.complete(Unit)
            state.awaitCleared()
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test(timeout = 5_000)
    fun accountResetCancelsOldFeedbackBeforeAnotherSessionPublishesTheSameText(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(signedIn = true, error = "Lỗi", feedbackGeneration = 7L))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val oldSession = clock.next()
            state.value = MainUiState(feedbackGeneration = 8L)
            withTimeout(2_000) { oldSession.cancelled.await() }
            state.value = MainUiState(signedIn = true, error = "Lỗi", feedbackGeneration = 9L)
            val newSession = clock.next()
            oldSession.expire.complete(Unit)
            assertEquals("Lỗi", state.value.error)
            assertTrue(state.value.signedIn)
            newSession.expire.complete(Unit)
            state.awaitCleared()
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test(timeout = 5_000)
    fun unrelatedStateUpdatesDoNotExtendFeedbackOrGetOverwrittenOnExpiry(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(message = "Đã lưu"))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val request = clock.next()
            state.update { it.copy(employeeQuery = "mtri3", attendanceHistoryLoading = true) }
            yield()
            assertTrue(clock.requests.tryReceive().isFailure)
            request.expire.complete(Unit)
            val cleared = state.awaitCleared()
            assertEquals("mtri3", cleared.employeeQuery)
            assertTrue(cleared.attendanceHistoryLoading)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test(timeout = 5_000)
    fun manualDismissAndLifecycleCancellationStopTheirPendingTimers(): Unit = runBlocking {
        val state = MutableStateFlow(MainUiState(error = "Lỗi thao tác"))
        val clock = FakeExpiryClock()
        val observer = launch { expireTransientFeedback(state, clock::waitForExpiry) }
        try {
            val dismissed = clock.next()
            state.update { it.copy(error = null) }
            withTimeout(2_000) { dismissed.cancelled.await() }
            assertNull(state.value.error)
            state.update { it.copy(message = "Kết quả mới", feedbackGeneration = it.feedbackGeneration + 1) }
            val lifecycle = clock.next()
            observer.cancelAndJoin()
            withTimeout(2_000) { lifecycle.cancelled.await() }
            lifecycle.expire.complete(Unit)
            assertEquals("Kết quả mới", state.value.message)
        } finally {
            observer.cancelAndJoin()
        }
    }
}
