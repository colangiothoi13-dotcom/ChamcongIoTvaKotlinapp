package vn.chamcong.iot.model

/** Complete, server-read history for one immutable scan and its employee's workday. */
data class AttendanceRecordHistory(
    val source: Attendance,
    val scheduleDate: String,
    val classifications: List<AttendanceClassificationOverride> = emptyList(),
    val adjustments: List<AttendanceAdjustment> = emptyList(),
    val offScheduleReviews: List<OffScheduleAttendanceReview> = emptyList(),
    val audits: List<AuditLog> = emptyList()
)
