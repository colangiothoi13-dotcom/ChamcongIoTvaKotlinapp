package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
