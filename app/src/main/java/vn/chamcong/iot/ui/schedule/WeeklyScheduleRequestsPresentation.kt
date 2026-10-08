package vn.chamcong.iot.ui.schedule

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyScheduleRequestStatus
import vn.chamcong.iot.ui.MainUiState

internal data class WeeklyScheduleRequestsPresentation(
    val ready: Boolean,
    val requests: List<WeeklyScheduleRequest>,
    val pendingRequests: List<WeeklyScheduleRequest>,
    val reviewablePendingRequests: List<WeeklyScheduleRequest>,
    val missingEmployees: List<Employee>,
    val revisionCount: Int,
    val allActiveEmployeesSubmitted: Boolean
) {
    val showEmpty: Boolean get() = ready && requests.isEmpty()
}

/** Stored submissions remain visible even if their employee profile has not loaded. */
internal fun weeklyScheduleRequestsPresentation(state: MainUiState): WeeklyScheduleRequestsPresentation {
    val ready = state.weeklyScheduleRequestsReady
    val requests = if (ready) state.weeklyScheduleRequests
        .filter { it.weekStart == state.selectedWeekStart.toString() }
        .sortedWith(compareBy<WeeklyScheduleRequest> {
            it.status != WeeklyScheduleRequestStatus.PENDING
        }.thenBy { it.employeeName }) else emptyList()
    val activeEmployees = state.operationalEmployees.filter { it.active }
    val activeIds = activeEmployees.mapTo(mutableSetOf()) { it.id }
    val submittedIds = requests.mapTo(mutableSetOf()) { it.employeeId }
    val pending = requests.filter { it.status == WeeklyScheduleRequestStatus.PENDING }
    val missing = if (ready) activeEmployees.filter { it.id !in submittedIds } else emptyList()
    return WeeklyScheduleRequestsPresentation(
        ready = ready,
        requests = requests,
        pendingRequests = pending,
        reviewablePendingRequests = pending.filter { it.employeeId in activeIds },
        missingEmployees = missing,
        revisionCount = requests.count { it.status == WeeklyScheduleRequestStatus.NEEDS_REVISION },
        allActiveEmployeesSubmitted = ready && activeEmployees.isNotEmpty() && missing.isEmpty()
    )
}
