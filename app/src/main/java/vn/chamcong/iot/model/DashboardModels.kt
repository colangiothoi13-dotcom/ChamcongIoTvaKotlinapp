package vn.chamcong.iot.model

import java.time.LocalDate

data class DashboardSummary(
    val weekStart: LocalDate = LocalDate.now(),
    val activeEmployees: Int = 0,
    val checkedEmployees: Int = 0,
    val lateEmployees: Int = 0,
    val unmarkedEmployees: Int = 0,
    val unresolvedPresenceEmployees: Int = 0,
    val weeklyAttendance: List<DailyAttendance> = emptyList()
)

data class DailyAttendance(
    val date: LocalDate,
    val count: Int
)
