package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.data.workItemsToCsv
import vn.chamcong.iot.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.Date

class WorkItemReportRulesTest {
    private fun time(value: String) = Timestamp(Date.from(Instant.parse(value)))

    @Test fun deadlineCohortUsesVietnamDateAndIncludesEarlierStartsAndLaterApproval() {
        val item = WorkItem(id = "job", assigneeId = "NV1", startAt = time("2026-09-01T01:00:00Z"),
            deadline = time("2026-09-30T17:00:00Z"), status = "COMPLETED",
            completedAt = time("2026-11-01T00:00:00Z"))
        val filter = ReportFilter(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), department = "Kỹ thuật")
        val employees = listOf(Employee(id = "NV1", department = "Kỹ thuật"))
        assertEquals(listOf(item), filterWorkItemsForReport(listOf(item), filter, employees))
        assertTrue(filterWorkItemsForReport(listOf(item), filter.copy(employeeId = "NV2"), employees).isEmpty())
        assertTrue(filterWorkItemsForReport(listOf(item), filter.copy(department = "Kho"), employees).isEmpty())
        assertTrue(filterWorkItemsForReport(listOf(item.copy(deadline = time("2026-09-30T16:59:59Z"))), filter, employees).isEmpty())
    }

    @Test fun exportKeepsMultilineEvidenceAndDistinguishesPendingOverdueFromCompletedLate() {
        val deadline = time("2026-10-01T09:00:00Z")
        val pending = WorkItem(id = "pending", title = "Máy A, khu 1", deadline = deadline,
            resultReport = "Biên bản \"A\"\nĐã kiểm tra", status = "PENDING_REVIEW")
        val completed = pending.copy(id = "done", status = "COMPLETED", completedAt = time("2026-10-01T10:00:00Z"))
        val csv = workItemsToCsv(listOf(pending, completed), Instant.parse("2026-10-01T11:00:00Z"))
        assertTrue(csv.startsWith("\uFEFFid,title"))
        assertTrue(csv.contains("\"Máy A, khu 1\""))
        assertTrue(csv.contains("\"Biên bản \"\"A\"\"\nĐã kiểm tra\""))
        assertTrue(csv.contains(",true,false,0,1"))
        assertTrue(csv.contains(",false,true,0,1"))
    }

    @Test fun spreadsheetDoesNotEvaluateEmployeeReportOrManagerFeedbackAsFormula() {
        val csv = workItemsToCsv(listOf(WorkItem(id = "job", resultReport = "=1+1", managerFeedback = "  @SUM(1)")))
        assertTrue(csv.contains(",'=1+1,'  @SUM(1),"))
    }
}
