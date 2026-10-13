package vn.chamcong.iot.domain

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import java.time.Instant

class RequestReviewNotesTest {
    private val pending = OvertimeRequest(id = "e1_2026-10-13", employeeId = "e1", employeeName = "An",
        workDate = "2026-10-13", startTime = "18:00", endTime = "22:00", status = "PENDING")

    @Test fun approvalNoteCanBeBlankAndNonblankNotesAreTrimmed() {
        assertEquals("", requestReviewNote(" \n ", required = false))
        assertEquals("Đã kiểm tra\nCa chiều", requestReviewNote("  Đã kiểm tra\nCa chiều  ", required = false))
        assertEquals("Cần sửa ca", requestReviewNote(" Cần sửa ca ", required = true))
    }

    @Test fun requiredReasonsAndSharedThousandCharacterLimitAreEnforced() {
        rejects { requestReviewNote(" \n ", required = true) }
        assertEquals(1000, requestReviewNote("x".repeat(1000), required = false).length)
        rejects { requestReviewNote("x".repeat(1001), required = false) }
        rejects { requestReviewNote("x".repeat(1001), required = true) }
    }

    @Test fun approvedOvertimeNoteIsRetainedInRecipientNotificationWithoutBecomingRejectionReason() {
        val reviewed = reviewOvertimeRequest(pending, OvertimeRequestStatus.APPROVED, "admin", "Quản lý",
            "  Hỗ trợ kiểm kê  ", Instant.parse("2026-10-13T01:00:00Z"))
        val notification = overtimeReviewNotification(reviewed, "  Hỗ trợ kiểm kê  ")
        assertNull(reviewed.rejectionReason)
        assertEquals("OVERTIME_RESULT", notification.type)
        assertEquals(pending.id, notification.referenceId); assertEquals("e1", notification.recipientEmployeeId)
        assertEquals("An", notification.audienceLabel)
        assertTrue(notification.body.endsWith("Hỗ trợ kiểm kê"))
        assertTrue(notification.body.contains("2026-10-13 18:00–22:00"))
        assertFalse(notification.read)
        assertEquals("PENDING", pending.status)
    }

    @Test fun approvalWithoutNoteStillProducesMeaningfulResultNotification() {
        val notification = overtimeReviewNotification(pending.copy(status = "APPROVED"), "")
        assertEquals("Tăng ca đã được duyệt", notification.title)
        assertTrue(notification.body.endsWith("Đã xử lý"))
    }

    @Test fun rejectionNotificationContainsRequiredReasonAndPendingRequestsCannotGenerateResult() {
        val rejected = pending.copy(status = "REJECTED", rejectionReason = "Không cần tăng ca")
        assertTrue(overtimeReviewNotification(rejected, "Không cần tăng ca").body.endsWith("Không cần tăng ca"))
        rejects { overtimeReviewNotification(rejected, " ") }
        rejects { overtimeReviewNotification(pending, "Chưa duyệt") }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid review note to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
