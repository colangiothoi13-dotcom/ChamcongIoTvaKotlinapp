package vn.chamcong.iot.domain

import vn.chamcong.iot.model.WorkItemAttachment

const val MAX_WORK_ITEM_ATTACHMENTS = 5
const val MAX_WORK_ITEM_ATTACHMENT_BYTES = 5L * 1024 * 1024
const val WORK_ITEM_ATTACHMENT_CHUNK_BYTES = 512 * 1024

fun validateWorkItemAttachments(attachments: List<WorkItemAttachment>) {
    require(attachments.size <= MAX_WORK_ITEM_ATTACHMENTS) { "Mỗi báo cáo tối đa 5 tệp đính kèm" }
    require(attachments.map { it.id }.distinct().size == attachments.size) { "Tệp đính kèm bị trùng" }
    attachments.forEach { file ->
        require(file.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Mã tệp đính kèm không hợp lệ" }
        require(file.fileName.isNotBlank() && file.fileName.length <= 180 &&
            file.fileName !in listOf(".", "..") &&
            file.fileName.none { it == '/' || it == '\\' || it.isISOControl() }) { "Tên tệp đính kèm không hợp lệ" }
        require(file.mimeType.isNotBlank() && file.mimeType.length <= 120 &&
            file.mimeType.none(Char::isISOControl)) { "Định dạng tệp không hợp lệ" }
        require(file.sizeBytes in 1..MAX_WORK_ITEM_ATTACHMENT_BYTES) { "Mỗi tệp phải có dữ liệu và không vượt quá 5 MB" }
        require(file.sha256.matches(Regex("[0-9a-f]{64}"))) { "Dữ liệu xác thực tệp không hợp lệ" }
        val expectedChunks = ((file.sizeBytes + WORK_ITEM_ATTACHMENT_CHUNK_BYTES - 1) / WORK_ITEM_ATTACHMENT_CHUNK_BYTES).toInt()
        require(file.chunkCount == expectedChunks) { "Dữ liệu tệp đính kèm chưa đầy đủ" }
        require(file.uploadedById.isNotBlank() && file.assigneeId.isNotBlank()) { "Thiếu người gửi tệp đính kèm" }
    }
}
