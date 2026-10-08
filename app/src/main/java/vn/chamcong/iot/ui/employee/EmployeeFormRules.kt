package vn.chamcong.iot.ui.employee

import java.time.LocalDate

internal fun employeeRequestDateRange(start: String, end: String): Pair<LocalDate, LocalDate>? = runCatching {
    val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    require(datePattern.matches(start.trim()) && datePattern.matches(end.trim()))
    val first = LocalDate.parse(start.trim())
    val last = LocalDate.parse(end.trim())
    require(!last.isBefore(first))
    first to last
}.getOrNull()

internal fun employeePhoneError(phone: String): String? {
    val clean = phone.trim()
    return if (clean.length > 25 || clean.any { !it.isDigit() && it !in "+() -" })
        "Số điện thoại tối đa 25 ký tự, chỉ gồm chữ số và + ( ) -" else null
}

internal fun employeeAddressError(address: String): String? =
    if (address.trim().length > 200) "Địa chỉ tối đa 200 ký tự" else null

internal fun employeeSupportReasonError(reason: String): String? = when {
    reason.isBlank() -> "Vui lòng nhập nội dung cần hỗ trợ"
    reason.length > 4000 -> "Nội dung hỗ trợ tối đa 4.000 ký tự"
    else -> null
}
