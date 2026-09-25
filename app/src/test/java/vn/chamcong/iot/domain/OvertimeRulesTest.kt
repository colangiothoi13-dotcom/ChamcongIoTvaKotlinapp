package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus
import java.time.Instant
import java.time.LocalDate

class OvertimeRulesTest {
    private val employee = Employee(
        id = "employee-1",
        code = "NV0001",
        fullName = "An",
        department = "Engineering"
    )

    @Test
    fun newRequestUsesFixedSupplementaryWindowAndPendingStatus() {
        val request = createOvertimeRequest(employee, LocalDate.of(2026, 9, 20))

        assertEquals("employee-1", request.employeeId)
        assertEquals("An", request.employeeName)
        assertEquals("Engineering", request.department)
        assertEquals("2026-09-20", request.workDate)
        assertEquals("18:00", request.startTime)
        assertEquals("22:00", request.endTime)
        assertEquals(OvertimeRequestStatus.PENDING.name, request.status)
        assertNull(request.reviewerId)
        assertNull(request.reviewedAt)
    }

    @Test
    fun validatesPersistedRequestFieldsAndReviewState() {
        val valid = createOvertimeRequest(employee, LocalDate.of(2026, 9, 20))

        listOf(
            valid.copy(startTime = "17:00"),
            valid.copy(endTime = "21:00"),
            valid.copy(status = "UNKNOWN"),
            valid.copy(employeeId = ""),
            valid.copy(workDate = ""),
            valid.copy(workDate = "2026-02-30"),
            valid.copy(status = OvertimeRequestStatus.REJECTED.name, rejectionReason = null)
        ).forEach(::assertInvalid)
    }

    @Test
    fun reviewsPendingRequestExactlyOnceAndRequiresARejectionReason() {
        val pending = createOvertimeRequest(employee, LocalDate.of(2026, 9, 20))
        val reviewedAt = Instant.parse("2026-09-19T08:00:00Z")

        val approved = reviewOvertimeRequest(
            pending,
            OvertimeRequestStatus.APPROVED,
            reviewerId = "admin-1",
            reviewerName = "Admin",
            reason = "ignored for approval",
            reviewedAt = reviewedAt
        )

        assertEquals(OvertimeRequestStatus.APPROVED.name, approved.status)
        assertEquals("admin-1", approved.reviewerId)
        assertEquals("Admin", approved.reviewerName)
        assertEquals(reviewedAt, approved.reviewedAt)
        assertNull(approved.rejectionReason)

        assertInvalid {
            reviewOvertimeRequest(
                pending,
                OvertimeRequestStatus.REJECTED,
                "admin-1",
                "Admin",
                "   ",
                reviewedAt
            )
        }
        assertInvalid {
            reviewOvertimeRequest(
                pending,
                OvertimeRequestStatus.PENDING,
                "admin-1",
                "Admin",
                "",
                reviewedAt
            )
        }
        assertInvalid {
            reviewOvertimeRequest(
                approved,
                OvertimeRequestStatus.REJECTED,
                "admin-1",
                "Admin",
                "Changed mind",
                reviewedAt.plusSeconds(1)
            )
        }
    }

    @Test
    fun rejectionRecordsTrimmedReasonAndCannotBeReviewedAgain() {
        val pending = createOvertimeRequest(employee, LocalDate.of(2026, 9, 20))
        val reviewedAt = Instant.parse("2026-09-19T08:00:00Z")
        val rejected = reviewOvertimeRequest(
            pending, OvertimeRequestStatus.REJECTED, "admin-1", "Admin", "  Not needed  ", reviewedAt
        )

        assertEquals(OvertimeRequestStatus.REJECTED.name, rejected.status)
        assertEquals("Not needed", rejected.rejectionReason)
        assertEquals("admin-1", rejected.reviewerId)
        assertEquals("Admin", rejected.reviewerName)
        assertEquals(reviewedAt, rejected.reviewedAt)
        assertInvalid {
            reviewOvertimeRequest(rejected, OvertimeRequestStatus.APPROVED, "admin-1", "Admin", "", reviewedAt)
        }
        for ((id, name) in listOf(" " to "Admin", "admin-1" to " ")) {
            assertInvalid {
                reviewOvertimeRequest(pending, OvertimeRequestStatus.APPROVED, id, name, "", reviewedAt)
            }
        }
    }

    @Test
    fun overtimeResolutionStatusesAreNotAcceptedAttendanceStates() {
        for (status in listOf(AttendanceResolutionStatus.OVERTIME_PENDING, AttendanceResolutionStatus.OVERTIME_REJECTED)) {
            assertFalse(isAcceptedAttendance(Attendance(resolutionStatus = status.name)))
        }
    }

    private fun assertInvalid(request: OvertimeRequest) = assertInvalid {
        validateOvertimeRequest(request)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
