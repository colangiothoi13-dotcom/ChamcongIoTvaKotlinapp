package vn.chamcong.iot.data

import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import vn.chamcong.iot.domain.MAX_WORK_ITEM_ATTACHMENT_BYTES
import vn.chamcong.iot.domain.WORK_ITEM_ATTACHMENT_CHUNK_BYTES
import vn.chamcong.iot.domain.validateWorkItemAttachments
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.UserRole
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemAttachment
import vn.chamcong.iot.model.WorkItemStatus
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Small binary chunks keep employee evidence available on the project's Firebase Spark plan. */
suspend fun FirebaseRepository.uploadWorkItemAttachment(workItemId: String, uri: Uri): WorkItemAttachment =
    withContext(Dispatchers.IO) {
        requireAttachmentDocumentId(workItemId)
        require(uri.scheme == "content") { "Vui lòng chọn ảnh hoặc tệp từ thiết bị" }
        require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
        val actorId = currentUserId
        val profile = db.collection("users").document(actorId).get(Source.SERVER).await()
            .toObject(UserProfile::class.java) ?: error("Không tìm thấy tài khoản")
        require(profile.active && profile.role == UserRole.EMPLOYEE.name && !profile.employeeId.isNullOrBlank()) {
            "Chỉ nhân viên đang hoạt động được gửi tệp kết quả"
        }
        val employeeId = requireNotNull(profile.employeeId)
        val employee = db.collection("employees").document(employeeId).get(Source.SERVER).await()
            .toObject(Employee::class.java)
        require(employee?.active == true) { "Nhân viên đã ngừng hoạt động" }
        val taskRef = db.collection("workItems").document(workItemId)
        val item = taskRef.get(Source.SERVER).await().toObject(WorkItem::class.java)
            ?: error("Công việc không còn tồn tại")
        require(item.assigneeId == employeeId && item.status == WorkItemStatus.IN_PROGRESS.name) {
            "Chỉ gửi tệp cho công việc của bạn đang thực hiện"
        }
        val resolver = appContext.contentResolver
        var displayName = "tep-dinh-kem"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    require(cursor.getLong(sizeColumn) <= MAX_WORK_ITEM_ATTACHMENT_BYTES) { "Mỗi tệp tối đa 5 MB" }
                }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { readBoundedWorkAttachment(it) }
            ?: error("Không đọc được tệp đã chọn")
        currentCoroutineContext().ensureActive()
        val fileName = sanitizeWorkAttachmentFileName(displayName)
        val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() && it.length <= 120 && '\n' !in it && '\r' !in it }
            ?: "application/octet-stream"
        val hash = attachmentSha256(bytes)
        val stableId = attachmentSha256(listOf(hash, fileName, mimeType, actorId, employeeId)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val attachment = WorkItemAttachment(
            id = stableId, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(), sha256 = hash,
            chunkCount = (bytes.size + WORK_ITEM_ATTACHMENT_CHUNK_BYTES - 1) / WORK_ITEM_ATTACHMENT_CHUNK_BYTES,
            uploadedById = actorId, assigneeId = employeeId
        )
        validateWorkItemAttachments(listOf(attachment))
        val attachmentRef = taskRef.collection("attachments").document(attachment.id)
        val existing = attachmentRef.get(Source.SERVER).await().toObject(WorkItemAttachment::class.java)
        if (existing != null) {
            require(existing.copy(createdAt = null) == attachment) { "Thông tin tệp đã lưu không khớp" }
            return@withContext existing
        }
        val batch = db.batch()
        batch.set(attachmentRef, attachment.firestoreAttachmentData().toMutableMap().apply {
            this["createdAt"] = FieldValue.serverTimestamp()
        })
        repeat(attachment.chunkCount) { index ->
            val offset = index * WORK_ITEM_ATTACHMENT_CHUNK_BYTES
            val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + WORK_ITEM_ATTACHMENT_CHUNK_BYTES))
            batch.set(attachmentRef.collection("chunks").document(index.toString()), mapOf(
                "index" to index, "data" to Blob.fromBytes(chunk), "createdAt" to FieldValue.serverTimestamp()
            ))
        }
        // Rules recheck the assignment/status and require every chunk in this same atomic batch.
        try {
            batch.commit().await()
        } catch (error: Exception) {
            // Concurrent retries can upload the same immutable content first. Reuse it only if
            // the server still authorizes this account and every metadata field matches.
            if (error is kotlinx.coroutines.CancellationException) throw error
            val raced = runCatching { attachmentRef.get(Source.SERVER).await().toObject(WorkItemAttachment::class.java) }.getOrNull()
            if (raced == null || raced.copy(createdAt = null) != attachment) throw error
            return@withContext raced
        }
        attachmentRef.get(Source.SERVER).await().toObject(WorkItemAttachment::class.java)
            ?: error("Không xác nhận được tệp vừa tải lên. Hãy thử lại")
    }

/** Always reauthorize through server reads before returning a locally cached file. */
suspend fun FirebaseRepository.downloadWorkItemAttachment(workItemId: String, attachment: WorkItemAttachment): File =
    withContext(Dispatchers.IO) {
        requireAttachmentDocumentId(workItemId)
        validateWorkItemAttachments(listOf(attachment))
        val attachmentRef = db.collection("workItems").document(workItemId).collection("attachments").document(attachment.id)
        val stored = attachmentRef.get(Source.SERVER).await().toObject(WorkItemAttachment::class.java)
            ?: error("Tệp đính kèm không còn tồn tại")
        require(stored == attachment) { "Thông tin tệp đính kèm đã thay đổi. Hãy tải lại công việc" }
        val directory = File(appContext.cacheDir, "work-item-attachments/$workItemId/${attachment.id}")
        require(directory.mkdirs() || directory.isDirectory) { "Không tạo được bộ nhớ tạm cho tệp" }
        val target = File(directory, workAttachmentCacheFileName(attachment))
        val pending = File.createTempFile("download-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            pending.outputStream().use { output ->
                repeat(attachment.chunkCount) { index ->
                    currentCoroutineContext().ensureActive()
                    val chunk = attachmentRef.collection("chunks").document(index.toString()).get(Source.SERVER).await()
                    require(chunk.getLong("index") == index.toLong()) { "Tệp tải về thiếu dữ liệu" }
                    val bytes = chunk.getBlob("data")?.toBytes() ?: error("Tệp tải về thiếu dữ liệu")
                    val expected = minOf(WORK_ITEM_ATTACHMENT_CHUNK_BYTES.toLong(), attachment.sizeBytes - downloaded).toInt()
                    require(bytes.size == expected) { "Tệp tải về có kích thước không đúng" }
                    output.write(bytes)
                    digest.update(bytes)
                    downloaded += bytes.size
                }
            }
            require(downloaded == attachment.sizeBytes && digest.digest().attachmentHex() == attachment.sha256) {
                "Tệp tải về bị lỗi dữ liệu. Hãy thử lại"
            }
            if (!pending.renameTo(target)) {
                pending.copyTo(target, overwrite = true)
                pending.delete()
            }
            target
        } finally {
            pending.delete()
        }
    }

internal fun readBoundedWorkAttachment(input: InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        // Read one byte beyond the limit so providers with absent/incorrect SIZE cannot bypass it.
        val allowed = minOf(buffer.size.toLong(), MAX_WORK_ITEM_ATTACHMENT_BYTES + 1 - output.size()).toInt()
        val count = input.read(buffer, 0, allowed)
        if (count < 0) break
        if (count == 0) {
            val next = input.read()
            if (next < 0) break
            output.write(next)
        } else output.write(buffer, 0, count)
        require(output.size() <= MAX_WORK_ITEM_ATTACHMENT_BYTES) { "Mỗi tệp tối đa 5 MB" }
    }
    require(output.size() > 0) { "Không thể gửi tệp rỗng" }
    return output.toByteArray()
}

internal fun sanitizeWorkAttachmentFileName(value: String): String =
    value.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }
        .trim().trim('.').take(180).ifBlank { "tep-dinh-kem" }

/** Metadata keeps the display name; ASCII disk names stay below Android's 255-byte limit. */
internal fun workAttachmentCacheFileName(attachment: WorkItemAttachment): String {
    val extension = attachment.fileName.substringAfterLast('.', "")
        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }.orEmpty()
    return attachment.id + if (extension.isEmpty()) "" else ".$extension"
}

private fun attachmentSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).attachmentHex()
private fun ByteArray.attachmentHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun requireAttachmentDocumentId(id: String) = require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
    "Mã công việc không hợp lệ"
}

internal fun WorkItemAttachment.firestoreAttachmentData(): Map<String, Any?> = mapOf(
    "id" to id, "fileName" to fileName, "mimeType" to mimeType, "sizeBytes" to sizeBytes,
    "sha256" to sha256, "chunkCount" to chunkCount, "uploadedById" to uploadedById,
    "assigneeId" to assigneeId, "createdAt" to createdAt
)
