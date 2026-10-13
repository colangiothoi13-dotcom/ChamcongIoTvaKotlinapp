package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Date

class FaceEnrollmentModelsTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private fun station(ageSeconds: Long = 0) = FaceStation(
        stationId = "GATE-01", faceDeviceId = "CAM-01", sessionId = "session-1",
        mode = "firebase", cameraReady = true,
        lastSeenAt = Timestamp(Date.from(now.minusSeconds(ageSeconds)))
    )

    @Test fun registrationNeedsEnabledVersionedMetadata() {
        assertFalse(FaceProfile(enabled = true).registered)
        assertFalse(FaceProfile(enabled = false, version = 2).registered)
        assertTrue(FaceProfile(enabled = true, version = 2).registered)
    }

    @Test fun stationNeedsFreshFirebaseSessionAndReadyCamera() {
        assertTrue(station(90).available(now))
        assertFalse(station(91).available(now))
        assertFalse(station(-31).available(now))
        assertTrue(station(-2).available(now))
        assertFalse(station().copy(lastSeenAt = null).available(now))
        assertFalse(station().copy(mode = "demo").available(now))
        assertFalse(station().copy(cameraReady = false).available(now))
        assertFalse(station().copy(sessionId = "").available(now))
        assertFalse(station().copy(faceDeviceId = "").available(now))
    }

    @Test fun finalizingCannotBeCancelledOrUsedToStartAnotherRequest() {
        val command = FaceEnrollmentCommand(status = "FINALIZING")
        assertTrue(command.active)
        assertFalse(command.canCancel)
        assertEquals("Đang lưu mẫu; vui lòng chờ", command.statusLabel)
    }

    @Test fun cancellationRequestNeverPretendsWorkerAlreadyCancelled() {
        val command = FaceEnrollmentCommand(status = "CAPTURING", cancelRequested = true)
        assertTrue(command.active)
        assertFalse(command.canCancel)
        assertEquals("Đang hủy; chờ laptop xác nhận", command.statusLabel)
        assertFalse(command.copy(status = "CANCELLED").active)
    }

    @Test fun deletionCompletionAndFailedCaptureHaveDistinctLabels() {
        assertEquals("Đã xóa mẫu khuôn mặt", FaceEnrollmentCommand(action = "DELETE", status = "COMPLETED").statusLabel)
        val failure = FaceEnrollmentCommand(action = "ENROLL", status = "FAILED", message = "Không thấy mặt")
        assertFalse(failure.active)
        assertEquals("Thất bại: Không thấy mặt", failure.statusLabel)
    }

    @Test fun malformedSnapshotCannotEnableStationOrProfile() {
        val station = faceStationFromFields("GATE-01", mapOf("cameraReady" to "true", "sessionId" to 123, "lastSeenAt" to "now"))
        assertFalse(station.available(now))
        assertFalse(faceProfileFromFields("employee-1", mapOf("enabled" to "true", "version" to "1")).registered)
        val command = faceEnrollmentCommandFromFields("request-1", mapOf("sampleCount" to -1, "samplesCaptured" to -20, "message" to 123))
        assertEquals(0, command.sampleCount)
        assertEquals(0, command.samplesCaptured)
        assertEquals("", command.message)
        assertFalse(command.active)
    }
}
