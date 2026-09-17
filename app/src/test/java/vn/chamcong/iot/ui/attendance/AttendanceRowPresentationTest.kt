package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import java.time.Instant
import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.ui.MainUiState

class AttendanceRowPresentationTest {
    private val row = Attendance(employeeId = "e1", employeeName = "An",
        timestamp = Timestamp(Date.from(Instant.parse("2026-09-17T23:00:00Z"))))

    @Test fun acceptedAndEachUnresolvedStateHaveDistinctPresentation() {
        val states = listOf("ACCEPTED", "PENDING", "DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER")
        val presentations = states.map { attendanceResolutionPresentation(row.copy(resolutionStatus = it)) }
        assertEquals(5, presentations.map { it.label }.distinct().size)
        assertTrue(presentations.first().accepted)
        assertTrue(presentations.drop(1).none { it.accepted })
        assertFalse(attendanceResolutionPresentation(row.copy(type = "SCAN")).accepted)
    }

    @Test fun malformedAndUnverifiedRowsNeverLookAccepted() {
        listOf(row.copy(verified = false), row.copy(type = "unknown"),
            row.copy(resolutionStatus = "unknown"), row.copy(scheduleDate = "bad-date"),
            row.copy(status = "unknown")).forEach {
            assertFalse(attendanceResolutionPresentation(it).accepted)
            assertTrue(attendanceResolutionPresentation(it).label.contains("ABNORMAL"))
        }
    }

    @Test fun scheduleDateWinsOverVietnamCalendarFallback() {
        assertEquals("2026-09-17", attendanceAdjustmentDate(row.copy(scheduleDate = "2026-09-17")))
        assertEquals("2026-09-18", attendanceAdjustmentDate(row))
        assertNull(attendanceAdjustmentDate(row.copy(scheduleDate = "bad-date")))
    }

    @Test fun targetUsesAllRowsAndLatestCorrectionInsteadOfTheClickedScan() {
        val start = row.copy(id = "in", timestamp = Timestamp(Date.from(Instant.parse("2026-09-17T01:00:00Z"))),
            scheduleDate = "2026-09-17")
        val end = row.copy(id = "out", type = "CHECK_OUT", scheduleDate = "2026-09-17",
            timestamp = Timestamp(Date.from(Instant.parse("2026-09-17T09:00:00Z"))))
        val duplicate = end.copy(id = "duplicate", resolutionStatus = "DUPLICATE")
        val correction = AttendanceAdjustment(employeeId = "e1", employeeName = "An", scheduleDate = "2026-09-17",
            checkInAt = Instant.parse("2026-09-17T02:00:00Z"), workedHoursOverride = 6.5,
            reason = "Correction", actorId = "admin", actorName = "Admin")
        val target = attendanceAdjustmentTarget(duplicate,
            MainUiState(attendance = listOf(start, end, duplicate), attendanceAdjustments = listOf(correction)))!!
        assertEquals("2026-09-17", target.scheduleDate)
        assertEquals(Instant.parse("2026-09-17T02:00:00Z"), target.currentCheckIn!!.toDate().toInstant())
        assertEquals(Instant.parse("2026-09-17T09:00:00Z"), target.currentCheckOut!!.toDate().toInstant())
        assertEquals(6.5, target.currentWorkedHours!!, 0.0)
        assertNull(attendanceAdjustmentTarget(row.copy(employeeId = ""), MainUiState()))
    }
}
