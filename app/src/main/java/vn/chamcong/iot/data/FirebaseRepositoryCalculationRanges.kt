package vn.chamcong.iot.data

import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.*
import java.time.LocalDate

/** All dependencies of one server-verified calculation, including late-created corrections. */
data class CalculationData(
    val schedules: List<WorkSchedule> = emptyList(),
    val shifts: List<WorkShift> = emptyList(),
    val leaveRequests: List<LeaveRequest> = emptyList(),
    val overtimeRequests: List<OvertimeRequest> = emptyList(),
    val adjustments: List<AttendanceAdjustment> = emptyList(),
    val classificationOverrides: List<AttendanceClassificationOverride> = emptyList(),
    val offScheduleReviews: List<OffScheduleAttendanceReview> = emptyList()
)

private fun Query.forEmployee(employeeId: String?): Query =
    employeeId?.takeIf(String::isNotBlank)?.let { whereEqualTo("employeeId", it) } ?: this

private suspend fun Query.getCalculationSnapshot(): QuerySnapshot =
    retryWhileFirestoreIndexBuilds { get(Source.SERVER).await() }

private fun FirebaseRepository.dateQuery(
    collection: String, field: String, start: LocalDate, end: LocalDate, employeeId: String?
): Query = db.collection(collection)
    .whereGreaterThanOrEqualTo(field, start.toString())
    .whereLessThanOrEqualTo(field, end.toString())
    .forEmployee(employeeId)

suspend fun FirebaseRepository.fetchCalculationData(
    startDate: LocalDate,
    endDate: LocalDate,
    employeeId: String? = null
): CalculationData = coroutineScope {
    require(!endDate.isBefore(startDate)) { "Khoảng ngày tính công không hợp lệ" }
    // Adjacent schedule dates let raw scans around midnight resolve to the correct shift.
    val start = startDate.minusDays(1)
    val end = endDate.plusDays(1)
    val schedules = async {
        dateQuery("workSchedules", "date", start, end, employeeId).getCalculationSnapshot()
            .documents.mapNotNull { it.toObject(WorkSchedule::class.java)?.copy(id = it.id) }
    }
    val shifts = async {
        db.collection("shifts").getCalculationSnapshot().documents
            .mapNotNull { it.toObject(WorkShift::class.java)?.copy(id = it.id) }
    }
    val leave = async {
        db.collection("leaveRequests")
            .whereLessThanOrEqualTo("startDate", end.toString())
            .whereGreaterThanOrEqualTo("endDate", start.toString())
            .orderBy("endDate").orderBy("startDate")
            .forEmployee(employeeId).getCalculationSnapshot().documents
            .mapNotNull { it.toObject(LeaveRequest::class.java)?.copy(id = it.id) }
    }
    val overtime = async {
        dateQuery("overtimeRequests", "workDate", start, end, employeeId).getCalculationSnapshot()
            .documents.mapNotNull(::overtimeRequest)
    }
    val adjustments = async {
        dateQuery("attendanceAdjustments", "scheduleDate", start, end, employeeId).getCalculationSnapshot()
            .documents.mapNotNull { it.toAttendanceAdjustment() }
    }
    val classifications = async {
        dateQuery("attendanceClassificationOverrides", "scheduleDate", start, end, employeeId)
            .getCalculationSnapshot().documents.mapNotNull { it.toAttendanceClassificationOverride() }
    }
    val reviews = async {
        dateQuery("offScheduleReviews", "scheduleDate", start, end, employeeId).getCalculationSnapshot()
            .documents.mapNotNull { document ->
                document.toObject(OffScheduleAttendanceReview::class.java)?.copy(id = document.id)?.let { review ->
                    if (review.status != "PENDING") review else when (review.decision) {
                        "APPROVE" -> review.copy(status = "APPROVED")
                        "REJECT" -> review.copy(status = "REJECTED")
                        else -> review
                    }
                }
            }
    }
    CalculationData(schedules.await(), shifts.await(), leave.await(), overtime.await(),
        adjustments.await(), classifications.await(), reviews.await())
}
