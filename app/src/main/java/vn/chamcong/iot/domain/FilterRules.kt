package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import java.util.Locale

fun filterEmployees(
    employees: List<Employee>,
    query: String,
    department: String?,
    includeRetired: Boolean
): List<Employee> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val normalizedDepartment = department?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    return employees.filter { employee ->
        (includeRetired || employee.active) &&
            (normalizedQuery.isBlank() || employee.code.lowercase(Locale.ROOT).contains(normalizedQuery) || employee.fullName.lowercase(Locale.ROOT).contains(normalizedQuery)) &&
            (normalizedDepartment == null || employee.department.lowercase(Locale.ROOT) == normalizedDepartment)
    }
}

fun filterAttendance(
    attendance: List<Attendance>,
    status: String?,
    type: String?
): List<Attendance> {
    val normalizedStatus = status?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    val normalizedType = type?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    return attendance.filter { row ->
        (normalizedStatus == null || row.status.lowercase(Locale.ROOT) == normalizedStatus) &&
            (normalizedType == null || row.type.lowercase(Locale.ROOT) == normalizedType)
    }
}
