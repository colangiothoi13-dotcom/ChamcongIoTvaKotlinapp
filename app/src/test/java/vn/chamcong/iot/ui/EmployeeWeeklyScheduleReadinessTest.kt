package vn.chamcong.iot.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import java.time.LocalDate

class EmployeeWeeklyScheduleReadinessTest {
    private val target = LocalDate.of(2026, 10, 12)
    private val loaded = MainUiState(
        employeeWeeklyTargetWeekStart = target,
        employeeWeeklyScheduleRequestLoadedWeekStart = target
    )

    @Test fun missingRequestIsReadyOnlyAfterItsWeekWasReadSuccessfully() {
        assertFalse(MainUiState(employeeWeeklyTargetWeekStart = target).employeeWeeklyScheduleRequestReady)
        assertTrue(loaded.employeeWeeklyScheduleRequestReady)
    }

    @Test fun existingApprovedRequestCannotMakeLoadingOrFailedReadReady() {
        val approved = loaded.copy(employeeWeeklyScheduleRequest = WeeklyScheduleRequest(
            weekStart = target.toString(), status = WeeklyScheduleRequestStatus.APPROVED
        ))
        assertTrue(approved.employeeWeeklyScheduleRequestReady)
        assertFalse(approved.copy(employeeWeeklyScheduleRequestLoading = true).employeeWeeklyScheduleRequestReady)
        assertFalse(approved.copy(employeeWeeklyScheduleRequestError = "Permission denied").employeeWeeklyScheduleRequestReady)
    }

    @Test fun previousWeekSnapshotDoesNotUnlockNewWeekAtRollover() {
        assertFalse(loaded.copy(employeeWeeklyTargetWeekStart = target.plusWeeks(1)).employeeWeeklyScheduleRequestReady)
        assertFalse(loaded.copy(employeeWeeklyScheduleRequestLoadedWeekStart = null).employeeWeeklyScheduleRequestReady)
    }
}
