package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemAttachment
import vn.chamcong.iot.model.WorkItemDraft

class WorkItemAttachmentRulesTest {
    private fun file(id: String = "file-1", size: Long = 123) = WorkItemAttachment(
        id = id, fileName = "biên bản.pdf", mimeType = "application/pdf", sizeBytes = size,
        sha256 = "a".repeat(64), chunkCount = ((size + WORK_ITEM_ATTACHMENT_CHUNK_BYTES - 1) / WORK_ITEM_ATTACHMENT_CHUNK_BYTES).toInt(),
        uploadedById = "user-1", assigneeId = "employee-1"
    )
    private fun item() = WorkItem(assigneeId = "employee-1", status = "IN_PROGRESS")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid attachment to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun attachmentOnlyResultCanBeSubmittedAndApproved() {
        val submitted = submitWorkItemResult(item(), "", listOf(file()))
        assertEquals("PENDING_REVIEW", submitted.status)
        assertEquals(listOf(file()), submitted.resultAttachments)
        val approved = reviewWorkItemResult(submitted, true, "", Timestamp(123, 0))
        assertEquals("COMPLETED", approved.status)
        assertEquals(submitted.resultAttachments, approved.resultAttachments)
    }

    @Test fun progressAndReworkPreserveFilesUntilExplicitlyRemoved() {
        val attached = updateWorkItemProgress(item(), "Tiến độ", listOf(file()))
        assertEquals(attached.resultAttachments, updateWorkItemProgress(attached, "Tiến độ mới").resultAttachments)
        val pending = submitWorkItemResult(attached, "Kết quả")
        val rework = reviewWorkItemResult(pending, false, "Bổ sung chữ ký", Timestamp(123, 0))
        assertEquals(attached.resultAttachments, rework.resultAttachments)
        assertTrue(updateWorkItemProgress(rework, "Đã sửa", emptyList()).resultAttachments.isEmpty())
        rejected { submitWorkItemResult(item(), " ", emptyList()) }
    }

    @Test fun reassignmentClearsCurrentFilesButKeepsPriorSnapshot() {
        val before = item().copy(resultReport = "Kết quả", resultAttachments = listOf(file()))
        val draft = WorkItemDraft(title = "Kiểm tra", description = "Mô tả", requiredResult = "Biên bản",
            assigneeId = "employee-2", assigneeName = "Bình", startAt = Timestamp(100, 0), deadline = Timestamp(200, 0))
        val after = editWorkItem(before, draft)
        assertTrue(after.resultAttachments.isEmpty())
        assertEquals(listOf(file()), before.resultAttachments)
    }

    @Test fun rejectsAnotherAssigneesFileAndDuplicateReferences() {
        rejected { updateWorkItemProgress(item(), "", listOf(file().copy(assigneeId = "employee-2"))) }
        rejected { validateWorkItemAttachments(listOf(file(), file())) }
        rejected { validateWorkItemAttachments((1..6).map { file("file-$it") }) }
    }

    @Test fun acceptsSizeBoundaryAndRejectsEmptyOversizedOrIncompleteContent() {
        validateWorkItemAttachments(listOf(file(size = MAX_WORK_ITEM_ATTACHMENT_BYTES)))
        rejected { validateWorkItemAttachments(listOf(file(size = 0))) }
        rejected { validateWorkItemAttachments(listOf(file(size = MAX_WORK_ITEM_ATTACHMENT_BYTES + 1))) }
        rejected { validateWorkItemAttachments(listOf(file(size = WORK_ITEM_ATTACHMENT_CHUNK_BYTES + 1L).copy(chunkCount = 1))) }
        rejected { validateWorkItemAttachments(listOf(file().copy(sha256 = "bad"))) }
    }

    @Test fun rejectsUnsafeOrMissingFileMetadata() {
        for (name in listOf("../secret", "dir\\secret", "bad\nname", "..", "", "a".repeat(181))) {
            rejected { validateWorkItemAttachments(listOf(file().copy(fileName = name))) }
        }
        rejected { validateWorkItemAttachments(listOf(file().copy(id = "../other"))) }
        rejected { validateWorkItemAttachments(listOf(file().copy(mimeType = "image/png\n"))) }
        rejected { validateWorkItemAttachments(listOf(file().copy(uploadedById = ""))) }
    }
}
