package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.domain.isAcceptedAttendance
import vn.chamcong.iot.domain.resolveSparkPendingAttendance
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceMethod
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.attendanceMethod

class AttendanceMethodPresentationTest {
    @Test fun historicalScansRetainFingerprintPresentationWithoutNewFields() {
        val legacy = Attendance()
        assertNull(legacy.method)
        assertNull(legacy.templateId)
        assertNull(legacy.faceSimilarity)
        assertEquals(AttendanceMethod.FINGERPRINT, legacy.attendanceMethod())
        assertEquals("Vân tay", attendanceMethodLabel(legacy))
    }

    @Test fun faceAndFingerprintLabelsAreDistinctAndUnknownIsNotFingerprint() {
        assertEquals("Vân tay", attendanceMethodLabel(Attendance(method = "FINGERPRINT")))
        assertEquals("Khuôn mặt", attendanceMethodLabel(Attendance(method = "FACE")))
        listOf("", "face", "OTHER").forEach { value ->
            val row = Attendance(method = value)
            assertEquals(AttendanceMethod.UNKNOWN, row.attendanceMethod())
            assertEquals("Phương thức không xác định", attendanceMethodLabel(row))
        }
    }

    @Test fun localShiftResolutionPreservesFaceMetadataAndDeduplicatesAcrossMethods() {
        val captured = Instant.parse("2026-10-12T01:00:00Z")
        val face = Attendance(
            id = "face-event", employeeId = "e1", employeeName = "An", deviceId = "CAM-01",
            method = "FACE", sourceEventId = "camera-session-1", stationId = "GATE-01",
            type = "SCAN", status = "PENDING", resolutionStatus = "PENDING", syncStatus = "PENDING_SYNC",
            timestamp = Timestamp(Date.from(captured)), capturedAt = Timestamp(Date.from(captured)),
            faceSimilarity = 0.62, profileVersion = 2
        )
        val fingerprint = face.copy(
            id = "fingerprint-event", deviceId = "GATE-01", method = "FINGERPRINT",
            sourceEventId = "fingerprint-1", timestamp = Timestamp(Date.from(captured.plusSeconds(1))),
            capturedAt = Timestamp(Date.from(captured.plusSeconds(1))),
            templateId = 7, confidence = 120, faceSimilarity = null, profileVersion = null
        )
        val shift = WorkShift(id = "morning", name = "Ca sáng", category = "MORNING",
            startTime = "08:00", endTime = "12:00")
        val schedules = listOf(
            WorkSchedule(employeeId = "e1", date = "2026-10-12", shiftId = shift.id),
            WorkSchedule(employeeId = "e2", date = "2026-10-12", shiftId = shift.id)
        )
        val otherEmployee = fingerprint.copy(id = "other-person", employeeId = "e2", employeeName = "Bình")
        val checkout = face.copy(id = "checkout", sourceEventId = "camera-session-2",
            timestamp = Timestamp(Date.from(Instant.parse("2026-10-12T05:00:00Z"))))

        val resolved = resolveSparkPendingAttendance(
            listOf(face, fingerprint, otherEmployee, checkout), schedules, listOf(shift), ZoneId.of("Asia/Ho_Chi_Minh")
        )

        val checkIn = resolved.single { it.id == face.id }
        assertTrue(isAcceptedAttendance(checkIn))
        assertEquals("CHECK_IN", checkIn.type)
        assertEquals("FACE", checkIn.method)
        assertEquals(face.sourceEventId, checkIn.sourceEventId)
        assertEquals(face.capturedAt, checkIn.capturedAt)
        assertEquals(face.faceSimilarity, checkIn.faceSimilarity)
        assertEquals(face.profileVersion, checkIn.profileVersion)
        assertNull(checkIn.templateId)
        val duplicate = resolved.single { it.id == fingerprint.id }
        assertEquals("DUPLICATE", duplicate.type)
        assertEquals("FINGERPRINT", duplicate.method)
        assertEquals(7, duplicate.templateId)
        assertFalse(isAcceptedAttendance(duplicate))
        assertTrue(isAcceptedAttendance(resolved.single { it.id == otherEmployee.id }))
        assertEquals("CHECK_OUT", resolved.single { it.id == checkout.id }.type)
        assertEquals("SCAN", face.type)
    }
}
