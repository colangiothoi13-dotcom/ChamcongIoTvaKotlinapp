package vn.chamcong.iot.model

import vn.chamcong.iot.domain.employeeMonthSummaries
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

fun nextEmployeeCode(lastNumber: Long, existingCodes: List<String>): String {
    val maximum = existingCodes.mapNotNull { Regex("NV([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0
    val next = Math.addExact(maxOf(lastNumber, maximum), 1)
    return "NV" + next.toString().padStart(4, '0')
}

fun calculateBasePay(hourlyRate: Long, hoursWorked: Double): Long {
    require(hourlyRate in 0..1000000000000L) { "Đơn giá giờ phải từ 0 đến 1.000 tỷ đồng" }
    require(hoursWorked.isFinite() && hoursWorked in 0.0..744.0) { "Số giờ làm phải từ 0 đến 744 giờ" }
    return (hourlyRate.toDouble() * hoursWorked).roundToLong()
}

fun createPayroll(employee: Employee, month: String, hoursWorked: Double, bonus: Long, deduction: Long): Payroll {
    require(employee.id.isNotBlank()) { "Chưa chọn nhân viên" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)) { "Tháng phải có dạng yyyy-MM" }
    require(employee.baseSalary in 0..1000000000000L && bonus in 0..1000000000000L && deduction in 0..1000000000000L) { "Số tiền phải từ 0 đến 1.000 tỷ đồng" }
    return Payroll(
        employeeId = employee.id,
        employeeCode = employee.code,
        employeeName = employee.fullName,
        month = month,
        baseSalary = calculateBasePay(employee.baseSalary, hoursWorked),
        hourlyRate = employee.baseSalary,
        hoursWorked = hoursWorked,
        bonus = bonus,
        deduction = deduction
    )
}

fun workedHoursForMonth(
    attendance: List<Attendance>,
    employeeId: String,
    month: YearMonth,
    zoneId: ZoneId,
    schedules: List<WorkSchedule> = emptyList(),
    shifts: List<WorkShift> = emptyList(),
    adjustments: List<AttendanceAdjustment> = emptyList()
): Double {
    val hours = employeeMonthSummaries(
        employeeId, month.atDay(1), attendance, schedules, shifts, emptySet(), zoneId, adjustments
    ).sumOf { it.workedHours }
    return (hours * 100).roundToLong() / 100.0
}

fun payrollCandidates(
    employees: List<Employee>,
    payroll: List<Payroll>,
    month: String
): List<Employee> = employees.filter { employee ->
    employee.active || payroll.none { it.employeeId == employee.id && it.month == month }
}
