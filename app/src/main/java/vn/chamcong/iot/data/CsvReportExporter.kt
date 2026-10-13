package vn.chamcong.iot.data

import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.WorkSummaryReportRow
import vn.chamcong.iot.model.PerformanceReportRow
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.terminationLocalDate
import java.time.YearMonth

private const val BOM = "\uFEFF"

fun performanceRowsToCsv(rows: List<PerformanceReportRow>): String = buildString {
    append(BOM)
    appendLine("employeeCode,employeeName,assigned,completed,inProgress,pendingReview,completedOnTime,completedLate,overdue,reworked,reworkCount,onTimeCompletionRate,score,note")
    rows.forEach { row ->
        val m = row.metrics
        appendLine(listOf(row.employeeCode, row.employeeName, m.assigned, m.completed, m.inProgress, m.pendingReview,
            m.completedOnTime, m.completedLate, m.overdue, m.reworked, m.reworkCount,
            if (m.completed == 0) "" else m.onTimeCompletionRate * 100, row.review?.score ?: "", row.review?.note.orEmpty())
            .joinToString(",", transform = ::escapeCsv))
    }
}

/** Receives the same historical employee rows and day summaries displayed by the monthly screen. */
fun monthlyTimesheetRowsToCsv(month: YearMonth, rows: List<Pair<Employee, List<EmployeeDaySummary>>>): String = buildString {
    append(BOM)
    appendLine((listOf("employeeCode", "employeeName") + (1..month.lengthOfMonth()).map { it.toString().padStart(2, '0') } +
        listOf("regularHours", "overtimeHours")).joinToString(","))
    rows.forEach { (employee, summaries) ->
        val cutoff = employee.terminationLocalDate()
        val days = summaries.filter { YearMonth.from(it.date) == month && (cutoff == null || it.date <= cutoff) }
        val byDate = days.associateBy { it.date }
        val cells = (1..month.lengthOfMonth()).map { day ->
            val summary = byDate[month.atDay(day)]
            when {
                summary == null -> ""
                summary.status == EmployeeAttendanceStatus.LEAVE -> "P"
                summary.shiftSummaries.isNotEmpty() || summary.checkIn != null || summary.checkOut != null ||
                    summary.workedSeconds > 0 || summary.overtimeSeconds > 0 || summary.workedHours > 0 || summary.overtimeHours > 0 -> summary.workedHours + summary.overtimeHours
                else -> ""
            }
        }
        appendLine((listOf(employee.code, employee.fullName) + cells + listOf(days.sumOf { it.workedHours }, days.sumOf { it.overtimeHours }))
            .joinToString(",", transform = ::escapeCsv))
    }
}

fun workSummaryRowsToCsv(rows: List<WorkSummaryReportRow>): String = buildString {
    append(BOM)
    appendLine("employeeId,employeeCode,employeeName,department,workdays,regularHours,overtimeHours,lateDays,earlyDays,leaveDays,missingDays")
    rows.forEach { row -> appendLine(listOf(row.employeeId, row.employeeCode, row.employeeName, row.department,
        row.workdays, row.regularHours, row.overtimeHours, row.lateDays, row.earlyDays, row.leaveDays, row.missingDays).joinToString(",", transform = ::escapeCsv)) }
}

fun payrollRowsToCsv(rows: List<Payroll>): String = buildString {
    append(BOM)
    appendLine("employeeCode,employeeName,employeeId,month,hoursWorked,hourlyRate,baseSalary,bonus,deduction,netSalary,revision")
    rows.forEach { row -> appendLine(listOf(row.employeeCode, row.employeeName, row.employeeId, row.month,
        row.hoursWorked, row.hourlyRate, row.baseSalary, row.bonus, row.deduction, row.netSalary, row.revision).joinToString(",", transform = ::escapeCsv)) }
}

fun attendanceRowsToCsv(rows: List<AttendanceReportRow>): String = buildString {
    append(BOM)
    appendLine("date,employeeId,employeeName,department,checkIn,checkOut,status,workedHours,overtimeHours,lateMinutes,earlyLeaveMinutes,approvedLeaveShiftCount")
    rows.forEach { row ->
        appendLine(listOf(row.date, row.employeeId, row.employeeName, row.department, row.checkIn, row.checkOut, row.status, row.workedHours, row.overtimeHours, row.lateMinutes, row.earlyLeaveMinutes, row.approvedLeaveShiftCount).joinToString(",", transform = ::escapeCsv))
    }
}

fun deviceRowsToCsv(rows: List<DeviceActivityRow>): String = buildString {
    append(BOM)
    appendLine("deviceId,status,lastHeartbeat,firmwareVersion,fingerprintCount,capacity,failedCommandCount")
    rows.forEach { row ->
        appendLine(listOf(row.deviceId, row.status, row.lastHeartbeat, row.firmwareVersion, row.fingerprintCount ?: "", row.capacity ?: "", row.failedCommandCount).joinToString(",", transform = ::escapeCsv))
    }
}

private fun escapeCsv(value: Any?): String {
    val raw = value?.toString().orEmpty()
    val text = if (value is String && raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$raw" else raw
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${text.replace("\"", "\"\"")}\"" else text
}
