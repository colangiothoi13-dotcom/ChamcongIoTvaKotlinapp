package vn.chamcong.iot.ui.reports

import vn.chamcong.iot.model.Employee

/** Report queries use document IDs; the form also accepts the employee's displayed code. */
internal fun resolveReportEmployeeId(input: String, employees: List<Employee>): String? {
    val value = input.trim().takeIf(String::isNotBlank) ?: return null
    val knownId = employees.firstOrNull { it.id == value }?.id
        ?: employees.firstOrNull { it.code.equals(value, ignoreCase = true) }?.id
    if (knownId != null) return knownId
    require(!value.matches(Regex("NV\\d+", RegexOption.IGNORE_CASE))) {
        "Không tìm thấy mã nhân viên $value. Kiểm tra mã và dữ liệu hồ sơ nhân viên."
    }
    return value
}
