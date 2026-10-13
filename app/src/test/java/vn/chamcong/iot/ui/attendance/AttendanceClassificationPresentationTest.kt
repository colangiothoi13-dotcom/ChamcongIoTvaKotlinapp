package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.domain.canCorrectAttendanceClassification
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceClassificationOverride
import java.time.Instant
import java.util.Date

class AttendanceClassificationPresentationTest {
    private val source = Attendance(id = "scan", employeeId = "e1", employeeName = "An",
        scheduleDate = "2026-10-10", type = "CHECK_IN", status = "ON_TIME", resolutionStatus = "ACCEPTED",
        timestamp = Timestamp(Date.from(Instant.parse("2026-10-10T01:00:00Z"))))

    @Test fun eligibleImmutableSourcesIncludeAcceptedScansAndPendingDeviceScans() {
        assertTrue(canCorrectAttendanceClassification(source))
        assertTrue(canCorrectAttendanceClassification(source.copy(type = "CHECK_OUT", status = "EARLY_LEAVE")))
        assertTrue(canCorrectAttendanceClassification(source.copy(type = "SCAN", status = "PENDING", resolutionStatus = "PENDING")))
        listOf(source.copy(verified = false), source.copy(id = ""), source.copy(employeeId = ""),
            source.copy(type = "DUPLICATE", resolutionStatus = "DUPLICATE"),
            source.copy(type = "UNSCHEDULED", resolutionStatus = "UNSCHEDULED"),
            source.copy(type = "SCAN", status = "NORMAL", resolutionStatus = "PENDING"),
            source.copy(status = "INVALID")).forEach { assertFalse(canCorrectAttendanceClassification(it)) }
    }

    @Test fun acceptedScanStatusCanBeCorrectedWithoutMutatingSource() {
        val draft = attendanceClassificationDraft(source, source, null, "CHECK_IN", "LATE", "  Xác minh giờ vào  ")
        assertEquals("CHECK_IN", draft.sourceType); assertEquals("ON_TIME", draft.sourceStatus)
        assertEquals("ON_TIME", draft.previousStatus); assertEquals("LATE", draft.correctedStatus)
        assertEquals("Xác minh giờ vào", draft.reason)
        assertEquals(source.timestamp.toDate().toInstant(), draft.sourceTimestamp)
        assertEquals("ON_TIME", source.status)
    }

    @Test fun recorrectionUsesLastOverrideAsPreviousAndRetainsImmutableRawSnapshot() {
        val prior = correction("CHECK_OUT", "NORMAL")
        val effective = source.copy(type = "CHECK_OUT", status = "EARLY_LEAVE")
        val draft = attendanceClassificationDraft(effective, source, prior, "CHECK_IN", "ON_TIME", "Sửa lại lần phân loại trước")
        assertEquals("CHECK_IN", draft.sourceType); assertEquals("ON_TIME", draft.sourceStatus)
        assertEquals("CHECK_OUT", draft.previousType); assertEquals("NORMAL", draft.previousStatus)
        assertEquals("CHECK_IN", draft.correctedType); assertEquals("ON_TIME", draft.correctedStatus)
    }

    @Test fun resolvedPendingScanMayBeConfirmedEvenWhenDisplayedTypeAlreadyMatchesChoice() {
        val pending = source.copy(type = "SCAN", status = "PENDING", resolutionStatus = "PENDING")
        val draft = attendanceClassificationDraft(pending.copy(type = "CHECK_IN", status = "ON_TIME", resolutionStatus = "ACCEPTED"),
            pending, null, "CHECK_IN", "ON_TIME", "Đã kiểm tra lượt quét")
        assertEquals("SCAN", draft.previousType); assertEquals("PENDING", draft.previousStatus)
        assertEquals("PENDING", draft.sourceResolutionStatus)
    }

    @Test fun discardedOverrideCanBeRestoredUsingTheOriginalEligibleSource() {
        val prior = correction("DISCARDED", "DISCARDED")
        val discarded = source.copy(type = "DISCARDED", status = "DISCARDED")
        val draft = attendanceClassificationDraft(discarded, source, prior, "CHECK_IN", "ON_TIME", "Khôi phục lượt chấm hợp lệ")
        assertEquals("DISCARDED", draft.previousType); assertEquals("CHECK_IN", draft.correctedType)
        assertTrue(attendanceResolutionPresentation(discarded).label.startsWith("Đã loại lượt quét"))
    }

    @Test fun noChangeBlankReasonAndMismatchedHistoryAreRejected() {
        rejects { attendanceClassificationDraft(source, source, null, "CHECK_IN", "ON_TIME", "Không đổi") }
        rejects { attendanceClassificationDraft(source, source, null, "CHECK_OUT", "NORMAL", " ") }
        rejects { attendanceClassificationDraft(source, source, null, "CHECK_IN", "DISCARDED", "Sai trạng thái") }
        rejects { attendanceClassificationDraft(source, source, correction("CHECK_OUT", "NORMAL").copy(attendanceId = "another"), "CHECK_IN", "LATE", "Sửa") }
        rejects { attendanceClassificationDraft(source.copy(employeeId = "other"), source, null, "CHECK_IN", "LATE", "Sửa") }
        rejects { attendanceClassificationDraft(source.copy(scheduleDate = "2026-02-30"), source, null, "CHECK_IN", "LATE", "Sửa") }
    }

    @Test fun initialChoicesUseFreshOverrideAndDoNotImplicitlyRestoreDiscardedRows() {
        val selection = initialAttendanceClassificationSelection(source, correction("CHECK_OUT", "NORMAL"))
        assertEquals("CHECK_OUT", selection.type); assertEquals("NORMAL", selection.status)
        val discarded = initialAttendanceClassificationSelection(source, correction("DISCARDED", "DISCARDED"))
        assertEquals("DISCARDED", discarded.type); assertEquals("DISCARDED", discarded.status)
    }

    private fun correction(type: String, status: String) = AttendanceClassificationOverride(
        attendanceId = source.id, sourceTimestamp = source.timestamp.toDate().toInstant(),
        correctedType = type, correctedStatus = status)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid correction to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
