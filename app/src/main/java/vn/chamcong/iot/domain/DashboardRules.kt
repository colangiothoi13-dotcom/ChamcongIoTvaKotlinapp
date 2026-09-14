package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DailyAttendance
import vn.chamcong.iot.model.Employee
import java.time.LocalDate
import java.time.ZoneId

fun summarizeDashboard(
    employees: List<Employee>,
    attendance: List<Attendance>,
    weekStart: LocalDate,
    zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
): DashboardSummary {
    val monday = mondayOfWeek(weekStart)
    val dates = weekDates(monday)
    val activeIds = employees.asSequence()
        .filter(Employee::active)
        .map(Employee::id)
        .filter(String::isNotBlank)
        .toSet()
    val weekRows = attendance.filter { it.localDate(zoneId) in dates }
    val checkedIds = weekRows.asSequence()
        .map(Attendance::employeeId)
        .filter(activeIds::contains)
        .toSet()
    val lateIds = weekRows.asSequence()
        .filter { it.status.equals("LATE", ignoreCase = true) }
        .map(Attendance::employeeId)
        .filter(activeIds::contains)
        .toSet()
    val latestByEmployee = weekRows.asSequence()
        .filter { it.employeeId in activeIds }
        .groupBy(Attendance::employeeId)
        .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<Attendance> { it.timestamp.seconds }.thenBy { it.timestamp.nanoseconds }) }
    val unresolved = latestByEmployee.values.count { it?.type.equals("CHECK_IN", ignoreCase = true) }
    val weeklyAttendance = dates.map { date ->
        DailyAttendance(date = date, count = attendance.count { it.localDate(zoneId) == date })
    }

    return DashboardSummary(
        weekStart = monday,
        activeEmployees = activeIds.size,
        checkedEmployees = checkedIds.size,
        lateEmployees = lateIds.size,
        unmarkedEmployees = (activeIds.size - checkedIds.size).coerceAtLeast(0),
        unresolvedPresenceEmployees = unresolved,
        weeklyAttendance = weeklyAttendance
    )
}

private fun Attendance.localDate(zoneId: ZoneId): LocalDate = timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()
