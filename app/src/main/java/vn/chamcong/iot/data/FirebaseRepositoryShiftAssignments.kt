// Chức năng: Lưu ca làm và phân ca cho nhân viên hoặc phòng ban.
package vn.chamcong.iot.data

import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.domain.copyScheduleToNextWeek
import vn.chamcong.iot.domain.canAssignScheduleShift
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.validateOvertimeHours
import vn.chamcong.iot.domain.validateShift
import vn.chamcong.iot.domain.validateScheduleShift
import vn.chamcong.iot.domain.validateWorkedHoursOverride
import vn.chamcong.iot.domain.defaultShiftTemplates
import vn.chamcong.iot.domain.mergeWeeklyAssignment
import vn.chamcong.iot.domain.scheduledShiftIds
import vn.chamcong.iot.domain.weeklyAssignmentShift
import vn.chamcong.iot.model.*
import java.time.LocalDate

suspend fun FirebaseRepository.saveShift(shift: WorkShift): String {
    validateShift(shift)
    require(shift.category == ShiftCategory.SUPPLEMENTARY.name) { "Ca sáng và ca chiều đã có mặc định; chỉ thêm ca tăng ca" }
    val ref = if (shift.id.isBlank()) db.collection("shifts").document() else db.collection("shifts").document(shift.id)
    ref.set(shift.copy(id = "")).await()
    writeAuditLog(AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = AuditAction.SHIFT_UPDATE.name,
        targetType = "shift",
        targetId = ref.id,
        details = "Lưu cấu hình ca ${shift.name}"
    ))
    return ref.id
}

private suspend fun FirebaseRepository.ensureDefaultShiftStored(shiftId: String) {
    val standard = defaultShiftTemplates().map { it.resolve() }.firstOrNull { it.id == shiftId } ?: return
    val ref = db.collection("shifts").document(shiftId)
    if (!ref.get(Source.SERVER).await().exists()) ref.set(standard.copy(id = "")).await()
}

suspend fun FirebaseRepository.saveSchedule(schedule: WorkSchedule) {
    require(schedule.employeeId.isNotBlank()) { "Chưa chọn nhân viên" }
    require(schedule.shiftId.isNotBlank()) { "Chưa chọn ca" }
    val date = LocalDate.parse(schedule.date).toString()
    require(LocalDate.parse(date).dayOfWeek.value in 1..6) { "Không thể phân ca vào Chủ nhật" }
    val selectedShiftIds = schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }
    require(selectedShiftIds.size in 1..2 && selectedShiftIds.distinct().size == selectedShiftIds.size) {
        "Có thể chọn tối đa hai ca khác nhau trong ngày"
    }
    require(selectedShiftIds.first() == schedule.shiftId) { "Ca chính phải là ca đầu tiên trong danh sách" }
    selectedShiftIds.forEach { ensureDefaultShiftStored(it) }
    val selectedShifts = selectedShiftIds.map { requireActiveAssignableShift(it) }
    require(selectedShifts.map { it.category }.distinct().size == selectedShifts.size) {
        "Trong ngày chỉ có thể chọn một ca sáng và một ca chiều"
    }
    validateOvertimeHours(schedule.overtimeHours)
    validateWorkedHoursOverride(schedule.workedHoursOverride)
    if (schedule.workedHoursOverride != null) require(schedule.adjustmentNote.isNotBlank()) { "Cần nhập lý do điều chỉnh giờ" }
    val id = scheduleDocumentId(schedule.employeeId, date)
    db.collection("workSchedules").document(id)
        .set(schedule.copy(id = "", date = date, shiftIds = selectedShiftIds, source = "ADMIN")).await()
    writeAuditLog(AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = if (schedule.workedHoursOverride != null) AuditAction.ATTENDANCE_ADJUST.name else AuditAction.SHIFT_UPDATE.name,
        targetType = "workSchedule",
        targetId = id,
        reason = schedule.adjustmentNote,
        details = "Phân ca ${schedule.shiftName} ngày $date cho ${schedule.employeeName}"
    ))
}

/** Keep existing shift settings and the other shift already assigned to each employee/day. */
suspend fun FirebaseRepository.saveWeeklySchedules(shift: WorkShift, schedules: List<WorkSchedule>) {
    validateScheduleShift(shift)
    require(schedules.isNotEmpty())
    require(schedules.all { it.shiftId == shift.id } &&
        schedules.map { scheduleDocumentId(it.employeeId, it.date) }.distinct().size == schedules.size) {
        "Danh sách phân ca trùng hoặc không đúng ca đã chọn"
    }
    require(schedules.all { LocalDate.parse(it.date).dayOfWeek.value in 1..6 }) {
        "Không thể phân ca vào Chủ nhật"
    }
    val shiftRef = db.collection("shifts").document(shift.id)
    var saved = 0
    try {
        schedules.chunked(400).forEach { chunk ->
            val auditRef = db.collection("audit_logs").document()
            db.runTransaction { transaction ->
                val snapshot = transaction.get(shiftRef)
                val stored = if (snapshot.exists()) snapshot.toObject(WorkShift::class.java)?.copy(id = shift.id)
                    ?: error("Không thể đọc ca mẫu đã lưu") else null
                val selectedShift = weeklyAssignmentShift(shift, stored)
                val scheduleRefs = chunk.associateWith { schedule ->
                    db.collection("workSchedules").document(scheduleDocumentId(schedule.employeeId, schedule.date))
                }
                val previousSchedules = scheduleRefs.mapValues { (_, ref) ->
                    val previous = transaction.get(ref)
                    if (previous.exists()) previous.toObject(WorkSchedule::class.java)
                        ?: error("Không thể đọc lịch đã phân") else null
                }
                val previousShiftIds = previousSchedules.values.filterNotNull()
                    .flatMap(::scheduledShiftIds).distinct().filterNot { it == shift.id }
                val shiftsById = previousShiftIds.associateWith { id ->
                    val previous = transaction.get(db.collection("shifts").document(id))
                    previous.toObject(WorkShift::class.java)?.copy(id = id)
                        ?: error("Ca đã phân trước đó không còn tồn tại: $id")
                } + (shift.id to selectedShift)

                if (stored == null) transaction.set(shiftRef, shift.copy(id = ""))
                chunk.forEach { schedule ->
                    val merged = mergeWeeklyAssignment(schedule, previousSchedules[schedule], selectedShift, shiftsById)
                    // Merge only schedule fields so earlier hour adjustments and notes remain intact.
                    transaction.set(scheduleRefs.getValue(schedule),
                        mapOf("id" to "", "employeeId" to merged.employeeId, "employeeName" to merged.employeeName,
                            "department" to merged.department, "date" to merged.date, "shiftId" to merged.shiftId,
                            "shiftIds" to merged.shiftIds,
                            "shiftName" to merged.shiftName, "overtimeHours" to merged.overtimeHours, "assignedBy" to currentUserId,
                            "source" to "ADMIN"), SetOptions.merge())
                }
                transaction.set(auditRef, AuditLog(actorId = currentUserId, actorName = currentUserName,
                    action = AuditAction.SHIFT_UPDATE.name, targetType = "workSchedule",
                    targetId = scheduleDocumentId(chunk.first().employeeId, chunk.first().date),
                    details = "Phân ca tuần: ${chunk.size} lịch; ${chunk.joinToString { it.id }}").toFirestoreData())
            }.await()
            saved += chunk.size
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        throw IllegalStateException("Đã lưu $saved/${schedules.size} lịch. Có thể thử lại an toàn. ${e.localizedMessage}", e)
    }
}

suspend fun FirebaseRepository.assignShiftToDepartment(
    department: String,
    dates: List<String>,
    shift: WorkShift,
    overtimeHours: Int,
    assignedBy: String
) {
    require(department.isNotBlank()) { "Chưa chọn phòng ban" }
    require(dates.isNotEmpty()) { "Chưa chọn ngày phân ca" }
    validateScheduleShift(shift)
    ensureDefaultShiftStored(shift.id)
    requireActiveAssignableShift(shift.id)
    validateOvertimeHours(overtimeHours)
    val employees = db.collection("employees")
        .whereEqualTo("active", true)
        .whereEqualTo("department", department)
        .get(Source.SERVER)
        .await()
        .documents
    val writes = dates.flatMap { rawDate ->
        val date = LocalDate.parse(rawDate).toString()
        require(LocalDate.parse(date).dayOfWeek.value in 1..6) { "Không thể phân ca vào Chủ nhật" }
        employees.mapNotNull { document ->
            document.toObject(Employee::class.java)?.let { employee ->
                val id = scheduleDocumentId(document.id, date)
                val schedule = WorkSchedule(
                    id = id,
                    employeeId = document.id,
                    employeeName = employee.fullName,
                    department = employee.department,
                    shiftId = shift.id,
                    shiftIds = listOf(shift.id),
                    shiftName = shift.name,
                    date = date,
                    overtimeHours = overtimeHours,
                    assignedBy = assignedBy,
                    source = "DEPARTMENT"
                )
                db.collection("workSchedules").document(id) to schedule.copy(id = "")
            }
        }
    }
    writes.chunked(400).forEach { chunk ->
        db.runBatch { batch ->
            chunk.forEach { (reference, schedule) -> batch.set(reference, schedule) }
        }.await()
    }
    writeAuditLog(AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = AuditAction.SHIFT_UPDATE.name,
        targetType = "department",
        targetId = department,
        details = "Phân lịch cho phòng ban $department, ngày $dates"
    ))
}

suspend fun FirebaseRepository.copyPreviousWeek(sourceWeekStart: String, targetWeekStart: String, assignedBy: String): Int {
    val sourceMonday = mondayOfWeek(LocalDate.parse(sourceWeekStart))
    val targetMonday = mondayOfWeek(LocalDate.parse(targetWeekStart))
    require(targetMonday == sourceMonday.plusWeeks(1)) { "Tuần đích phải là tuần kế tiếp" }
    val sourceEnd = sourceMonday.plusDays(6)
    val sourceSnapshot = db.collection("workSchedules")
        .whereGreaterThanOrEqualTo("date", sourceMonday.toString())
        .whereLessThanOrEqualTo("date", sourceEnd.toString())
        .get(Source.SERVER)
        .await()
    val targetSnapshot = db.collection("workSchedules")
        .whereGreaterThanOrEqualTo("date", targetMonday.toString())
        .whereLessThanOrEqualTo("date", targetMonday.plusDays(6).toString())
        .get(Source.SERVER)
        .await()
    val source = sourceSnapshot.documents.mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) }
    val existingIds = targetSnapshot.documents.mapTo(mutableSetOf()) { it.id }
    val newSchedules = copyScheduleToNextWeek(source, existingIds).map {
        it.copy(assignedBy = assignedBy, source = "ADMIN_COPY")
    }
    // Validate all candidates before writing any copied schedule, including legacy custom IDs.
    newSchedules.flatMap { it.shiftIds.ifEmpty { listOf(it.shiftId) } }.distinct()
        .forEach { requireActiveAssignableShift(it) }
    newSchedules.chunked(400).forEach { chunk ->
        db.runBatch { batch ->
            chunk.forEach { schedule ->
                batch.set(db.collection("workSchedules").document(schedule.id), schedule.copy(
                    id = "", shiftIds = schedule.shiftIds.ifEmpty { listOf(schedule.shiftId) }
                ))
            }
        }.await()
    }
    writeAuditLog(AuditLog(
        actorId = currentUserId,
        actorName = currentUserName,
        action = AuditAction.SHIFT_UPDATE.name,
        targetType = "week",
        targetId = targetMonday.toString(),
        details = "Sao chép $sourceMonday sang $targetMonday: ${newSchedules.size} lịch"
    ))
    return newSchedules.size
}

internal suspend fun FirebaseRepository.requireAssignableStoredShift(shiftId: String) {
    require(shiftId.isNotBlank()) { "Chưa chọn ca" }
    val snapshot = db.collection("shifts").document(shiftId).get(Source.SERVER).await()
    val shift = snapshot.toObject(WorkShift::class.java)?.copy(id = snapshot.id)
    require(shift != null) { "Ca không còn tồn tại" }
    validateScheduleShift(shift)
}

internal suspend fun FirebaseRepository.requireActiveAssignableShift(shiftId: String): WorkShift {
    require(shiftId.isNotBlank()) { "Chưa chọn ca" }
    val snapshot = db.collection("shifts").document(shiftId).get(Source.SERVER).await()
    val shift = snapshot.toObject(WorkShift::class.java)?.copy(id = snapshot.id)
        ?: error("Ca không còn tồn tại")
    require(shift.active && canAssignScheduleShift(shift)) { "Ca ${shift.name} không còn được đăng ký" }
    validateScheduleShift(shift)
    return shift
}
