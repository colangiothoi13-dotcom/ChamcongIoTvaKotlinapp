package vn.chamcong.iot.model

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PayrollRulesTest {
    @Test
    fun calculatesBasePayFromHourlyRateAndWorkedHours() {
        val payroll = createPayroll(
            Employee(id = "e1", code = "NV0001", fullName = "An", baseSalary = 50_000),
            month = "2026-09",
            hoursWorked = 8.5,
            bonus = 100_000,
            deduction = 25_000
        )

        assertEquals(425_000L, payroll.baseSalary)
        assertEquals(50_000L, payroll.hourlyRate)
        assertEquals(8.5, payroll.hoursWorked, 0.001)
        assertEquals(500_000L, payroll.netSalary)
    }

    @Test
    fun calculatesWorkedHoursFromCheckInAndCheckOutPairsInMonth() {
        val attendance = listOf(
            attendance("e1", "CHECK_IN", "2026-09-10T08:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-09-10T17:30:00+07:00"),
            attendance("e1", "CHECK_IN", "2026-10-01T08:00:00+07:00"),
            attendance("e1", "CHECK_OUT", "2026-10-01T12:00:00+07:00")
        )

        assertEquals(
            9.5,
            workedHoursForMonth(attendance, "e1", YearMonth.of(2026, 9), ZoneId.of("Asia/Ho_Chi_Minh")),
            0.001
        )
    }

    @Test
    fun hidesRetiredEmployeeAfterPayrollExistsForSelectedMonth() {
        val employees = listOf(
            Employee(id = "active", fullName = "Đang làm"),
            Employee(id = "retired", fullName = "Đã nghỉ", active = false)
        )
        val payroll = listOf(Payroll(employeeId = "retired", month = "2026-09"))

        val candidates = payrollCandidates(employees, payroll, "2026-09")

        assertTrue(candidates.any { it.id == "active" })
        assertTrue(candidates.none { it.id == "retired" })
    }

    @Test
    fun keepsRetiredEmployeeAvailableUntilTheirMonthIsSettled() {
        val retired = Employee(id = "retired", fullName = "Đã nghỉ", active = false)

        assertTrue(payrollCandidates(listOf(retired), emptyList(), "2026-09").contains(retired))
    }

    private fun attendance(employeeId: String, type: String, localTime: String): Attendance =
        Attendance(
            employeeId = employeeId,
            type = type,
            timestamp = Timestamp(Date.from(ZonedDateTime.parse(localTime).toInstant()))
        )
}
