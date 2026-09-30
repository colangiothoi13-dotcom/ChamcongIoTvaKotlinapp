package vn.chamcong.iot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSnapshotParsingTest {
    @Test
    fun malformedFirestoreFieldsFallBackWithoutCrashing() {
        val snapshot = deviceSnapshotFromFields(
            id = "GATE-01",
            fields = mapOf(
                "name" to 123,
                "location" to true,
                "status" to 42,
                "lastHeartbeat" to "not-a-timestamp",
                "firmwareVersion" to listOf("bad"),
                "fingerprintCount" to "127",
                "capacity" to 127L,
                "capabilities" to listOf("fingerprint", 7)
            )
        )

        assertEquals("GATE-01", snapshot.id)
        assertEquals("", snapshot.name)
        assertEquals("", snapshot.location)
        assertEquals("UNKNOWN", snapshot.status)
        assertNull(snapshot.lastHeartbeat)
        assertEquals("", snapshot.firmwareVersion)
        assertNull(snapshot.fingerprintCount)
        assertEquals(127, snapshot.capacity)
        assertEquals(setOf("fingerprint"), snapshot.capabilities)
    }
}
