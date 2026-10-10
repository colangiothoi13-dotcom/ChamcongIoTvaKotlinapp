package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.WorkItem
import java.time.ZoneId

/** A deadline cohort: includes work started earlier, and completion approved after the period. */
fun filterWorkItemsForReport(
    items: List<WorkItem>, filter: ReportFilter, employees: List<Employee>,
    zone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
): List<WorkItem> {
    validateReportFilter(filter)
    val departments = employees.associate { it.id to it.department }
    return items.filter { item ->
        val date = item.deadline.toDate().toInstant().atZone(zone).toLocalDate()
        date >= filter.startDate && date <= filter.endDate &&
            (filter.employeeId == null || item.assigneeId == filter.employeeId) &&
            (filter.department == null || departments[item.assigneeId] == filter.department)
    }.sortedWith(compareBy({ it.deadline }, { it.id }))
}
