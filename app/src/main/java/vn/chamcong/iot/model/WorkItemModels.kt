package vn.chamcong.iot.model

import com.google.firebase.Timestamp

/** Completion is approved by a manager; attendance never changes this state. */
enum class WorkItemStatus { ASSIGNED, IN_PROGRESS, PENDING_REVIEW, COMPLETED }
enum class WorkItemPriority { LOW, NORMAL, HIGH, URGENT }
enum class WorkItemAction { CREATED, EDITED, STARTED, PROGRESS_UPDATED, RESULT_SUBMITTED, APPROVED, REWORK_REQUESTED }

/** File content is stored separately; task versions retain immutable attachment metadata. */
data class WorkItemAttachment(
    val id: String = "",
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val chunkCount: Int = 0,
    val uploadedById: String = "",
    val assigneeId: String = "",
    val createdAt: Timestamp? = null
)

data class WorkItem(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val requiredResult: String = "",
    val assignedById: String = "",
    val assignedByName: String = "",
    val assigneeId: String = "",
    val assigneeName: String = "",
    val startAt: Timestamp = Timestamp.now(),
    val deadline: Timestamp = Timestamp.now(),
    val priority: String = WorkItemPriority.NORMAL.name,
    val status: String = WorkItemStatus.ASSIGNED.name,
    val completedAt: Timestamp? = null,
    val resultReport: String = "",
    val resultAttachments: List<WorkItemAttachment> = emptyList(),
    val managerFeedback: String = "",
    /** workSchedules document ID and selected shift ID (a schedule may contain two shifts). */
    val relatedScheduleId: String? = null,
    val relatedShiftId: String? = null,
    val relatedScheduleDate: String? = null,
    val version: Long = 1L,
    val reworkCount: Int = 0,
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now(),
    val lastUpdatedById: String = "",
    val lastUpdatedByName: String = ""
)

data class WorkItemDraft(
    val title: String = "",
    val description: String = "",
    val requiredResult: String = "",
    val assigneeId: String = "",
    val assigneeName: String = "",
    val startAt: Timestamp = Timestamp.now(),
    val deadline: Timestamp = Timestamp.now(),
    val priority: String = WorkItemPriority.NORMAL.name,
    val relatedScheduleId: String? = null,
    val relatedShiftId: String? = null,
    val relatedScheduleDate: String? = null
)

/** Every version stores both snapshots in the same transaction as the work item. */
data class WorkItemHistory(
    val id: String = "",
    val workItemId: String = "",
    val version: Long = 1L,
    val action: String = WorkItemAction.CREATED.name,
    val actorId: String = "",
    val actorName: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val before: WorkItem? = null,
    val after: WorkItem = WorkItem()
)

data class WorkItemMetrics(
    val assigned: Int = 0,
    val completed: Int = 0,
    val inProgress: Int = 0,
    val pendingReview: Int = 0,
    val completedOnTime: Int = 0,
    val completedLate: Int = 0,
    val reworked: Int = 0,
    val reworkCount: Int = 0,
    val overdue: Int = 0,
    /** Fraction in [0, 1], using completed work only as the denominator. */
    val onTimeCompletionRate: Double = 0.0
)
