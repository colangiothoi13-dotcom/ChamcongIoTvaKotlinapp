package vn.chamcong.iot.model

class PayrollConflictException : IllegalStateException(
    "Phiếu lương hoặc đơn giá đã thay đổi. Hãy tải lại và kiểm tra trước khi tính lại."
)

/** A preview changes only payable values. Saved identity and the previous snapshot stay intact. */
fun previewPayrollRecalculation(
    previous: Payroll,
    hoursWorked: Double,
    hourlyRate: Long,
    bonus: Long = previous.bonus,
    deduction: Long = previous.deduction
): Payroll {
    require(previous.employeeId.isNotBlank() && '/' !in previous.employeeId) { "Mã nhân viên không hợp lệ" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(previous.month)) { "Tháng phải có dạng yyyy-MM" }
    require(previous.revision >= 0) { "Phiên bản phiếu lương không hợp lệ" }
    require(bonus in 0..1000000000000L && deduction in 0..1000000000000L) {
        "Số tiền phải từ 0 đến 1.000 tỷ đồng"
    }
    return previous.copy(
        hoursWorked = hoursWorked, hourlyRate = hourlyRate,
        baseSalary = calculateBasePay(hourlyRate, hoursWorked), bonus = bonus, deduction = deduction,
        revision = Math.addExact(previous.revision, 1L)
    )
}

fun requirePayrollPreviewCurrent(previous: Payroll, current: Payroll, hourlyRate: Long, currentHourlyRate: Long) {
    if (current != previous || hourlyRate != currentHourlyRate) throw PayrollConflictException()
}

fun validatePayrollRecalculationReason(reason: String): String = reason.trim().also {
    require(it.isNotBlank() && it.length <= 500) { "Cần nhập lý do tính lại phiếu lương (tối đa 500 ký tự)" }
}
