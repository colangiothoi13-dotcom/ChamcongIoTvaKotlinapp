package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WorkSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class AttendanceResolutionRulesTest {
    @Test
    fun pendingScanCanBeConfirmedOrDiscardedWithoutChangingRawScan() {
        val instant = Instant.parse("2026-09-14T01:00:00Z")
        val raw = Attendance(id = "scan-1", employeeId = "e1", employeeName = "An",
            type = "SCAN", status = "PENDING", resolutionStatus = "PENDING",
            timestamp = Timestamp(Date.from(instant)))
        val base = AttendanceClassificationOverride(attendanceId = raw.id, employeeId = raw.employeeId,
            employeeName = raw.employeeName, scheduleDate = "2026-09-14", sourceTimestamp = instant,
            sourceType = raw.type, sourceStatus = raw.status, sourceResolutionStatus = raw.resolutionStatus,
            previousType = raw.type, previousStatus = raw.status, correctedType = "CHECK_IN",
            correctedStatus = "NORMAL", reason = "Admin confirmed", actorId = "admin", actorName = "Admin")
        val confirmed = applyAttendanceClassificationOverrides(listOf(raw), listOf(base)).single()
        assertTrue(isAcceptedAttendance(confirmed))
        assertEquals("SCAN", raw.type)
        val discarded = applyAttendanceClassificationOverrides(listOf(raw),
            listOf(base.copy(correctedType = "DISCARDED", correctedStatus = "DISCARDED"))).single()
        assertFalse(isAcceptedAttendance(discarded))
        assertEquals("DISCARDED", discarded.type)
    }
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
    fun standardShiftUsesConfiguredLateAndEarlyLeaveGrace() {
        val shift = WorkShift(
            category = "MORNING",
            startTime = "08:00",
            endTime = "12:00",
            lateGraceMinutes = 10,
            earlyLeaveAllowedMinutes = 15
        )

        assertEquals(0, attendanceLateMinutes(Instant.parse("2026-09-14T01:10:00Z"), scheduleDate, shift, zone))
        assertEquals(11, attendanceLateMinutes(Instant.parse("2026-09-14T01:11:00Z"), scheduleDate, shift, zone))
        assertEquals(0, attendanceEarlyLeaveMinutes(Instant.parse("2026-09-14T04:45:00Z"), scheduleDate, shift, zone))
        assertEquals(30, attendanceEarlyLeaveMinutes(Instant.parse("2026-09-14T04:30:00Z"), scheduleDate, shift, zone))
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

    @Test
    fun sparkApprovedOvertimeHasItsOwnAcceptedSession() {
        val regular = WorkShift(id = "evening", name = "Ca chiều", category = "EVENING",
            startTime = "13:00", endTime = "17:00")
        val schedule = WorkSchedule(employeeId = "e1", date = scheduleDate.toString(), shiftId = regular.id)
        val request = OvertimeRequest(id = "ot-1", employeeId = "e1", employeeName = "An",
            workDate = scheduleDate.toString(), startTime = "18:00", endTime = "22:00", status = "APPROVED")
        val rows = listOf(rawScan("2026-09-14T10:00:00Z", "regular-out"),
            rawScan("2026-09-14T11:00:00Z", "overtime-in"),
            rawScan("2026-09-14T15:00:00Z", "overtime-out"))

        val resolved = resolveSparkPendingAttendance(rows, listOf(schedule), listOf(regular), zone,
            overtimeRequests = listOf(request))

        assertEquals(listOf("CHECK_OUT", "CHECK_IN", "CHECK_OUT"), resolved.map { it.type })
        assertEquals(listOf("evening", SUPPLEMENTARY_SHIFT_ID, SUPPLEMENTARY_SHIFT_ID), resolved.map { it.shiftId })
        assertTrue(resolved.all(::isAcceptedAttendance))
        assertEquals(listOf("ot-1", "ot-1"), resolved.takeLast(2).map { it.overtimeRequestId })
    }

    @Test
    fun sparkApprovedOffScheduleReviewTurnsUnscheduledScansIntoAttendance() {
        val shift = WorkShift(id = "morning", name = "Ca sáng", category = "MORNING",
            startTime = "08:00", endTime = "12:00")
        val review = OffScheduleAttendanceReview(employeeId = "e1", employeeName = "An",
            scheduleDate = scheduleDate.toString(), shiftId = shift.id, shiftName = shift.name,
            decision = "APPROVE", status = "PENDING", reviewerId = "admin", reviewerName = "Admin")
        val rows = listOf("2026-09-14T01:00:00Z", "2026-09-14T05:00:00Z").mapIndexed { index, time ->
            rawScan(time, "off-$index").copy(type = "UNSCHEDULED", status = "ABNORMAL",
                resolutionStatus = AttendanceResolutionStatus.UNSCHEDULED.name)
        }

        val resolved = resolveSparkPendingAttendance(rows, emptyList(), listOf(shift), zone, reviews = listOf(review))

        assertEquals(listOf("CHECK_IN", "CHECK_OUT"), resolved.map { it.type })
        assertTrue(resolved.all(::isAcceptedAttendance))
        assertTrue(resolved.all { it.offScheduleReviewStatus == "APPROVED" })
        assertEquals("UNSCHEDULED", rows.first().type)
        assertEquals(shift.id, applyApprovedOffScheduleReviewsToSchedules(emptyList(), listOf(review), listOf(shift)).single().shiftId)
    }

    @Test
    fun sparkApprovedOffScheduleReviewAlsoResolvesRawPendingScans() {
        val shift = WorkShift(id = "morning", name = "Ca sáng", category = "MORNING",
            startTime = "08:00", endTime = "12:00")
        val review = OffScheduleAttendanceReview(employeeId = "e1", employeeName = "An",
            scheduleDate = scheduleDate.toString(), shiftId = shift.id, shiftName = shift.name,
            decision = "APPROVE", status = "PENDING", reviewerId = "admin", reviewerName = "Admin")
        val rows = listOf(rawScan("2026-09-14T01:00:00Z", "raw-in"),
            rawScan("2026-09-14T05:00:00Z", "raw-out"))

        val resolved = resolveSparkPendingAttendance(rows, emptyList(), listOf(shift), zone,
            reviews = listOf(review))

        assertEquals(listOf("CHECK_IN", "CHECK_OUT"), resolved.map { it.type })
        assertTrue(resolved.all(::isAcceptedAttendance))
        assertTrue(resolved.all { it.offScheduleReviewStatus == "APPROVED" })
        assertTrue(rows.all { it.type == "SCAN" })
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
