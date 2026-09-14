package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.AttendanceType
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
    zoneId: ZoneId
): List<AttendanceReportRow> {
    validateReportFilter(filter)
    val selectedEmployees = filterEmployees(employees, filter)
    val employeeIds = selectedEmployees.map { it.id }.toSet()
    val shiftsById = shifts.associateBy { it.id }
    val schedulesByKey = schedules.associateBy { "${it.employeeId}_${it.date}" }
    val attendanceByKey = attendance
        .filter { it.employeeId in employeeIds }
        .groupBy { "${it.employeeId}_${it.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()}" }
    val approvedLeaveKeys = approvedRequests
        .filter { it.status == "APPROVED" && it.type == "LEAVE" }
        .flatMap { request ->
            val start = runCatching { LocalDate.parse(request.startDate, reportDateFormatter) }.getOrNull()
            val end = runCatching { LocalDate.parse(request.endDate, reportDateFormatter) }.getOrNull()
            if (start == null || end == null) emptyList() else selectedEmployees.flatMap { employee ->
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
                val rows = attendanceByKey[key].orEmpty().sortedBy { it.timestamp.toDate().time }
                val schedule = schedulesByKey[key]
                if (rows.isEmpty() && schedule == null && key !in approvedLeaveKeys) return@map null
                val checkIns = rows.filter { it.type == AttendanceType.CHECK_IN.name }
                val checkOuts = rows.filter { it.type == AttendanceType.CHECK_OUT.name }
                val firstIn = checkIns.firstOrNull()?.timestamp?.toDate()?.toInstant()
                val lastOut = checkOuts.lastOrNull()?.timestamp?.toDate()?.toInstant()
                val summary = calculateWorkTime(
                    checkIn = firstIn,
                    checkOut = lastOut,
                    shift = schedule?.let { shiftsById[it.shiftId] },
                    overtimeHours = schedule?.overtimeHours ?: 0,
                    zoneId = zoneId
                )
                val status = when {
                    key in approvedLeaveKeys -> "LEAVE"
                    rows.any { !it.verified || it.type !in listOf(AttendanceType.CHECK_IN.name, AttendanceType.CHECK_OUT.name) } -> "ABNORMAL"
                    firstIn == null -> "MISSING_CHECK_IN"
                    lastOut == null -> "MISSING_CHECK_OUT"
                    summary.lateMinutes > 0 -> "LATE"
                    summary.earlyLeaveMinutes > 0 -> "EARLY_LEAVE"
                    else -> "ON_TIME"
                }
                AttendanceReportRow(
                    date = date.toString(),
                    employeeId = employee.id,
                    employeeName = employee.fullName,
                    department = employee.department,
                    checkIn = firstIn?.atZone(zoneId)?.format(reportTimeFormatter).orEmpty(),
                    checkOut = lastOut?.atZone(zoneId)?.format(reportTimeFormatter).orEmpty(),
                    status = status,
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
