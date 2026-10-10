package vn.chamcong.iot.data

import com.google.firebase.Timestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreSnapshotsTest {
    @Test
    fun nullTimestampDoesNotCrashNotificationCopyOrSorting() {
        val notification = appNotificationFromFields("notification-1", mapOf(
            "type" to "ANNOUNCEMENT",
            "title" to "Thông báo thử",
            "body" to "Nội dung thử",
            "referenceId" to "announcement-1",
            "recipientEmployeeId" to "employee-1",
            "audienceLabel" to "Tất cả nhân viên",
            "createdAt" to null,
            "read" to false
        ))

        assertEquals("notification-1", notification.copy().id)
        assertEquals("ANNOUNCEMENT", notification.type)
        assertEquals("Thông báo thử", notification.title)
        assertEquals("Nội dung thử", notification.body)
        assertEquals("announcement-1", notification.referenceId)
        assertEquals("employee-1", notification.recipientEmployeeId)
        assertEquals("Tất cả nhân viên", notification.audienceLabel)
        assertFalse(notification.read)
        assertNotNull(listOf(notification).sortedByDescending { it.createdAt.toDate().time }.single().createdAt)
    }

    @Test
    fun missingLegacyTimestampStillProducesUsableNotification() {
        val notification = appNotificationFromFields("legacy-1", mapOf("title" to "Legacy"))

        assertEquals("Legacy", notification.copy().title)
        assertNotNull(notification.createdAt.toDate())
    }

    @Test
    fun estimatedAndCommittedTimestampsArePreserved() {
        val estimate = Timestamp(1_700_000_000L, 123)
        val committed = Timestamp(1_700_000_001L, 456)
        val pending = appNotificationFromFields("pending", mapOf("createdAt" to estimate))
        val saved = appNotificationFromFields("saved", mapOf("createdAt" to committed, "read" to true))

        assertSame(estimate, pending.createdAt)
        assertSame(committed, saved.createdAt)
        assertTrue(saved.read)
        assertEquals(listOf("saved", "pending"), listOf(pending, saved)
            .sortedByDescending { it.createdAt.toDate().time }.map { it.id })
    }

    @Test
    fun malformedNotificationFieldsDoNotEscapeIntoTheUi() {
        val notification = appNotificationFromFields("malformed", mapOf(
            "type" to 42,
            "title" to null,
            "body" to listOf("bad"),
            "referenceId" to true,
            "recipientEmployeeId" to 7,
            "audienceLabel" to false,
            "createdAt" to "bad-timestamp",
            "read" to "true"
        ))

        assertEquals("", notification.copy().title)
        assertEquals("", notification.body)
        assertEquals(null, notification.referenceId)
        assertEquals(null, notification.recipientEmployeeId)
        assertFalse(notification.read)
        assertNotNull(notification.createdAt)
    }

    @Test
    fun asynchronousCallbackDecodeErrorReachesFlowCatchAndRemovesListener() = runBlocking {
        val failure = IllegalArgumentException("Invalid Firestore document")
        var observed: Throwable? = null
        var listenerRemoved = false

        val rows = callbackFlow<Int> {
            val callback = launch { sendSnapshot(null) { throw failure } }
            awaitClose {
                callback.cancel()
                listenerRemoved = true
            }
        }.catch { observed = it }.toList()

        // Coroutines may copy an exception while recovering its stack trace.
        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        assertTrue(rows.isEmpty())
        assertTrue(listenerRemoved)
    }

    @Test
    fun firestoreErrorSkipsDecodeAndReachesFlowCatch() = runBlocking {
        val failure = IllegalStateException("Permission denied")
        var observed: Throwable? = null
        var decoded = false
        var listenerRemoved = false

        callbackFlow<Int> {
            sendSnapshot(failure) {
                decoded = true
                1
            }
            awaitClose { listenerRemoved = true }
        }.catch { observed = it }.toList()

        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        assertFalse(decoded)
        assertTrue(listenerRemoved)
    }

    @Test
    fun successfulSnapshotEmitsAndCancellationRemovesListener() = runBlocking {
        var listenerRemoved = false
        val value = callbackFlow<Int> {
            sendSnapshot(null) { 42 }
            awaitClose { listenerRemoved = true }
        }.take(1).single()

        assertEquals(42, value)
        assertTrue(listenerRemoved)
    }
}
