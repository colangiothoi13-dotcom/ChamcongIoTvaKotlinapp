package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reportDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val reportTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun validateReportFilter(filter: ReportFilter) {
    require(!filter.endDate.isBefore(filter.startDate)) { "Khoảng báo cáo không hợp lệ" }
}

fun filterEmployees(employees: List<Employee>, filter: ReportFilter): List<Employee> {
    validateReportFilter(filter)
    val employeeId = filter.employeeId?.trim()?.takeIf(String::isNotBlank)
    val department = filter.department?.trim()?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
    return employees.filter { employee ->
        (employeeId == null || employee.id == employeeId) &&
            (department == null || employee.department.trim().lowercase(Locale.ROOT) == department)
    }
}

fun attendanceReportRows(
    filter: ReportFilter,
    employees: List<Employee>,
    attendance: List<Attendance>,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    approvedRequests: List<LeaveRequest>,
    zoneId: ZoneId,
    adjustments: List<AttendanceAdjustment> = emptyList(),
    now: Instant = Instant.now(),
    overtimeRequests: List<OvertimeRequest> = emptyList()
): List<AttendanceReportRow> {
    validateReportFilter(filter)
    val selectedEmployees = filterEmployees(employees, filter)
    val employeeIds = selectedEmployees.map { it.id }.toSet()
    val schedulesByKey = schedules.associateBy { "${it.employeeId}_${it.date}" }
    val attendanceByKey = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
        .filter { it.employeeId in employeeIds }
        .groupBy { row ->
            val date = row.scheduleDate ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()
            "${row.employeeId}_$date"
        }
    return selectedEmployees.flatMap { employee ->
        generateSequence(filter.startDate) { date -> date.plusDays(1).takeIf { !it.isAfter(filter.endDate) } }
            .map { date ->
                val key = "${employee.id}_$date"
                val schedule = schedulesByKey[key]
                val leaveRequestsForDay = approvedRequests.filter { request ->
                    request.employeeId == employee.id && request.status == "APPROVED" && request.type == "LEAVE" &&
                        runCatching { date in LocalDate.parse(request.startDate)..LocalDate.parse(request.endDate) }
                            .getOrDefault(false)
                }
                val legacyLeave = leaveRequestsForDay.any { it.leaveShiftsByDate == null }
                val leaveShiftIds = approvedLeaveShiftIdsForDate(employee.id, date, schedule, shifts, leaveRequestsForDay)
                val hasApprovedLeave = legacyLeave || leaveShiftIds.isNotEmpty()
                val rows = attendanceByKey[key].orEmpty()
                val adjustment = latestAdjustment(adjustments, employee.id, date)
                val hasOvertimeRequest = overtimeRequests.any { it.employeeId == employee.id && it.workDate == date.toString() }
                if (rows.isEmpty() && schedule == null && adjustment == null && !hasApprovedLeave && !hasOvertimeRequest) return@map null
                val summary = employeeDaySummaryWithRequests(
                    employeeId = employee.id,
                    date = date,
                    attendance = rows,
                    schedule = schedule,
                    shifts = shifts,
                    approvedLeave = legacyLeave,
                    zoneId = zoneId,
                    adjustments = adjustments,
                    now = now,
                    approvedLeaveShiftIds = leaveShiftIds,
                    overtimeRequests = overtimeRequests
                )
                AttendanceReportRow(
                    date = date.toString(),
                    employeeId = employee.id,
                    employeeName = employee.fullName,
                    department = employee.department,
                    checkIn = summary.checkIn?.atZone(zoneId)?.format(reportTimeFormatter).orEmpty(),
                    checkOut = summary.checkOut?.atZone(zoneId)?.format(reportTimeFormatter).orEmpty(),
                    status = summary.status.name,
                    workedHours = summary.workedHours,
                    overtimeHours = summary.overtimeHours,
                    lateMinutes = summary.lateMinutes,
                    earlyLeaveMinutes = summary.earlyLeaveMinutes,
                    approvedLeaveShiftCount = leaveShiftIds.size
                )
            }
            .filterNotNull()
            .toList()
    }.sortedWith(compareBy<AttendanceReportRow> { it.date }.thenBy { it.employeeName })
}

/** One predicate for both the report preview and its CSV export. */
fun filterAttendanceReportRows(rows: List<AttendanceReportRow>, type: ReportType): List<AttendanceReportRow> =
    rows.filter { row ->
        when (type) {
            ReportType.LATE_EARLY -> row.lateMinutes > 0 || row.earlyLeaveMinutes > 0
            ReportType.LEAVE -> row.status == "LEAVE" || row.approvedLeaveShiftCount > 0
            ReportType.OVERTIME -> row.overtimeHours > 0
            else -> true
        }
    }

fun deviceActivityRows(
    devices: List<DeviceSnapshot>,
    failedCommands: List<Map<String, Any>>
): List<DeviceActivityRow> = devices.map { device ->
    DeviceActivityRow(
        deviceId = device.id,
        status = device.status,
        lastHeartbeat = device.lastHeartbeat?.toDate()?.toInstant()?.toString().orEmpty(),
        firmwareVersion = device.firmwareVersion,
        fingerprintCount = device.fingerprintCount,
        capacity = device.capacity,
        failedCommandCount = failedCommands.count {
            it["deviceId"] == device.id && it["status"] == "FAILED"
        }
    )
}.sortedBy { it.deviceId }
