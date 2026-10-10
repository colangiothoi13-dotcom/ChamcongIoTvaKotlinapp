package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import vn.chamcong.iot.model.*
import java.time.Instant

class WorkItemConflictException : IllegalStateException("Công việc đã được người khác cập nhật. Hãy tải lại trước khi lưu.")

fun validateWorkItemDraft(draft: WorkItemDraft) {
    require(draft.title.isNotBlank() && draft.title.length <= 160) { "Tên công việc phải có từ 1 đến 160 ký tự" }
    require(draft.description.isNotBlank() && draft.description.length <= 5000) { "Cần mô tả công việc (tối đa 5000 ký tự)" }
    require(draft.requiredResult.isNotBlank() && draft.requiredResult.length <= 5000) { "Cần yêu cầu kết quả (tối đa 5000 ký tự)" }
    require(draft.assigneeId.isNotBlank() && '/' !in draft.assigneeId && draft.assigneeName.isNotBlank()) { "Chưa chọn người thực hiện" }
    require(draft.deadline > draft.startAt) { "Hạn hoàn thành phải sau ngày bắt đầu" }
    require(draft.priority in WorkItemPriority.entries.map { it.name }) { "Mức ưu tiên không hợp lệ" }
    require((draft.relatedScheduleId == null) == (draft.relatedShiftId == null)) { "Cần chọn cả lịch phân ca và ca liên quan" }
    listOfNotNull(draft.relatedScheduleId, draft.relatedShiftId).forEach {
        require(it.isNotBlank() && '/' !in it) { "Mã ca liên quan không hợp lệ" }
    }
}

fun requireWorkItemVersion(item: WorkItem, expectedVersion: Long) {
    if (expectedVersion != item.version) throw WorkItemConflictException()
}

fun editWorkItem(item: WorkItem, draft: WorkItemDraft): WorkItem {
    validateWorkItemDraft(draft)
    require(item.status != WorkItemStatus.COMPLETED.name) { "Công việc đã hoàn thành không thể thay đổi" }
    val reassigned = item.assigneeId != draft.assigneeId
    return item.copy(
        title = draft.title.trim(), description = draft.description.trim(), requiredResult = draft.requiredResult.trim(),
        assigneeId = draft.assigneeId, assigneeName = draft.assigneeName.trim(), startAt = draft.startAt,
        deadline = draft.deadline, priority = draft.priority, relatedScheduleId = draft.relatedScheduleId,
        relatedShiftId = draft.relatedShiftId, relatedScheduleDate = draft.relatedScheduleDate,
        status = when { reassigned -> WorkItemStatus.ASSIGNED.name
            item.status == WorkItemStatus.PENDING_REVIEW.name -> WorkItemStatus.IN_PROGRESS.name
            else -> item.status },
        resultReport = if (reassigned) "" else item.resultReport,
        resultAttachments = if (reassigned) emptyList() else item.resultAttachments,
        managerFeedback = if (reassigned) "" else item.managerFeedback,
        reworkCount = if (reassigned) 0 else item.reworkCount
    )
}

fun startWorkItem(item: WorkItem): WorkItem {
    require(item.status == WorkItemStatus.ASSIGNED.name) { "Chỉ bắt đầu công việc đang được giao" }
    return item.copy(status = WorkItemStatus.IN_PROGRESS.name)
}

fun updateWorkItemProgress(item: WorkItem, report: String, attachments: List<WorkItemAttachment> = item.resultAttachments): WorkItem {
    require(item.status == WorkItemStatus.IN_PROGRESS.name) { "Chỉ cập nhật công việc đang thực hiện" }
    require(report.length <= 10000) { "Nội dung báo cáo tối đa 10000 ký tự" }
    validateWorkItemAttachments(attachments)
    require(attachments.all { it.assigneeId == item.assigneeId }) { "Tệp đính kèm không thuộc người thực hiện công việc" }
    return item.copy(resultReport = report.trim(), resultAttachments = attachments)
}

fun submitWorkItemResult(item: WorkItem, report: String, attachments: List<WorkItemAttachment> = item.resultAttachments): WorkItem {
    require(report.isNotBlank() || attachments.isNotEmpty()) { "Cần nhập kết quả hoặc đính kèm tệp trước khi gửi duyệt" }
    return updateWorkItemProgress(item, report, attachments).copy(status = WorkItemStatus.PENDING_REVIEW.name)
}

fun reviewWorkItemResult(item: WorkItem, approve: Boolean, feedback: String, now: Timestamp): WorkItem {
    require(item.status == WorkItemStatus.PENDING_REVIEW.name) { "Công việc chưa chờ duyệt hoặc đã được xử lý" }
    require(feedback.length <= 5000) { "Phản hồi tối đa 5000 ký tự" }
    require(approve || feedback.isNotBlank()) { "Cần nêu lý do yêu cầu làm lại" }
    require(item.resultReport.isNotBlank() || item.resultAttachments.isNotEmpty()) { "Không thể duyệt công việc chưa có kết quả" }
    validateWorkItemAttachments(item.resultAttachments)
    return item.copy(
        status = if (approve) WorkItemStatus.COMPLETED.name else WorkItemStatus.IN_PROGRESS.name,
        managerFeedback = feedback.trim(), completedAt = if (approve) now else null,
        reworkCount = item.reworkCount + if (approve) 0 else 1
    )
}

fun isWorkItemOverdue(item: WorkItem, now: Instant = Instant.now()): Boolean =
    item.status != WorkItemStatus.COMPLETED.name && item.deadline.toDate().toInstant() < now

fun wasWorkItemCompletedLate(item: WorkItem): Boolean = item.status == WorkItemStatus.COMPLETED.name &&
    item.completedAt?.let { it > item.deadline } == true

fun workItemMetrics(items: List<WorkItem>, now: Instant = Instant.now()): WorkItemMetrics {
    val completed = items.filter { it.status == WorkItemStatus.COMPLETED.name && it.completedAt != null }
    val late = completed.count(::wasWorkItemCompletedLate)
    val onTime = completed.size - late
    return WorkItemMetrics(
        assigned = items.size, completed = completed.size,
        inProgress = items.count { it.status == WorkItemStatus.IN_PROGRESS.name },
        pendingReview = items.count { it.status == WorkItemStatus.PENDING_REVIEW.name },
        completedOnTime = onTime, completedLate = late, reworked = items.count { it.reworkCount > 0 }, reworkCount = items.sumOf { it.reworkCount },
        overdue = items.count { isWorkItemOverdue(it, now) },
        onTimeCompletionRate = if (completed.isEmpty()) 0.0 else onTime.toDouble() / completed.size
    )
}
