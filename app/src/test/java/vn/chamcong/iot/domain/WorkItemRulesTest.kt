package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.*
import java.time.Instant
import java.util.Date

class WorkItemRulesTest {
    private val start = at("2026-10-08T01:00:00Z")
    private val deadline = at("2026-10-08T09:00:00Z")
    private fun at(value: String) = Timestamp(Date.from(Instant.parse(value)))
    private fun item(status: String = "ASSIGNED") = WorkItem(title = "Kiểm tra máy A", description = "Kiểm tra an toàn",
        requiredResult = "Gửi biên bản", assigneeId = "e1", assigneeName = "Nguyễn Văn A", startAt = start,
        deadline = deadline, status = status, assignedById = "admin", assignedByName = "Quản lý")
    private fun draft(assigneeId: String = "e1", deadline: Timestamp = this.deadline) = WorkItemDraft(
        title = "Kiểm tra máy A", description = "Kiểm tra an toàn", requiredResult = "Gửi biên bản",
        assigneeId = assigneeId, assigneeName = "Nguyễn Văn A", startAt = start, deadline = deadline)
    private fun rejected(operation: () -> Unit) {
        try { operation(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun employeeSubmitsAndOnlyApprovalSetsCompletionTime() {
        val started = startWorkItem(item())
        assertEquals("IN_PROGRESS", started.status)
        val submitted = submitWorkItemResult(started, "Biên bản: máy hoạt động tốt")
        assertEquals("PENDING_REVIEW", submitted.status)
        assertNull(submitted.completedAt)
        val approval = at("2026-10-08T08:50:00Z")
        val completed = reviewWorkItemResult(submitted, true, "Đạt yêu cầu", approval)
        assertEquals("COMPLETED", completed.status)
        assertEquals(approval, completed.completedAt)
        assertFalse(wasWorkItemCompletedLate(completed))
        rejected { startWorkItem(completed) }
        rejected { submitWorkItemResult(completed, "Other") }
        rejected { reviewWorkItemResult(started, true, "", approval) }
    }

    @Test fun reworkPreservesEvidenceAndRequiresReasonBeforeResubmission() {
        val pending = item("PENDING_REVIEW").copy(resultReport = "Biên bản thiếu chữ ký")
        rejected { reviewWorkItemResult(pending, false, "  ", deadline) }
        val rework = reviewWorkItemResult(pending, false, "Bổ sung chữ ký", deadline)
        assertEquals("IN_PROGRESS", rework.status)
        assertEquals(1, rework.reworkCount)
        assertEquals(pending.resultReport, rework.resultReport)
        assertNull(rework.completedAt)
        val completed = reviewWorkItemResult(submitWorkItemResult(rework, "Biên bản đã ký"), true, "", deadline)
        assertEquals(1, completed.reworkCount)
        assertFalse(wasWorkItemCompletedLate(completed))
    }

    @Test fun reassignmentClearsPreviousReportAndDeadlineEditInvalidatesPendingApproval() {
        val pending = item("PENDING_REVIEW").copy(resultReport = "Kết quả của A", managerFeedback = "Cũ", reworkCount = 2)
        val moved = editWorkItem(pending, draft("e2"))
        assertEquals("ASSIGNED", moved.status)
        assertEquals("", moved.resultReport)
        assertEquals("", moved.managerFeedback)
        assertEquals(0, moved.reworkCount)
        val revised = editWorkItem(pending, draft(deadline = at("2026-10-09T09:00:00Z")))
        assertEquals("IN_PROGRESS", revised.status)
        assertEquals(pending.resultReport, revised.resultReport)
        assertEquals(2, revised.reworkCount)
        rejected { editWorkItem(item("COMPLETED").copy(completedAt = deadline), draft()) }
    }

    @Test fun staleVersionRejectsSecondConcurrentWriter() {
        val initial = item().copy(version = 2)
        requireWorkItemVersion(initial, 2)
        val firstWriter = initial.copy(version = 3)
        try { requireWorkItemVersion(firstWriter, 2); fail("Stale write accepted") }
        catch (_: WorkItemConflictException) { }
    }

    @Test fun overdueAndCompletedLateAreSeparateAndRateUsesCompletedOnly() {
        val now = Instant.parse("2026-10-08T10:00:00Z")
        val pending = item("PENDING_REVIEW").copy(resultReport = "Đã gửi")
        val onTime = item("COMPLETED").copy(completedAt = deadline)
        val late = item("COMPLETED").copy(completedAt = at("2026-10-08T09:01:00Z"), reworkCount = 2)
        assertTrue(isWorkItemOverdue(pending, now))
        assertFalse(isWorkItemOverdue(late, now))
        assertTrue(wasWorkItemCompletedLate(late))
        val metrics = workItemMetrics(listOf(pending, onTime, late, item("IN_PROGRESS")), now)
        assertEquals(4, metrics.assigned)
        assertEquals(2, metrics.completed)
        assertEquals(2, metrics.overdue)
        assertEquals(1, metrics.completedOnTime)
        assertEquals(1, metrics.completedLate)
        assertEquals(1, metrics.inProgress)
        assertEquals(1, metrics.pendingReview)
        assertEquals(1, metrics.reworked)
        assertEquals(2, metrics.reworkCount)
        assertEquals(0.5, metrics.onTimeCompletionRate, 0.0)
        assertEquals(0.0, workItemMetrics(emptyList(), now).onTimeCompletionRate, 0.0)
        assertFalse(isWorkItemOverdue(item(), deadline.toDate().toInstant()))
    }

    @Test fun rejectsMissingEvidenceInvalidWindowAndHalfLinkedShift() {
        rejected { validateWorkItemDraft(draft().copy(requiredResult = " ")) }
        rejected { validateWorkItemDraft(draft().copy(deadline = start)) }
        rejected { validateWorkItemDraft(draft().copy(relatedScheduleId = "e1_2026-10-08")) }
        rejected { submitWorkItemResult(item("IN_PROGRESS"), " ") }
        rejected { updateWorkItemProgress(item(), "Report") }
    }
}
