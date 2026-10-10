package vn.chamcong.iot.ui.workitems

import com.google.firebase.Timestamp
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemHistory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date

internal val workZone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
private val workDateTimeFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withResolverStyle(ResolverStyle.STRICT)

internal fun workTimeLabel(value: Timestamp?): String = value?.toDate()?.toInstant()
    ?.atZone(workZone)?.format(workDateTimeFormat) ?: "Chưa có"

internal fun parseWorkTime(value: String): Timestamp? = runCatching {
    Timestamp(Date.from(LocalDateTime.parse(value.trim(), workDateTimeFormat).atZone(workZone).toInstant()))
}.getOrNull()

internal fun workStatusLabel(value: String): String = when (value) {
    "ASSIGNED" -> "Được giao"
    "IN_PROGRESS" -> "Đang thực hiện"
    "PENDING_REVIEW" -> "Chờ duyệt"
    "COMPLETED" -> "Hoàn thành"
    else -> value
}

internal fun workPriorityLabel(value: String): String = when (value) {
    "LOW" -> "Thấp"
    "NORMAL" -> "Bình thường"
    "HIGH" -> "Cao"
    "URGENT" -> "Khẩn cấp"
    else -> value
}

internal fun workActionLabel(value: String): String = when (value) {
    "CREATED" -> "Tạo và giao việc"
    "EDITED" -> "Điều chỉnh công việc"
    "STARTED" -> "Bắt đầu thực hiện"
    "PROGRESS_UPDATED" -> "Cập nhật tiến độ"
    "RESULT_SUBMITTED" -> "Gửi kết quả chờ duyệt"
    "APPROVED" -> "Duyệt hoàn thành"
    "REWORK_REQUESTED" -> "Yêu cầu làm lại"
    else -> value
}

/** Human-readable before/after values come from the immutable transaction snapshot. */
internal fun workHistoryChanges(history: WorkItemHistory): List<String> {
    val before = history.before
    val after = history.after
    if (before == null) return listOf(
        "Tên: ${after.title}", "Mô tả: ${after.description}", "Yêu cầu kết quả: ${after.requiredResult}",
        "Giao cho: ${after.assigneeName}", "Bắt đầu: ${workTimeLabel(after.startAt)}",
        "Hạn: ${workTimeLabel(after.deadline)}", "Ưu tiên: ${workPriorityLabel(after.priority)}",
        "Trạng thái: ${workStatusLabel(after.status)}",
        "Ca liên quan: ${after.relatedScheduleDate ?: "Không có ngày"} • ${after.relatedScheduleId ?: "Không có"} / ${after.relatedShiftId ?: "Không có"}"
    )
    return buildList {
        fun changed(label: String, old: String, new: String) {
            if (old != new) add("$label: ${old.ifBlank { "Trống" }} → ${new.ifBlank { "Trống" }}")
        }
        changed("Tên", before.title, after.title)
        changed("Mô tả", before.description, after.description)
        changed("Yêu cầu kết quả", before.requiredResult, after.requiredResult)
        if (before.assigneeId != after.assigneeId) add("Người thực hiện: ${before.assigneeName} → ${after.assigneeName}")
        changed("Bắt đầu", workTimeLabel(before.startAt), workTimeLabel(after.startAt))
        changed("Hạn", workTimeLabel(before.deadline), workTimeLabel(after.deadline))
        changed("Ưu tiên", workPriorityLabel(before.priority), workPriorityLabel(after.priority))
        changed("Trạng thái", workStatusLabel(before.status), workStatusLabel(after.status))
        changed("Báo cáo", before.resultReport, after.resultReport)
        if (before.resultAttachments != after.resultAttachments) {
            val oldNames = before.resultAttachments.joinToString { it.fileName }
            val newNames = after.resultAttachments.joinToString { it.fileName }
            if (oldNames == newNames) add("Tệp đính kèm: đã cập nhật nội dung $newNames")
            else changed("Tệp đính kèm", oldNames, newNames)
        }
        changed("Phản hồi", before.managerFeedback, after.managerFeedback)
        changed("Thời điểm hoàn thành", workTimeLabel(before.completedAt), workTimeLabel(after.completedAt))
        changed("Ngày ca liên quan", before.relatedScheduleDate.orEmpty(), after.relatedScheduleDate.orEmpty())
        if (before.reworkCount != after.reworkCount) add("Số lần yêu cầu làm lại: ${before.reworkCount} → ${after.reworkCount}")
        if (before.relatedScheduleId != after.relatedScheduleId || before.relatedShiftId != after.relatedShiftId) {
            add("Ca liên quan: ${before.relatedScheduleId ?: "Không có"}/${before.relatedShiftId.orEmpty()} → ${after.relatedScheduleId ?: "Không có"}/${after.relatedShiftId.orEmpty()}")
        }
    }
}
