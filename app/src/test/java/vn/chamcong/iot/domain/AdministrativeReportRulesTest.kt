package vn.chamcong.iot.domain

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import com.google.firebase.Timestamp
import java.util.Date

class AdministrativeReportRulesTest {
    @Test fun performanceExportUsesVietnamDeadlineMonthManagerApprovalTimeAndMatchingReviewPeriod() {
        fun stamp(value: String) = Timestamp(Date.from(Instant.parse(value)))
        val work = listOf(
            WorkItem(id = "late", assigneeId = "e1", status = "COMPLETED", deadline = stamp("2026-09-30T18:00:00Z"), completedAt = stamp("2026-09-30T19:00:00Z")),
            WorkItem(id = "on-time", assigneeId = "e1", status = "COMPLETED", deadline = stamp("2026-10-02T10:00:00Z"), completedAt = stamp("2026-10-02T09:00:00Z")),
            WorkItem(id = "open", assigneeId = "e1", status = "IN_PROGRESS", deadline = stamp("2026-10-03T10:00:00Z"), reworkCount = 2),
            WorkItem(id = "other-month", assigneeId = "e1", deadline = stamp("2026-10-31T18:00:00Z")),
            WorkItem(id = "archived", assigneeId = "old", assigneeName = "Nhân viên cũ", deadline = stamp("2026-10-04T10:00:00Z"))
        )
        val review = PerformanceReview(employeeId = "e1", period = "2026-10", score = 82.5, note = "Đã đánh giá")
        val rows = performanceReportRows(listOf(Employee(id = "e1", fullName = "An"), Employee(id = "empty", fullName = "Bình")), work,
            listOf(review.copy(period = "2026-09", score = 100.0), review), YearMonth.parse("2026-10"), Instant.parse("2026-10-10T00:00:00Z"))
        val own = rows.first { it.employeeId == "e1" }
        assertEquals(3, own.metrics.assigned); assertEquals(2, own.metrics.completed)
        assertEquals(1, own.metrics.completedOnTime); assertEquals(1, own.metrics.completedLate)
        assertEquals(1, own.metrics.overdue); assertEquals(2, own.metrics.reworkCount)
        assertEquals(0.5, own.metrics.onTimeCompletionRate, 0.0); assertEquals(review, own.review)
        assertEquals(0, rows.first { it.employeeId == "empty" }.metrics.completed)
        assertEquals("Nhân viên cũ", rows.first { it.employeeId == "old" }.employeeName)
    }
    @Test fun `summary groups dates per employee and distinguishes regular overtime partial leave and missing punches`() {
        val employees = listOf(Employee(id = "e1", code = "NV0001", fullName = "An"), Employee(id = "e2", code = "NV0002", fullName = "Bình"))
        val rows = listOf(
            AttendanceReportRow(date = "2026-10-01", employeeId = "e1", workedHours = 4.0, overtimeHours = 4.0, lateMinutes = 5, approvedLeaveShiftCount = 1),
            AttendanceReportRow(date = "2026-10-02", employeeId = "e1", workedHours = 3.5, earlyLeaveMinutes = 30),
            AttendanceReportRow(date = "2026-10-03", employeeId = "e1", status = "MISSING_CHECK_OUT"),
            AttendanceReportRow(date = "2026-10-04", employeeId = "e1", status = "LEAVE", overtimeHours = 4.0)
        )
        val result = workSummaryReportRows(rows, employees)
        assertEquals(2, result.size)
        val own = result.first()
        assertEquals(2, own.workdays); assertEquals(7.5, own.regularHours, 0.0); assertEquals(8.0, own.overtimeHours, 0.0)
        assertEquals(1, own.lateDays); assertEquals(1, own.earlyDays); assertEquals(2, own.leaveDays); assertEquals(1, own.missingDays)
        assertEquals(0, result.last().workdays)
    }

    @Test fun `saved payroll report filters month employee and case insensitive department without recalculation`() {
        val employees = listOf(Employee(id = "e1", department = "Kỹ thuật"), Employee(id = "e2", department = "Nhân sự"))
        val saved = listOf(Payroll(employeeId = "e1", month = "2026-10", hoursWorked = 2.0, hourlyRate = 25000, baseSalary = 50000, revision = 3),
            Payroll(employeeId = "e2", month = "2026-10", baseSalary = 80000), Payroll(employeeId = "e1", month = "2026-09", baseSalary = 40000))
        val filter = ReportFilter(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), department = " KỸ THUẬT ")
        assertEquals(listOf(saved.first()), filterPayrollReportRows(saved, employees, filter))
        assertEquals(listOf(saved[1]), filterPayrollReportRows(saved, employees, filter.copy(department = null, employeeId = "e2")))
        assertEquals(50000L, saved.first().baseSalary)
        assertThrows(IllegalArgumentException::class.java) { payrollReportMonth(filter.copy(endDate = LocalDate.parse("2026-11-01"))) }
    }
}
