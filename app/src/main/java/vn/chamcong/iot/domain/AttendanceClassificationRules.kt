package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Attendance

/** Eligibility follows the immutable source, even when an earlier override discarded it. */
fun canCorrectAttendanceClassification(source: Attendance): Boolean =
    source.id.isNotBlank() && source.employeeId.isNotBlank() && source.verified &&
        (isAcceptedAttendance(source) ||
            source.type == "SCAN" && source.status == "PENDING" && source.resolutionStatus == "PENDING")
