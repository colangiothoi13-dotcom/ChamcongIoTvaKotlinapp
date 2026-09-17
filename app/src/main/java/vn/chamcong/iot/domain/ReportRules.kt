package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate
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
    adjustments: List<AttendanceAdjustment> = emptyList()
): List<AttendanceReportRow> {
    validateReportFilter(filter)
    val selectedEmployees = filterEmployees(employees, filter)
    val employeeIds = selectedEmployees.map { it.id }.toSet()
    val shiftsById = shifts.associateBy { it.id }
    val schedulesByKey = schedules.associateBy { "${it.employeeId}_${it.date}" }
    val attendanceByKey = assignAttendanceScheduleDates(attendance, schedules, shifts, zoneId)
        .filter { it.employeeId in employeeIds }
        .groupBy { row ->
            val date = row.scheduleDate ?: row.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate().toString()
            "${row.employeeId}_$date"
        }
    val approvedLeaveKeys = approvedRequests
        .filter { it.status == "APPROVED" && it.type == "LEAVE" }
        .flatMap { request ->
            val start = runCatching { LocalDate.parse(request.startDate, reportDateFormatter) }.getOrNull()
            val end = runCatching { LocalDate.parse(request.endDate, reportDateFormatter) }.getOrNull()
            if (start == null || end == null) emptyList() else selectedEmployees.filter { it.id == request.employeeId }.flatMap { employee ->
                generateSequence(start) { date -> date.plusDays(1).takeIf { !it.isAfter(end) } }
                    .filter { it in filter.startDate..filter.endDate }
                    .map { "${employee.id}_$it" }
                    .toList()
            }
        }
        .toSet()

    return selectedEmployees.flatMap { employee ->
        generateSequence(filter.startDate) { date -> date.plusDays(1).takeIf { !it.isAfter(filter.endDate) } }
            .map { date ->
                val key = "${employee.id}_$date"
                val schedule = schedulesByKey[key]
                val shift = schedule?.let { shiftsById[it.shiftId] }
                val rows = attendanceByKey[key].orEmpty()
                    .filter { belongsToScheduleDate(it, date, shift, zoneId) }
                val adjustment = latestAdjustment(adjustments, employee.id, date)
                if (rows.isEmpty() && schedule == null && adjustment == null && key !in approvedLeaveKeys) return@map null
                val summary = employeeDaySummary(
                    employee.id, date, rows, schedule, shift, key in approvedLeaveKeys, zoneId, adjustments
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
                    overtimeHours = summary.overtimeHours
                )
            }
            .filterNotNull()
            .toList()
    }.sortedWith(compareBy<AttendanceReportRow> { it.date }.thenBy { it.employeeName })
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
