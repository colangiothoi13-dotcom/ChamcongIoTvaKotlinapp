package vn.chamcong.iot.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.ui.MainUiState
import java.time.LocalDate

class WeeklyScheduleRequestsPresentationTest {
    private val week = LocalDate.parse("2026-10-12")
    private fun request(employeeId: String, status: WeeklyScheduleRequestStatus = WeeklyScheduleRequestStatus.PENDING) =
        WeeklyScheduleRequest(id = "${employeeId}_$week", employeeId = employeeId,
            employeeName = employeeId, weekStart = week.toString(), status = status)

    private fun loadedState() = MainUiState(
        selectedWeekStart = week,
        weeklyScheduleRequestsLoadedWeekStart = week,
        weeklyScheduleRequests = listOf(request("active")),
        employees = listOf(Employee(id = "active"), Employee(id = "missing"))
    )

    @Test fun pendingSubmissionRemainsVisibleBeforeEmployeeProfilesLoad() {
        val presentation = weeklyScheduleRequestsPresentation(loadedState().copy(employees = emptyList()))
        assertEquals(listOf("active"), presentation.requests.map { it.employeeId })
        assertEquals(1, presentation.pendingRequests.size)
        assertTrue(presentation.reviewablePendingRequests.isEmpty())
        assertFalse(presentation.showEmpty)
        assertFalse(presentation.allActiveEmployeesSubmitted)
    }

    @Test fun retiredAndUnknownEmployeesKeepTheirStoredSubmissionsWithoutApprovalActions() {
        val presentation = weeklyScheduleRequestsPresentation(loadedState().copy(
            employees = listOf(Employee(id = "retired", active = false, terminationDate = "2020-01-01")),
            weeklyScheduleRequests = listOf(request("retired"), request("unknown"))
        ))
        assertEquals(setOf("retired", "unknown"), presentation.requests.map { it.employeeId }.toSet())
        assertEquals(2, presentation.pendingRequests.size)
        assertTrue(presentation.reviewablePendingRequests.isEmpty())
        assertFalse(presentation.showEmpty)
    }

    @Test fun onlySelectedWeekContributesToStatusCountsAndMissingEmployees() {
        val presentation = weeklyScheduleRequestsPresentation(loadedState().copy(
            employees = listOf(Employee(id = "active"), Employee(id = "approved"),
                Employee(id = "revision"), Employee(id = "missing")),
            weeklyScheduleRequests = listOf(request("active"),
                request("approved", WeeklyScheduleRequestStatus.APPROVED),
                request("revision", WeeklyScheduleRequestStatus.NEEDS_REVISION),
                request("missing").copy(weekStart = week.minusWeeks(1).toString()))
        ))
        assertEquals(3, presentation.requests.size)
        assertEquals(listOf("active"), presentation.reviewablePendingRequests.map { it.employeeId })
        assertEquals(1, presentation.revisionCount)
        assertEquals(listOf("missing"), presentation.missingEmployees.map { it.id })
    }

    @Test fun loadingErrorAndWrongLoadedWeekNeverClaimEmptyOrMissing() {
        val base = loadedState().copy(weeklyScheduleRequests = emptyList())
        val unavailable = listOf(base.copy(weeklyScheduleRequestsLoading = true),
            base.copy(weeklyScheduleRequestsError = "Không tải được đăng ký"),
            base.copy(weeklyScheduleRequestsLoadedWeekStart = week.minusWeeks(1)),
            base.copy(weeklyScheduleRequestsLoadedWeekStart = null))
        unavailable.forEach { state ->
            val presentation = weeklyScheduleRequestsPresentation(state)
            assertFalse(presentation.ready)
            assertFalse(presentation.showEmpty)
            assertTrue(presentation.missingEmployees.isEmpty())
            assertTrue(presentation.reviewablePendingRequests.isEmpty())
            assertFalse(presentation.allActiveEmployeesSubmitted)
        }
    }

    @Test fun staleRowsCannotEnableApprovalWhileAnotherWeekLoads() {
        val presentation = weeklyScheduleRequestsPresentation(loadedState().copy(
            weeklyScheduleRequestsLoading = true))
        assertTrue(presentation.requests.isEmpty())
        assertTrue(presentation.pendingRequests.isEmpty())
        assertTrue(presentation.reviewablePendingRequests.isEmpty())
    }

    @Test fun emptyAndCompleteRegistrationMessagesRequireSuccessfulCurrentWeekSnapshot() {
        val empty = weeklyScheduleRequestsPresentation(loadedState().copy(weeklyScheduleRequests = emptyList()))
        assertTrue(empty.showEmpty)
        assertEquals(setOf("active", "missing"), empty.missingEmployees.map { it.id }.toSet())
        val complete = weeklyScheduleRequestsPresentation(loadedState().copy(
            weeklyScheduleRequests = listOf(request("active"), request("missing"))))
        assertFalse(complete.showEmpty)
        assertTrue(complete.allActiveEmployeesSubmitted)
        assertTrue(complete.missingEmployees.isEmpty())
    }
}
