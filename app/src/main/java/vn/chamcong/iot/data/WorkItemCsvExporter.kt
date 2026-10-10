package vn.chamcong.iot.data

import vn.chamcong.iot.domain.isWorkItemOverdue
import vn.chamcong.iot.domain.wasWorkItemCompletedLate
import vn.chamcong.iot.model.WorkItem
import java.time.Instant

/** UTC ISO instants keep export unambiguous; app forms display Vietnam local time. */
fun workItemsToCsv(items: List<WorkItem>, now: Instant = Instant.now()): String = buildString {
    append('\uFEFF')
    appendLine("id,title,description,requiredResult,assignedById,assignedByName,assigneeId,assigneeName,startAt,deadline,priority,status,completedAt,resultReport,managerFeedback,relatedScheduleId,relatedShiftId,overdue,completedLate,reworkCount,version")
    items.forEach { item ->
        appendLine(listOf(item.id, item.title, item.description, item.requiredResult,
            item.assignedById, item.assignedByName, item.assigneeId, item.assigneeName,
            item.startAt.toDate().toInstant(), item.deadline.toDate().toInstant(), item.priority, item.status,
            item.completedAt?.toDate()?.toInstant(), item.resultReport, item.managerFeedback,
            item.relatedScheduleId, item.relatedShiftId, isWorkItemOverdue(item, now),
            wasWorkItemCompletedLate(item), item.reworkCount, item.version
        ).joinToString(",") { value ->
            val original = value?.toString().orEmpty()
            // Reports contain employee-authored text; keep spreadsheet apps from evaluating it.
            val text = if (value is String && (
                original.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
                    original.firstOrNull() in listOf('\t', '\r', '\n')
                )) "'$original" else original
            if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${text.replace("\"", "\"\"")}\"" else text
        })
    }
}
