package vn.chamcong.iot.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkItemDraft
import vn.chamcong.iot.model.WorkItemAttachment
import vn.chamcong.iot.domain.MAX_WORK_ITEM_ATTACHMENTS
import vn.chamcong.iot.domain.validateWorkItemAttachments

fun MainViewModel.retryWorkItems() {
    workItemsSubscription?.cancel()
    val employeeId = when {
        subscriptionMode == "ADMIN" -> null
        subscriptionMode?.startsWith("EMPLOYEE:") == true -> _state.value.userProfile?.employeeId
            ?: return
        else -> return
    }
    _state.update { it.copy(workItemsLoading = true, workItemsError = null) }
    workItemsSubscription = viewModelScope.launch {
        repository.observeWorkItems(employeeId).catch { error ->
            if (error is CancellationException) throw error
            _state.update { it.copy(workItemsLoading = false, workItemsError = userFacingErrorMessage(error)) }
        }.collect { items ->
            _state.update { it.copy(workItems = items, workItemsLoading = false, workItemsError = null) }
        }
    }
}

fun MainViewModel.selectWorkItem(id: String?) {
    workItemHistorySubscription?.cancel()
    workItemHistorySubscription = null
    _state.update { it.copy(selectedWorkItemId = id, workItemHistory = emptyList(),
        workItemHistoryLoading = id != null, workItemHistoryError = null, error = null) }
    if (id == null) return
    workItemHistorySubscription = viewModelScope.launch {
        repository.observeWorkItemHistory(id).catch { error ->
            if (error is CancellationException) throw error
            _state.update { state -> if (state.selectedWorkItemId != id) state else
                state.copy(workItemHistoryLoading = false, workItemHistoryError = userFacingErrorMessage(error)) }
        }.collect { history ->
            _state.update { state -> if (state.selectedWorkItemId != id) state else
                state.copy(workItemHistory = history, workItemHistoryLoading = false, workItemHistoryError = null) }
        }
    }
}

private fun MainViewModel.performWorkItem(
    done: (String) -> Unit = {},
    block: suspend () -> Pair<String, String>
) = viewModelScope.launch {
    if (_state.value.saving) return@launch
    val generation = workItemSessionGeneration
    val userId = repository.currentUserId
    fun currentSession() = workItemSessionGeneration == generation && repository.currentUserId == userId
    workItemOperation = currentCoroutineContext()[Job]
    _state.update { it.copy(saving = true, error = null, message = null, workItemAttachmentStatus = null,
        feedbackGeneration = it.feedbackGeneration + 1) }
    try {
        val (id, message) = block()
        currentCoroutineContext().ensureActive()
        if (!currentSession()) return@launch
        _state.update { it.copy(saving = false, message = message, workItemAttachmentStatus = null) }
        done(id)
    } catch (error: CancellationException) {
        if (currentSession()) _state.update { it.copy(saving = false, workItemAttachmentStatus = null) }
        throw error
    } catch (error: Exception) {
        if (currentSession()) _state.update { it.copy(saving = false, error = userFacingErrorMessage(error), workItemAttachmentStatus = null) }
    } finally {
        if (workItemOperation === currentCoroutineContext()[Job]) workItemOperation = null
    }
}

fun MainViewModel.createWorkItem(draft: WorkItemDraft, done: (String) -> Unit = {}) = performWorkItem(done) {
    repository.createWorkItem(draft) to "Đã giao công việc"
}

fun MainViewModel.updateWorkItem(item: WorkItem, draft: WorkItemDraft, done: () -> Unit = {}) =
    performWorkItem({ done() }) {
        repository.updateWorkItem(item.id, draft, item.version)
        item.id to "Đã cập nhật công việc"
    }

fun MainViewModel.startWorkItem(item: WorkItem, done: () -> Unit = {}) = performWorkItem({ done() }) {
    repository.startWorkItem(item.id, item.version)
    item.id to "Đã bắt đầu công việc"
}

fun MainViewModel.updateWorkItemProgress(item: WorkItem, report: String, done: () -> Unit = {}) =
    performWorkItem({ done() }) {
        repository.updateWorkItemProgress(item.id, report, item.version)
        item.id to "Đã lưu tiến độ"
    }

fun MainViewModel.submitWorkItemResult(item: WorkItem, report: String, done: () -> Unit = {}) =
    performWorkItem({ done() }) {
        repository.submitWorkItemResult(item.id, report, item.version)
        item.id to "Đã gửi kết quả, đang chờ quản lý duyệt"
    }

fun MainViewModel.reviewWorkItemResult(item: WorkItem, approve: Boolean, feedback: String, done: () -> Unit = {}) =
    performWorkItem({ done() }) {
        repository.reviewWorkItemResult(item.id, approve, feedback, item.version)
        item.id to if (approve) "Đã duyệt hoàn thành công việc" else "Đã yêu cầu làm lại"
    }

fun MainViewModel.saveWorkItemReport(
    item: WorkItem,
    report: String,
    attachments: List<WorkItemAttachment>,
    pendingUris: List<Uri>,
    submit: Boolean,
    done: () -> Unit = {}
) = performWorkItem({ done() }) {
    val userId = repository.currentUserId
    require(userId.isNotBlank() && _state.value.currentEmployee?.id == item.assigneeId) { "Công việc không được giao cho bạn" }
    require(item.status == "IN_PROGRESS") { "Công việc không còn ở trạng thái đang thực hiện" }
    require(report.length <= 10000) { "Nội dung báo cáo tối đa 10000 ký tự" }
    validateWorkItemAttachments(attachments)
    val selected = pendingUris.distinct()
    require(attachments.size + selected.size <= MAX_WORK_ITEM_ATTACHMENTS) { "Mỗi báo cáo tối đa 5 tệp đính kèm" }
    require(!submit || report.isNotBlank() || attachments.isNotEmpty() || selected.isNotEmpty()) { "Cần nhập kết quả hoặc đính kèm tệp trước khi gửi duyệt" }
    val uploaded = selected.mapIndexed { index, uri ->
        require(repository.currentUserId == userId) { "Tài khoản đã thay đổi. Vui lòng mở lại công việc." }
        _state.update { it.copy(workItemAttachmentStatus = "Đang tải tệp ${index + 1}/${selected.size}…") }
        repository.uploadWorkItemAttachment(item.id, uri)
    }
    require(repository.currentUserId == userId) { "Tài khoản đã thay đổi. Vui lòng mở lại công việc." }
    val files = (attachments + uploaded).distinctBy { it.id }
    _state.update { it.copy(workItemAttachmentStatus = "Đang lưu báo cáo…") }
    if (submit) repository.submitWorkItemResult(item.id, report, item.version, files)
    else repository.updateWorkItemProgress(item.id, report, item.version, files)
    item.id to if (submit) "Đã gửi kết quả, đang chờ quản lý duyệt" else "Đã lưu tiến độ"
}

fun MainViewModel.openWorkItemAttachment(workItemId: String, attachment: WorkItemAttachment) = performWorkItem {
    val userId = repository.currentUserId
    _state.update { it.copy(workItemAttachmentStatus = "Đang tải ${attachment.fileName}…") }
    val file = repository.downloadWorkItemAttachment(workItemId, attachment)
    require(repository.currentUserId == userId) { "Tài khoản đã thay đổi. Vui lòng mở lại công việc." }
    val context = repository.appContext
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(attachment.fileName, uri)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Mở tệp đính kèm").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        error("Thiết bị chưa có ứng dụng mở loại tệp này")
    }
    workItemId to "Đã tải tệp đính kèm"
}
