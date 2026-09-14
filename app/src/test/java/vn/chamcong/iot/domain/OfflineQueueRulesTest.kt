package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueRulesTest {
    @Test
    fun duplicateEventIdsAreCollapsedBeforeRetry() {
        assertEquals(listOf("event-a", "event-b"), deduplicatePendingEventIds(listOf("event-a", "event-a", "event-b")))
    }

    @Test
    fun successfulOrDuplicateHttpResponsesAcknowledgeAnEvent() {
        assertTrue(httpResponseAcknowledgesEvent(200))
        assertTrue(httpResponseAcknowledgesEvent(409))
        assertFalse(httpResponseAcknowledgesEvent(500))
    }
}
