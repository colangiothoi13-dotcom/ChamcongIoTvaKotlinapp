// Chức năng: Chuyển đổi lịch, đơn từ, thông báo và nhật ký sang dữ liệu Firestore.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import vn.chamcong.iot.model.*

internal fun FirebaseRepository.scheduleDocumentId(employeeId: String, date: String): String = "${employeeId}_$date"

internal fun WorkSchedule.toFirestoreData(): Map<String, Any?> = mapOf(
    "employeeId" to employeeId,
    "employeeName" to employeeName,
    "department" to department,
    "shiftId" to shiftId,
    "shiftIds" to shiftIds.ifEmpty { listOf(shiftId) },
    "shiftName" to shiftName,
    "date" to date,
    "overtimeHours" to overtimeHours,
    "workedHoursOverride" to workedHoursOverride,
    "adjustmentNote" to adjustmentNote,
    "assignedBy" to assignedBy,
    "source" to source,
    "note" to note
)

internal fun LeaveRequest.toFirestoreData(): Map<String, Any?> = mapOf(
    "employeeId" to employeeId,
    "employeeName" to employeeName,
    "department" to department,
    "type" to type,
    "startDate" to startDate,
    "endDate" to endDate,
    "reason" to reason,
    "proposedCheckIn" to proposedCheckIn,
    "proposedCheckOut" to proposedCheckOut,
    "requestedShiftId" to requestedShiftId,
    "requestedShiftName" to requestedShiftName,
    "leaveShiftsByDate" to leaveShiftsByDate,
    "appliedAdjustmentId" to appliedAdjustmentId,
    "attachmentUrl" to attachmentUrl,
    "status" to status,
    "reviewerId" to reviewerId,
    "reviewerName" to reviewerName,
    "reviewedAt" to reviewedAt,
    "reviewNote" to reviewNote,
    "createdAt" to FieldValue.serverTimestamp()
)

internal fun OvertimeRequest.toOvertimeFirestoreData(): Map<String, Any?> = mapOf(
    "employeeId" to employeeId,
    "employeeName" to employeeName,
    "department" to department,
    "workDate" to workDate,
    "startTime" to startTime,
    "endTime" to endTime,
    "reason" to reason,
    "status" to status,
    "createdAt" to FieldValue.serverTimestamp(),
    "reviewerId" to reviewerId,
    "reviewerName" to reviewerName,
    "reviewedAt" to reviewedAt?.let { Timestamp(it.epochSecond, it.nano) },
    "rejectionReason" to rejectionReason
)

internal fun AppNotification.toFirestoreData(): Map<String, Any?> = mapOf(
    "type" to type,
    "title" to title,
    "body" to body,
    "referenceId" to referenceId,
    "recipientEmployeeId" to recipientEmployeeId,
    "audienceLabel" to audienceLabel,
    "createdAt" to FieldValue.serverTimestamp(),
    "read" to read
)

internal fun FirebaseRepository.auditLog(document: DocumentSnapshot): AuditLog = AuditLog(
    id = document.id,
    actorId = document.getString("actorId").orEmpty(),
    actorName = document.getString("actorName").orEmpty(),
    action = document.getString("action").orEmpty(),
    targetType = document.getString("targetType").orEmpty(),
    targetId = document.getString("targetId").orEmpty(),
    reason = document.getString("reason").orEmpty(),
    details = document.getString("details").orEmpty(),
    createdAt = document.getTimestamp("createdAt") ?: Timestamp.now()
)

internal fun AuditLog.toFirestoreData(): Map<String, Any?> = mapOf(
    "actorId" to actorId,
    "actorName" to actorName,
    "action" to action,
    "targetType" to targetType,
    "targetId" to targetId,
    "reason" to reason,
    "details" to details,
    "createdAt" to FieldValue.serverTimestamp()
)
