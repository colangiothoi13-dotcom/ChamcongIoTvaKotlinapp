package vn.chamcong.iot.data

import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow

private const val BOM = "\uFEFF"

fun attendanceRowsToCsv(rows: List<AttendanceReportRow>): String = buildString {
    append(BOM)
    appendLine("date,employeeId,employeeName,department,checkIn,checkOut,status,workedHours,overtimeHours")
    rows.forEach { row ->
        appendLine(listOf(row.date, row.employeeId, row.employeeName, row.department, row.checkIn, row.checkOut, row.status, row.workedHours, row.overtimeHours).joinToString(",", transform = ::escapeCsv))
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
    val text = value?.toString().orEmpty()
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${text.replace("\"", "\"\"")}\"" else text
}
