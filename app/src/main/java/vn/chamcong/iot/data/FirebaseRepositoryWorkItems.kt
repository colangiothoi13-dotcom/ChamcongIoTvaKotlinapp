package vn.chamcong.iot.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.*
import vn.chamcong.iot.model.*

/** No limit: task KPIs must not silently omit older completed tasks. */
fun FirebaseRepository.observeWorkItems(employeeId: String? = null): Flow<List<WorkItem>> = callbackFlow {
    var query: Query = db.collection("workItems")
    if (employeeId != null) {
        require(employeeId.isNotBlank()) { "Chưa liên kết nhân viên" }
        query = query.whereEqualTo("assigneeId", employeeId)
    }
    val listener = query.orderBy("deadline").addSnapshotListener { snapshot, error ->
        if (error != null) close(error)
        else runCatching {
            snapshot?.documents.orEmpty().mapNotNull { it.workItem() }
        }.onSuccess { trySend(it) }.onFailure { close(it) }
    }
    awaitClose { listener.remove() }
}

fun FirebaseRepository.observeWorkItemHistory(workItemId: String): Flow<List<WorkItemHistory>> = callbackFlow {
    requireDocumentId(workItemId)
    val listener = db.collection("workItems").document(workItemId).collection("history")
        .orderBy("version", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else runCatching {
                snapshot?.documents.orEmpty().mapNotNull { document ->
                    document.toObject(WorkItemHistory::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?.copy(id = document.id)
                }
            }.onSuccess { trySend(it) }.onFailure { close(it) }
        }
    awaitClose { listener.remove() }
}

suspend fun FirebaseRepository.getWorkItemSchedule(scheduleId: String): WorkSchedule? {
    requireDocumentId(scheduleId)
    val snapshot = db.collection("workSchedules").document(scheduleId).get(Source.SERVER).await()
    return snapshot.toObject(WorkSchedule::class.java)?.copy(id = snapshot.id)
}

suspend fun FirebaseRepository.createWorkItem(draft: WorkItemDraft): String {
    validateWorkItemDraft(draft)
    val ref = db.collection("workItems").document()
    db.runTransaction { transaction ->
        val actor = requireWorkActor(transaction, manager = true)
        val canonical = canonicalWorkDraft(transaction, draft)
        val now = Timestamp.now()
        val item = WorkItem(
            title = canonical.title.trim(), description = canonical.description.trim(),
            requiredResult = canonical.requiredResult.trim(), assignedById = actor.uid,
            assignedByName = actor.name, assigneeId = canonical.assigneeId, assigneeName = canonical.assigneeName,
            startAt = canonical.startAt, deadline = canonical.deadline, priority = canonical.priority,
            relatedScheduleId = canonical.relatedScheduleId, relatedShiftId = canonical.relatedShiftId,
            relatedScheduleDate = canonical.relatedScheduleDate,
            createdAt = now, updatedAt = now, lastUpdatedById = actor.uid, lastUpdatedByName = actor.name
        )
        writeWorkVersion(transaction, ref.id, null, null, item, WorkItemAction.CREATED, actor)
    }.await()
    return ref.id
}

suspend fun FirebaseRepository.updateWorkItem(id: String, draft: WorkItemDraft, expectedVersion: Long) {
    validateWorkItemDraft(draft)
    mutateWorkItem(id, expectedVersion, WorkItemAction.EDITED, manager = true) { transaction, current ->
        val canonical = canonicalWorkDraft(transaction, draft)
        editWorkItem(current, canonical).copy(relatedScheduleDate = canonical.relatedScheduleDate)
    }
}

suspend fun FirebaseRepository.startWorkItem(id: String, expectedVersion: Long) =
    mutateWorkItem(id, expectedVersion, WorkItemAction.STARTED) { _, current -> vn.chamcong.iot.domain.startWorkItem(current) }

suspend fun FirebaseRepository.updateWorkItemProgress(
    id: String, report: String, expectedVersion: Long, attachments: List<WorkItemAttachment>? = null
) =
    mutateWorkItem(id, expectedVersion, WorkItemAction.PROGRESS_UPDATED) { _, current ->
        vn.chamcong.iot.domain.updateWorkItemProgress(current, report, attachments ?: current.resultAttachments)
    }

suspend fun FirebaseRepository.submitWorkItemResult(
    id: String, report: String, expectedVersion: Long, attachments: List<WorkItemAttachment>? = null
) =
    mutateWorkItem(id, expectedVersion, WorkItemAction.RESULT_SUBMITTED) { _, current ->
        vn.chamcong.iot.domain.submitWorkItemResult(current, report, attachments ?: current.resultAttachments)
    }

suspend fun FirebaseRepository.reviewWorkItemResult(id: String, approve: Boolean, feedback: String, expectedVersion: Long) =
    mutateWorkItem(id, expectedVersion, if (approve) WorkItemAction.APPROVED else WorkItemAction.REWORK_REQUESTED,
        manager = true) { _, current ->
        vn.chamcong.iot.domain.reviewWorkItemResult(current, approve, feedback, Timestamp.now())
    }

private data class WorkActor(val uid: String, val name: String, val employeeId: String?)

private fun FirebaseRepository.requireWorkActor(transaction: Transaction, manager: Boolean): WorkActor {
    require(currentUserId.isNotBlank()) { "Chưa đăng nhập" }
    val profile = transaction.get(db.collection("users").document(currentUserId)).toObject(UserProfile::class.java)
        ?: error("Không tìm thấy tài khoản")
    require(profile.active) { "Tài khoản đã ngừng hoạt động" }
    require(profile.role == if (manager) UserRole.ADMIN.name else UserRole.EMPLOYEE.name) {
        if (manager) "Chỉ quản lý được giao và duyệt công việc" else "Chỉ người thực hiện được cập nhật kết quả"
    }
    if (!manager) {
        require(!profile.employeeId.isNullOrBlank()) { "Chưa liên kết nhân viên" }
        val employee = transaction.get(db.collection("employees").document(profile.employeeId!!))
            .toObject(Employee::class.java)
        require(employee?.active == true) { "Nhân viên đã ngừng hoạt động" }
    }
    return WorkActor(currentUserId, profile.displayName.ifBlank { currentUserName }, profile.employeeId)
}

private fun FirebaseRepository.canonicalWorkDraft(transaction: Transaction, draft: WorkItemDraft): WorkItemDraft {
    val employee = transaction.get(db.collection("employees").document(draft.assigneeId))
        .toObject(Employee::class.java) ?: error("Người thực hiện không tồn tại")
    require(employee.active) { "Không thể giao việc cho nhân viên đã nghỉ" }
    val schedule = draft.relatedScheduleId?.let { scheduleId ->
        transaction.get(db.collection("workSchedules").document(scheduleId))
            .toObject(WorkSchedule::class.java) ?: error("Lịch phân ca không còn tồn tại")
    }
    if (schedule != null) {
        require(schedule.employeeId == draft.assigneeId) { "Ca liên quan phải thuộc người thực hiện" }
        require(draft.relatedShiftId in schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }) {
            "Ca đã chọn không có trong lịch phân ca"
        }
    }
    return draft.copy(assigneeName = employee.fullName, relatedScheduleDate = schedule?.date)
}

private suspend fun FirebaseRepository.mutateWorkItem(
    id: String, expectedVersion: Long, action: WorkItemAction, manager: Boolean = false,
    change: (Transaction, WorkItem) -> WorkItem
) {
    requireDocumentId(id)
    val ref = db.collection("workItems").document(id)
    db.runTransaction { transaction ->
        val actor = requireWorkActor(transaction, manager)
        val snapshot = transaction.get(ref)
        val current = snapshot.workItem() ?: error("Công việc không còn tồn tại")
        requireWorkItemVersion(current, expectedVersion)
        if (!manager) require(current.assigneeId == actor.employeeId) { "Công việc không được giao cho bạn" }
        val next = change(transaction, current).copy(version = current.version + 1,
            updatedAt = Timestamp.now(), lastUpdatedById = actor.uid, lastUpdatedByName = actor.name)
        writeWorkVersion(transaction, id, current, snapshot.data, next, action, actor)
    }.await()
}

/** Both writes are required by Firestore rules. A stale version fails even after transaction retry. */
private fun FirebaseRepository.writeWorkVersion(
    transaction: Transaction, id: String, previous: WorkItem?, previousData: Map<String, Any>?,
    next: WorkItem, action: WorkItemAction, actor: WorkActor
) {
    val ref = db.collection("workItems").document(id)
    val data = next.firestoreWorkData().toMutableMap().apply {
        this["updatedAt"] = FieldValue.serverTimestamp()
        if (previous == null) this["createdAt"] = FieldValue.serverTimestamp()
        if (action == WorkItemAction.APPROVED) this["completedAt"] = FieldValue.serverTimestamp()
    }
    transaction.set(ref, data)
    transaction.set(ref.collection("history").document(next.version.toString()), mapOf(
        "workItemId" to id, "version" to next.version, "action" to action.name,
        "actorId" to actor.uid, "actorName" to actor.name, "createdAt" to FieldValue.serverTimestamp(),
        "before" to previousData, "after" to data
    ))
}

// Local writes can be emitted before server timestamps resolve; never inject null into non-null timestamps.
private fun DocumentSnapshot.workItem(): WorkItem? =
    toObject(WorkItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.copy(id = id)
private fun requireDocumentId(id: String) = require(id.isNotBlank() && '/' !in id) { "Mã công việc không hợp lệ" }

private fun WorkItem.firestoreWorkData(): Map<String, Any?> = mapOf(
    "title" to title, "description" to description, "requiredResult" to requiredResult,
    "assignedById" to assignedById, "assignedByName" to assignedByName,
    "assigneeId" to assigneeId, "assigneeName" to assigneeName, "startAt" to startAt,
    "deadline" to deadline, "priority" to priority, "status" to status, "completedAt" to completedAt,
    "resultReport" to resultReport, "resultAttachments" to resultAttachments.map { it.firestoreAttachmentData() },
    "managerFeedback" to managerFeedback,
    "relatedScheduleId" to relatedScheduleId, "relatedShiftId" to relatedShiftId,
    "relatedScheduleDate" to relatedScheduleDate, "version" to version, "reworkCount" to reworkCount,
    "createdAt" to createdAt, "updatedAt" to updatedAt,
    "lastUpdatedById" to lastUpdatedById, "lastUpdatedByName" to lastUpdatedByName
)
