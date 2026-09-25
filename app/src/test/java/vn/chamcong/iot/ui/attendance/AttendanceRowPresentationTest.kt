package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import java.time.Instant
import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
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

    @Test fun sparkRawScanIsPresentedAsRecordedNotWaiting() {
        val presentation = attendanceResolutionPresentation(row.copy(type = "SCAN", resolutionStatus = "PENDING"))

        assertTrue(presentation.label.startsWith("Đã ghi nhận từ thiết bị"))
        assertFalse(presentation.label.contains("Chờ hệ thống xử lý"))
        assertFalse(presentation.accepted)
    }

    @Test fun allAttendanceListUsesSparkResolvedTypeForDeviceScan() {
        val date = "2026-09-17"
        val shift = WorkShift(
            id = "morning",
            name = "Ca sáng",
            category = ShiftCategory.MORNING.name,
            startTime = "08:00",
            endTime = "12:00"
        )
        val rawScan = row.copy(
            id = "scan",
            type = "SCAN",
            status = "PENDING",
            resolutionStatus = AttendanceResolutionStatus.PENDING.name,
            timestamp = Timestamp(Date.from(Instant.parse("2026-09-17T03:00:00Z")))
        )
        val state = MainUiState(
            employees = listOf(Employee(id = "e1", fullName = "An")),
            attendance = listOf(rawScan),
            shifts = listOf(shift),
            schedules = listOf(WorkSchedule(employeeId = "e1", date = date, shiftId = shift.id)),
            attendanceTypeFilter = "CHECK_IN"
        )

        val visible = state.visibleAttendance.single()

        assertEquals("CHECK_IN", visible.type)
        assertEquals(AttendanceResolutionStatus.ACCEPTED.name, visible.resolutionStatus)
        assertEquals("LATE", visible.status)
        assertTrue(attendanceResolutionPresentation(visible).accepted)
    }

    @Test fun malformedAndUnverifiedRowsNeverLookAccepted() {
        listOf(row.copy(verified = false), row.copy(type = "unknown"),
            row.copy(resolutionStatus = "unknown"), row.copy(scheduleDate = "bad-date"),
            row.copy(status = "unknown")).forEach {
            assertFalse(attendanceResolutionPresentation(it).accepted)
            assertTrue(attendanceResolutionPresentation(it).label.contains("Bất thường"))
        }
    }

    @Test fun serverResolutionTypesUseTheirDedicatedLabels() {
        listOf("DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER").forEach { status ->
            val presentation = attendanceResolutionPresentation(row.copy(type = status, resolutionStatus = status, status = "ABNORMAL"))
            val expected = mapOf(
                "DUPLICATE" to "Quét trùng",
                "UNSCHEDULED" to "Chưa có ca",
                "OUT_OF_ORDER" to "Sai thứ tự"
            ).getValue(status)
            assertTrue("Expected $expected label, got ${presentation.label}", presentation.label.startsWith(expected))
            assertFalse(presentation.accepted)
        }
    }

    @Test fun serverResolutionTypesDoNotBypassAbnormalGuards() {
        listOf("DUPLICATE", "UNSCHEDULED", "OUT_OF_ORDER").forEach { status ->
            val resolved = row.copy(type = status, resolutionStatus = status, status = "ABNORMAL")
            listOf(resolved.copy(verified = false), resolved.copy(scheduleDate = "bad-date"),
                resolved.copy(type = "unknown"), resolved.copy(resolutionStatus = "unknown")).forEach {
                val presentation = attendanceResolutionPresentation(it)
                assertTrue(presentation.label.startsWith("Bất thường"))
                assertFalse(presentation.accepted)
            }
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
