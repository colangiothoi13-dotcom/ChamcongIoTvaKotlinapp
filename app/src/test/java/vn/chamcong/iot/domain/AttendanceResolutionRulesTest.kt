package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.WorkShift

class AttendanceResolutionRulesTest {
    @Test
    fun legacyAttendanceDefaultsToAcceptedResolution() {
        assertEquals(AttendanceResolutionStatus.ACCEPTED.name, Attendance().resolutionStatus)
    }

    @Test
    fun shiftDefaultsToSixtyMinuteMissingCheckoutGrace() {
        assertEquals(60, WorkShift().missingCheckOutGraceMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeMissingCheckoutGrace() {
        validateShift(
            WorkShift(
                name = "Ca sáng",
                effectiveFrom = "2026-09-17",
                missingCheckOutGraceMinutes = -1
            )
        )
    }
}
