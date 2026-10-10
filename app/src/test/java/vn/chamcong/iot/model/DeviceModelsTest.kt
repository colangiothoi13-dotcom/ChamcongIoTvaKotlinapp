package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

class DeviceModelsTest {
    @Test
    fun deviceIsOfflineWhenHeartbeatIsOlderThanTwoMinutes() {
        val now = Instant.parse("2026-09-13T10:00:00Z")
        val device = DeviceSnapshot(lastHeartbeat = Timestamp(Date.from(now.minusSeconds(121))))

        assertFalse(device.isOnline(now))
    }

    @Test
    fun deviceWithNoHeartbeatIsUnknown() {
        assertFalse(DeviceSnapshot(status = "UNKNOWN").isOnline(Instant.parse("2026-09-13T10:00:00Z")))
    }

    @Test
    fun freshHeartbeatToleratesPeriodicUiClockSampleWithoutAcceptingLargeFutureSkew() {
        val now = Instant.parse("2026-10-09T03:36:18Z")
        for (seconds in listOf(0L, 15L, 30L)) {
            val device = DeviceSnapshot(status = "ONLINE", wifiStatus = "ONLINE",
                lastHeartbeat = Timestamp(Date.from(now.plusSeconds(seconds))))
            assertTrue(device.isOnline(now))
            assertEquals(DeviceConnectionState.ONLINE, device.connectionState(now))
        }
        for (seconds in listOf(31L, 120L, 3600L)) {
            val device = DeviceSnapshot(lastHeartbeat = Timestamp(Date.from(now.plusSeconds(seconds))))
            assertFalse(device.isOnline(now))
            assertEquals(DeviceConnectionState.CLOCK_SKEW, device.connectionState(now))
        }
    }

    @Test
    fun historicalOnlineAndHealthySensorNeverOverrideStaleHeartbeat() {
        val now = Instant.parse("2026-10-09T03:36:18Z")
        val device = DeviceSnapshot(status = "ONLINE", wifiStatus = "ONLINE", firebaseSyncStatus = "ONLINE",
            sensorStatus = "OK", lastHeartbeat = Timestamp(Date.from(Instant.parse("2026-10-09T00:53:18Z"))))
        assertFalse(device.isOnline(now))
        assertEquals(DeviceConnectionState.STALE_HEARTBEAT, device.connectionState(now))
        assertEquals(DeviceConnectionState.NO_HEARTBEAT, device.copy(lastHeartbeat = null).connectionState(now))
    }

    @Test
    fun timeoutBoundaryAndFreshExplicitOfflineArePreserved() {
        val now = Instant.parse("2026-10-09T03:36:18Z")
        val device = DeviceSnapshot(lastHeartbeat = Timestamp(Date.from(now.minusSeconds(120))))
        assertTrue(device.isOnline(now))
        assertEquals(DeviceConnectionState.STALE_HEARTBEAT, device.copy(
            lastHeartbeat = Timestamp(Date.from(now.minusSeconds(121)))).connectionState(now))
        for (offline in listOf(device.copy(status = "OFFLINE"), device.copy(wifiStatus = "offline"),
            device.copy(wifiStatus = " OFFLINE "))) {
            assertFalse(offline.isOnline(now))
            assertEquals(DeviceConnectionState.REPORTED_OFFLINE, offline.connectionState(now))
        }
    }

    @Test
    fun pendingAttendanceSyncDoesNotEraseEvidenceOfFreshFirebaseContact() {
        val now = Instant.parse("2026-10-09T03:36:18Z")
        val device = DeviceSnapshot(status = "ONLINE", wifiStatus = "ONLINE", firebaseSyncStatus = "ERROR",
            sensorStatus = "OK", pendingAttendanceCount = 3, lastHeartbeat = Timestamp(Date.from(now)))
        assertTrue(device.isOnline(now))
    }

    @Test
    fun processingEnrollmentCommandHasActionableLabel() {
        assertEquals(
            "Thiết bị đang chờ thao tác",
            commandStatusLabel(mapOf("type" to "ENROLL_FINGERPRINT", "status" to "PROCESSING"))
        )
    }

    @Test
    fun failedDeleteCommandExplainsRetryState() {
        assertEquals(
            "Xóa vân tay thất bại; vị trí được giữ lại",
            commandStatusLabel(mapOf("type" to "DELETE_FINGERPRINT", "status" to "FAILED"))
        )
    }
}
