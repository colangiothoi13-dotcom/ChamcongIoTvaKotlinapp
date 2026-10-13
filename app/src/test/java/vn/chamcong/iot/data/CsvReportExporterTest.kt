package vn.chamcong.iot.data

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.domain.workSummaryReportRows
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.EmployeeAttendanceStatus
import vn.chamcong.iot.model.PerformanceReview
import vn.chamcong.iot.model.PerformanceReportRow
import vn.chamcong.iot.model.WorkItemMetrics
import java.time.YearMonth
import java.time.LocalDate

class CsvReportExporterTest {
    @Test fun monthlyCsvUsesDisplayedHistoricalRowsLeapDaysLeaveAndRetirementCutoff() {
        val employee = Employee(id = "retired", code = "NV0001", fullName = "-An", active = false, terminationDate = "2024-02-02")
        val days = listOf(
            EmployeeDaySummary(LocalDate.parse("2024-02-01"), workedHours = 2.5, overtimeHours = 1.0),
            EmployeeDaySummary(LocalDate.parse("2024-02-02"), status = EmployeeAttendanceStatus.LEAVE),
            EmployeeDaySummary(LocalDate.parse("2024-02-03"), workedHours = 8.0, overtimeHours = 4.0),
            EmployeeDaySummary(LocalDate.parse("2024-01-31"), workedHours = 8.0)
        )
        val csv = monthlyTimesheetRowsToCsv(YearMonth.parse("2024-02"), listOf(employee to days))
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        val cells = lines[1].split(',')
        assertEquals(2, lines.size); assertEquals(33, cells.size)
        assertTrue(lines.first().contains(",28,29,regularHours,overtimeHours"))
        assertEquals("'-An", cells[1]); assertEquals("3.5", cells[2]); assertEquals("P", cells[3]); assertEquals("", cells[4])
        assertEquals("2.5", cells[31]); assertEquals("1.0", cells[32])
        assertEquals(8.0, days[2].workedHours, 0.0)
    }

    @Test fun performanceCsvIncludesOnlyCompletedWorkInRateAndKeepsMissingReviewAndRateBlank() {
        val metrics = WorkItemMetrics(assigned = 3, completed = 2, inProgress = 1, completedOnTime = 1, completedLate = 1,
            overdue = 1, reworked = 1, reworkCount = 2, onTimeCompletionRate = 0.5)
        val rows = listOf(PerformanceReportRow("e1", "NV1", "An", metrics, PerformanceReview("e1", "2026-10", 82.5, "=2")),
            PerformanceReportRow("e2", "NV2", "Bình", WorkItemMetrics(assigned = 1, pendingReview = 1)))
        val csv = performanceRowsToCsv(rows)
        assertTrue(csv.contains("NV1,An,3,2,1,0,1,1,1,1,2,50.0,82.5,'=2"))
        assertTrue(csv.contains("NV2,Bình,1,0,0,1,0,0,0,0,0,,,"))
        assertEquals(0.5, rows.first().metrics.onTimeCompletionRate, 0.0)
    }
    @Test fun savedPayrollCsvUsesSnapshotAmountsAndNeutralizesTextFormulas() {
        val saved = Payroll(employeeId = "e1", employeeCode = "NV0001", employeeName = " =HYPERLINK(\"https://example.com\")",
            month = "2026-10", hoursWorked = 4.0, hourlyRate = 25000, baseSalary = 100000, bonus = 0, deduction = 150000, revision = 2)
        val csv = payrollRowsToCsv(listOf(saved))
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("' =HYPERLINK")); assertTrue(csv.contains(",-50000,2"))
        assertEquals(-50000L, saved.netSalary)
    }

    @Test fun workSummaryCsvContainsOneAggregatePerEmployeeInsteadOfDayRows() {
        val days = listOf(AttendanceReportRow(date = "2026-10-01", employeeId = "e1", employeeName = "An", workedHours = 4.0),
            AttendanceReportRow(date = "2026-10-02", employeeId = "e1", employeeName = "An", workedHours = 3.0, overtimeHours = 4.0, lateMinutes = 10))
        val csv = workSummaryRowsToCsv(workSummaryReportRows(days))
        assertEquals(2, csv.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(csv.contains("workdays,regularHours,overtimeHours")); assertTrue(csv.contains("e1,,An,,2,7.0,4.0,1,0,0,0"))
    }
    @Test
    fun csvIncludesBomAndEscapesCommaQuotesAndNewlines() {
        val csv = attendanceRowsToCsv(
            listOf(
                AttendanceReportRow(
                    date = "2026-09-14",
                    employeeId = "e1",
                    employeeName = "An, \"A\"",
                    department = "Kinh doanh",
                    checkIn = "08:00",
                    checkOut = "17:00",
                    status = "LATE\nNEEDS REVIEW",
                    workedHours = 8.0,
                    overtimeHours = 0.0
                )
            )
        )

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"An, \"\"A\"\"\""))
        assertTrue(csv.contains("\"LATE\nNEEDS REVIEW\""))
    }
}
