package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType

class RequestRulesTest {
    private val pending = LeaveRequest(
        id = "r1",
        employeeId = "e1",
        employeeName = "An",
        type = RequestType.LEAVE.name,
        startDate = "2026-09-14",
        endDate = "2026-09-15",
        reason = "Nghỉ ốm"
    )

    @Test
    fun approvalStoresReviewerAndTimestamp() {
        val reviewedAt = Timestamp.now()
        val result = reviewRequest(pending, RequestStatus.APPROVED, "admin-1", "Admin", "Đã duyệt", reviewedAt)

        assertEquals(RequestStatus.APPROVED.name, result.status)
        assertEquals("admin-1", result.reviewerId)
        assertEquals("Admin", result.reviewerName)
        assertEquals(reviewedAt, result.reviewedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectionRequiresAReason() {
        reviewRequest(pending, RequestStatus.REJECTED, "admin-1", "Admin", "", Timestamp.now())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDateRangeIsRejected() {
        validateRequest(pending.copy(startDate = "2026-09-16", endDate = "2026-09-15"))
    }

    @Test
    fun newRequestProducesAnInAppNotification() {
        val notification = notificationForRequest(pending)

        assertEquals("REQUEST", notification.type)
        assertEquals("r1", notification.referenceId)
    }
}
