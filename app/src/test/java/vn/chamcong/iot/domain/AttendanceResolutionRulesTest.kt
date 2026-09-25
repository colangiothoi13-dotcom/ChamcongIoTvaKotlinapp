package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class AttendanceResolutionRulesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val scheduleDate = LocalDate.of(2026, 9, 14)
    private val overnightShift = WorkShift(
        name = "Ca dem",
        startTime = "22:00",
        endTime = "06:00",
        effectiveFrom = scheduleDate.toString()
    )

    @Test
    fun legacyAttendanceDefaultsToAcceptedResolution() {
        assertEquals(AttendanceResolutionStatus.ACCEPTED.name, Attendance().resolutionStatus)
    }

    @Test
    fun shiftDefaultsToSixtyMinuteMissingCheckoutGrace() {
        assertEquals(60, WorkShift().missingCheckOutGraceMinutes)
    }

    @Test
    fun sparkRejectedOffScheduleReviewIsShownAsRejectedImmediately() {
        val row = Attendance(
            id = "scan-1",
            employeeId = "e1",
            type = "UNSCHEDULED",
            status = "ABNORMAL",
            resolutionStatus = AttendanceResolutionStatus.UNSCHEDULED.name,
            scheduleDate = scheduleDate.toString(),
            timestamp = Timestamp(Date.from(Instant.parse("2026-09-14T03:00:00Z")))
        )
        val review = OffScheduleAttendanceReview(
            employeeId = "e1",
            scheduleDate = scheduleDate.toString(),
            shiftId = "morning",
            decision = "REJECT",
            status = "PENDING",
            reviewerId = "admin-1",
            reviewerName = "Admin"
        )

        val effective = applyOffScheduleReviewDecisions(listOf(row), listOf(review), zone).single()

        assertEquals("REJECTED", effective.offScheduleReviewStatus)
        assertEquals("admin-1", effective.offScheduleReviewerId)
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

    @Test
    fun overnightShiftWindowEndsOnTheFollowingDate() {
        val window = shiftWindow(scheduleDate, overnightShift, zone)

        assertEquals(Instant.parse("2026-09-14T15:00:00Z"), window.start)
        assertEquals(Instant.parse("2026-09-14T23:00:00Z"), window.end)
    }

    @Test
    fun resolvesAcceptedOvernightEventsUnderTheirScheduleDate() {
        val pair = resolveAttendancePair(
            rows = listOf(
                attendance(AttendanceType.CHECK_IN.name, "2026-09-14T16:00:00Z"),
                attendance(AttendanceType.CHECK_OUT.name, "2026-09-14T22:30:00Z")
            ),
            scheduleDate = scheduleDate,
            shift = overnightShift,
            zoneId = zone
        )

        assertEquals(scheduleDate, pair.scheduleDate)
        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), pair.checkIn)
        assertEquals(Instant.parse("2026-09-14T22:30:00Z"), pair.checkOut)
    }

    @Test
    fun excludesDuplicateUnscheduledAndOutOfOrderEvents() {
        val pair = resolveAttendancePair(
            rows = listOf(
                attendance(AttendanceType.CHECK_IN.name, "2026-09-14T16:00:00Z"),
                attendance(AttendanceType.CHECK_OUT.name, "2026-09-14T16:01:00Z", AttendanceResolutionStatus.DUPLICATE.name),
                attendance(AttendanceType.CHECK_OUT.name, "2026-09-14T16:02:00Z", AttendanceResolutionStatus.UNSCHEDULED.name),
                attendance(AttendanceType.CHECK_OUT.name, "2026-09-14T16:03:00Z", AttendanceResolutionStatus.OUT_OF_ORDER.name)
            ),
            scheduleDate = scheduleDate,
            shift = overnightShift,
            zoneId = zone
        )

        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), pair.checkIn)
        assertNull(pair.checkOut)
    }

    @Test
    fun newestValidAdjustmentSuppliesMissingRawCheckoutWithoutMutatingRows() {
        val rawRows = listOf(attendance(AttendanceType.CHECK_IN.name, "2026-09-14T16:00:00Z"))
        val old = adjustment("old", "2026-09-14T22:00:00Z", "2026-09-14T20:00:00Z")
        val newest = adjustment("new", "2026-09-14T23:00:00Z", "2026-09-14T22:30:00Z")

        val pair = resolveAttendancePair(rawRows, scheduleDate, overnightShift, listOf(old, newest), zone)

        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), pair.checkIn)
        assertEquals(Instant.parse("2026-09-14T22:30:00Z"), pair.checkOut)
        assertEquals("new", pair.adjustment?.id)
        assertEquals(1, rawRows.size)
        assertNull(rawRows.single().scheduleDate)
    }

    @Test
    fun doesNotMarkOpenPairMissingBeforeShiftGraceExpires() {
        val pair = vn.chamcong.iot.model.AttendancePair(scheduleDate, Instant.parse("2026-09-14T16:00:00Z"))

        assertFalse(isMissingCheckOut(pair, scheduleDate, overnightShift, Instant.parse("2026-09-14T23:59:00Z"), zone))
    }

    @Test
    fun marksOpenPairMissingAfterShiftGraceExpires() {
        val pair = vn.chamcong.iot.model.AttendancePair(scheduleDate, Instant.parse("2026-09-14T16:00:00Z"))

        assertTrue(isMissingCheckOut(pair, scheduleDate, overnightShift, Instant.parse("2026-09-15T00:01:00Z"), zone))
    }

    @Test
    fun neverMarksCompletedPairMissing() {
        val pair = vn.chamcong.iot.model.AttendancePair(
            scheduleDate,
            Instant.parse("2026-09-14T16:00:00Z"),
            Instant.parse("2026-09-14T22:30:00Z")
        )

        assertFalse(isMissingCheckOut(pair, scheduleDate, overnightShift, Instant.parse("2026-09-15T01:00:00Z"), zone))
    }

    @Test
    fun treatsAnOpenPairWithoutShiftAsMissingOnlyAfterItsScheduleDate() {
        val pair = vn.chamcong.iot.model.AttendancePair(scheduleDate, Instant.parse("2026-09-14T16:00:00Z"))

        assertFalse(isMissingCheckOut(pair, scheduleDate, null, Instant.parse("2026-09-14T16:01:00Z"), zone))
        assertTrue(isMissingCheckOut(pair, scheduleDate, null, Instant.parse("2026-09-15T00:00:00Z"), zone))
    }

    @Test
    fun resolvesSparkPendingScansLocallyAcrossTwoSeparateShifts() {
        val morning = WorkShift(
            id = "morning",
            name = "Ca sáng",
            category = "MORNING",
            startTime = "08:00",
            endTime = "12:00",
            allowEarlyMinutes = 120
        )
        val afternoon = WorkShift(
            id = "afternoon",
            name = "Ca chiều",
            category = "EVENING",
            startTime = "13:00",
            endTime = "17:00"
        )
        val schedule = WorkSchedule(
            employeeId = "e1",
            date = scheduleDate.toString(),
            shiftId = morning.id,
            shiftIds = listOf(morning.id, afternoon.id)
        )
        val rawRows = listOf(
            rawScan("2026-09-14T03:00:00Z", "in-morning"),
            rawScan("2026-09-14T05:00:00Z", "out-morning"),
            rawScan("2026-09-14T06:00:00Z", "in-afternoon"),
            rawScan("2026-09-14T10:30:00Z", "out-afternoon")
        )

        val resolved = resolveSparkPendingAttendance(rawRows, listOf(schedule), listOf(morning, afternoon), zone)

        assertEquals(listOf("CHECK_IN", "CHECK_OUT", "CHECK_IN", "CHECK_OUT"), resolved.map { it.type })
        assertEquals(listOf("LATE", "NORMAL", "NORMAL", "NORMAL"), resolved.map { it.status })
        assertEquals(listOf("morning", "morning", "afternoon", "afternoon"), resolved.map { it.shiftId })
        assertTrue(resolved.all { it.resolutionStatus == AttendanceResolutionStatus.ACCEPTED.name })
        assertEquals("SCAN", rawRows.first().type)
        assertEquals(AttendanceResolutionStatus.PENDING.name, rawRows.first().resolutionStatus)
    }

    @Test
    fun resolvesSparkEarlyCheckoutWithoutChangingFirestoreRow() {
        val morning = WorkShift(
            id = "morning",
            category = "MORNING",
            startTime = "08:00",
            endTime = "12:00",
            allowEarlyMinutes = 120
        )
        val schedule = WorkSchedule(
            employeeId = "e1",
            date = scheduleDate.toString(),
            shiftId = morning.id,
            shiftIds = listOf(morning.id)
        )
        val rawRows = listOf(
            rawScan("2026-09-14T03:00:00Z", "in"),
            rawScan("2026-09-14T04:30:00Z", "early-out")
        )

        val resolved = resolveSparkPendingAttendance(rawRows, listOf(schedule), listOf(morning), zone)

        assertEquals("EARLY_LEAVE", resolved.last().status)
        assertEquals("SCAN", rawRows.last().type)
    }

    private fun attendance(type: String, instant: String, resolutionStatus: String = AttendanceResolutionStatus.ACCEPTED.name) = Attendance(
        employeeId = "e1",
        type = type,
        timestamp = Timestamp(Date.from(Instant.parse(instant))),
        resolutionStatus = resolutionStatus
    )

    private fun rawScan(instant: String, id: String) = Attendance(
        id = id,
        employeeId = "e1",
        employeeName = "An",
        type = "SCAN",
        status = "PENDING",
        resolutionStatus = AttendanceResolutionStatus.PENDING.name,
        syncStatus = "PENDING_SYNC",
        timestamp = Timestamp(Date.from(Instant.parse(instant)))
    )

    private fun adjustment(id: String, createdAt: String, checkOutAt: String) = AttendanceAdjustment(
        id = id,
        employeeId = "e1",
        employeeName = "An",
        scheduleDate = scheduleDate.toString(),
        checkOutAt = Instant.parse(checkOutAt),
        reason = "Forgot checkout",
        actorId = "admin",
        actorName = "Admin",
        createdAt = Instant.parse(createdAt)
    )
}
