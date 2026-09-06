package vn.chamcong.iot.model

fun nextEmployeeCode(lastNumber: Long, existingCodes: List<String>): String {
    val maximum = existingCodes.mapNotNull { Regex("NV([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0
    val next = Math.addExact(maxOf(lastNumber, maximum), 1)
    return "NV" + next.toString().padStart(4, '0')
}

fun createPayroll(employee: Employee, month: String, bonus: Long, deduction: Long): Payroll {
    require(employee.id.isNotBlank()) { "Chưa chọn nhân viên" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)) { "Tháng phải có dạng yyyy-MM" }
    require(employee.baseSalary in 0..1000000000000L && bonus in 0..1000000000000L && deduction in 0..1000000000000L) { "Số tiền phải từ 0 đến 1.000 tỷ đồng" }
    return Payroll(employeeId=employee.id, employeeCode=employee.code, employeeName=employee.fullName,
        month=month, baseSalary=employee.baseSalary, bonus=bonus, deduction=deduction)
}
