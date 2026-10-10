package vn.chamcong.iot.model

import vn.chamcong.iot.domain.employeeMonthSummaries
import vn.chamcong.iot.domain.payrollHoursForMonth
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
    adjustments: List<AttendanceAdjustment> = emptyList(),
    leaveRequests: List<LeaveRequest> = emptyList()
): Double {
    val hours = employeeMonthSummaries(
        employeeId, month.atDay(1), attendance, schedules, shifts, emptySet(), zoneId, adjustments,
        leaveRequests = leaveRequests
    ).sumOf { it.workedHours + it.overtimeHours }
    return (hours * 100).roundToLong() / 100.0
}

/**
 * Payroll-aware overload. The legacy overload above intentionally remains the
 * main attendance calculation for older callers; both overloads sum disjoint
 * regular and overtime portions, and this overload has no overtime requests.
 */
fun workedHoursForMonth(
    attendance: List<Attendance>,
    employeeId: String,
    month: YearMonth,
    zoneId: ZoneId,
    schedules: List<WorkSchedule>,
    shifts: List<WorkShift>,
    adjustments: List<AttendanceAdjustment>,
    overtimeRequests: List<OvertimeRequest>,
    leaveRequests: List<LeaveRequest> = emptyList()
): Double = payrollHoursForMonth(
    employeeId = employeeId,
    month = month,
    attendance = attendance,
    schedules = schedules,
    shifts = shifts,
    overtimeRequests = overtimeRequests,
    adjustments = adjustments,
    zoneId = zoneId,
    leaveRequests = leaveRequests
)

fun payrollCandidates(
    employees: List<Employee>,
    payroll: List<Payroll>,
    month: String
): List<Employee> = employees.filter { employee ->
    val retirementMonth = employee.terminationLocalDate()?.let { YearMonth.from(it) }
        ?: if (!employee.active) YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")) else null
    val selectedMonth = runCatching { YearMonth.parse(month) }.getOrNull()
    val stillInEmploymentMonth = retirementMonth == null || selectedMonth == null || !selectedMonth.isAfter(retirementMonth)
    (employee.active || stillInEmploymentMonth) &&
        payroll.none { it.employeeId == employee.id && it.month == month }
}
