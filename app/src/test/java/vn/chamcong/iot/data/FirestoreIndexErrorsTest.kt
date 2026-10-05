package vn.chamcong.iot.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreIndexErrorsTest {
    @Test
    fun retriesReadUntilBuildingIndexBecomesReady() = runBlocking {
        var attempts = 0

        val result = retryWhileFirestoreIndexBuilds(maxRetries = 2, retryDelayMillis = 0) {
            attempts++
            if (attempts < 3) throw buildingIndexError()
            "overview loaded"
        }

        assertEquals("overview loaded", result)
        assertEquals(3, attempts)
    }

    @Test
    fun exhaustedRetryLimitRethrowsTheOriginalError() = runBlocking {
        val error = buildingIndexError()
        var attempts = 0

        val result = runCatching {
            retryWhileFirestoreIndexBuilds(maxRetries = 2, retryDelayMillis = 0) {
                attempts++
                throw error
            }
        }

        assertSame(error, result.exceptionOrNull())
        assertEquals(3, attempts)
    }

    @Test
    fun zeroRetryLimitMakesOnlyTheInitialRead() = runBlocking {
        val error = buildingIndexError()
        var attempts = 0

        val result = runCatching {
            retryWhileFirestoreIndexBuilds(maxRetries = 0, retryDelayMillis = 0) {
                attempts++
                throw error
            }
        }

        assertSame(error, result.exceptionOrNull())
        assertEquals(1, attempts)
    }

    @Test
    fun missingIndexPermissionAndUnrelatedPreconditionErrorsAreNotRetried() = runBlocking {
        val errors = listOf(
            IllegalStateException("FAILED_PRECONDITION: The query requires an index. You can create it here."),
            IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions."),
            IllegalStateException("FAILED_PRECONDITION: This operation requires an active transaction."),
            IllegalStateException("FAILED_PRECONDITION: An unrelated index is currently building.")
        )

        for (error in errors) {
            var attempts = 0
            val result = runCatching {
                retryWhileFirestoreIndexBuilds(maxRetries = 2, retryDelayMillis = 0) {
                    attempts++
                    throw error
                }
            }

            assertSame(error, result.exceptionOrNull())
            assertEquals(error.message, 1, attempts)
        }
    }

    @Test
    fun wrappedBuildingIndexErrorsAreClassifiedAndRetried() = runBlocking {
        val error = IllegalStateException("Không tải được tổng quan", buildingIndexError())
        var attempts = 0

        assertTrue(error.isFirestoreIndexRequired())
        assertTrue(error.isFirestoreIndexBuilding())

        val result = retryWhileFirestoreIndexBuilds(maxRetries = 1, retryDelayMillis = 0) {
            attempts++
            if (attempts == 1) throw error
            "overview loaded"
        }

        assertEquals("overview loaded", result)
        assertEquals(2, attempts)
    }

    @Test
    fun cancellationIsRethrownWithoutRetryEvenWithAnIndexMessage() = runBlocking {
        val error = CancellationException(BUILDING_INDEX_MESSAGE)
        var attempts = 0

        val result = runCatching {
            retryWhileFirestoreIndexBuilds(maxRetries = 2, retryDelayMillis = 0) {
                attempts++
                throw error
            }
        }

        assertSame(error, result.exceptionOrNull())
        assertEquals(1, attempts)
    }

    @Test(timeout = 1_000L)
    fun cyclicCausesTerminateWithoutFindingAnIndexError() {
        val outer = IllegalStateException("outer error")
        val inner = IllegalStateException("inner error")
        outer.initCause(inner)
        inner.initCause(outer)

        assertFalse(outer.isFirestoreIndexRequired())
        assertFalse(outer.isFirestoreIndexBuilding())
    }

    private fun buildingIndexError() = IllegalStateException(BUILDING_INDEX_MESSAGE)

    private companion object {
        const val BUILDING_INDEX_MESSAGE =
            "FAILED_PRECONDITION: The query requires an index. " +
                "That index is currently building and cannot be used yet."
    }
}
