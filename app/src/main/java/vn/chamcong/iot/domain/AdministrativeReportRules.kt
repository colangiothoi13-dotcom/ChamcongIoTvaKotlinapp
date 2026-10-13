package vn.chamcong.iot.domain

import vn.chamcong.iot.model.*
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

fun performanceReportRows(employees: List<Employee>, workItems: List<WorkItem>, reviews: List<PerformanceReview>,
    month: YearMonth, now: Instant = Instant.now(), employeeCatalog: List<Employee> = employees): List<PerformanceReportRow> {
    val tasks = workItems.filter { YearMonth.from(it.deadline.toDate().toInstant().atZone(ZoneId.of("Asia/Ho_Chi_Minh"))) == month }
    val grouped = tasks.groupBy { it.assigneeId }
    return (employees.map { it.id } + grouped.keys).distinct().map { employeeId ->
        val employee = employees.firstOrNull { it.id == employeeId } ?: employeeCatalog.firstOrNull { it.id == employeeId }
        PerformanceReportRow(employeeId, employee?.code.orEmpty(), employee?.fullName?.ifBlank { employeeId }
            ?: grouped[employeeId]?.firstOrNull()?.assigneeName?.ifBlank { employeeId } ?: employeeId,
            workItemMetrics(grouped[employeeId].orEmpty(), now),
            reviews.firstOrNull { it.employeeId == employeeId && it.period == month.toString() })
    }
}

fun workSummaryReportRows(rows: List<AttendanceReportRow>, employees: List<Employee> = emptyList()): List<WorkSummaryReportRow> {
    val byEmployee = rows.groupBy { it.employeeId }
    return (employees.map { it.id } + byEmployee.keys).distinct().map { id ->
        val employee = employees.firstOrNull { it.id == id }
        val own = byEmployee[id].orEmpty().distinctBy { it.date }
        WorkSummaryReportRow(id, employee?.code.orEmpty(), employee?.fullName ?: own.firstOrNull()?.employeeName ?: id,
            employee?.department ?: own.firstOrNull()?.department.orEmpty(),
            own.count { it.workedHours > 0 }, own.sumOf { it.workedHours }, own.sumOf { it.overtimeHours },
            own.count { it.lateMinutes > 0 }, own.count { it.earlyLeaveMinutes > 0 },
            own.count { it.status == "LEAVE" || it.approvedLeaveShiftCount > 0 },
            own.count { it.status in setOf("MISSING_CHECK_IN", "MISSING_CHECK_OUT") })
    }.sortedWith(compareBy({ it.employeeCode }, { it.employeeName }, { it.employeeId }))
}

fun payrollReportMonth(filter: ReportFilter): YearMonth {
    validateReportFilter(filter)
    val month = YearMonth.from(filter.startDate)
    require(YearMonth.from(filter.endDate) == month) { "Báo cáo lương cần chọn khoảng ngày trong cùng một tháng" }
    return month
}

fun filterPayrollReportRows(payroll: List<Payroll>, employees: List<Employee>, filter: ReportFilter): List<Payroll> {
    val month = payrollReportMonth(filter).toString()
    val department = filter.department?.trim()?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
    return payroll.filter { slip ->
        slip.month == month && (filter.employeeId.isNullOrBlank() || slip.employeeId == filter.employeeId) &&
            (department == null || employees.firstOrNull { it.id == slip.employeeId }?.department?.trim()?.lowercase(Locale.ROOT) == department)
    }.sortedWith(compareBy({ it.employeeCode }, { it.employeeName }, { it.employeeId }))
}
