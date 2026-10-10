package vn.chamcong.iot.ui.workitems

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date

class WorkItemAttendanceEvidenceTest {
    private val date = LocalDate.parse("2026-10-08")
    private val morning = WorkShift(id = "morning", name = "Morning", category = "MORNING",
        startTime = "08:00", endTime = "12:00", allowEarlyMinutes = 120)
    private val schedule = WorkSchedule(id = "e1_2026-10-08", employeeId = "e1",
        date = date.toString(), shiftId = morning.id, shiftIds = listOf(morning.id))
    private val item = WorkItem(id = "work-1", assigneeId = "e1", relatedScheduleId = schedule.id,
        relatedScheduleDate = schedule.date, relatedShiftId = morning.id)

    private fun scan(id: String, time: String, type: String = "CHECK_IN", day: LocalDate = date) = Attendance(
        id = id, employeeId = "e1", type = type, deviceId = "GATE-01",
        timestamp = Timestamp(Date.from(day.atTime(LocalTime.parse(time)).atZone(workZone).toInstant()))
    )

    @Test fun singleShiftLegacyScansWithoutEitherTagStillShowPresence() {
        val rows = listOf(scan("in", "08:00"), scan("out", "12:00", "CHECK_OUT"))
        val evidence = workItemAttendanceEvidence(item, rows, listOf(schedule), listOf(morning))
        assertEquals(listOf("in", "out"), evidence.map { it.id })
        assertEquals(listOf(null, null), rows.map { it.scheduleDate })
        assertEquals(listOf(null, null), rows.map { it.shiftId })
    }

    @Test fun dateTaggedSingleShiftScansWithoutShiftIdStillShowPresence() {
        val rows = listOf(scan("in", "08:00").copy(scheduleDate = date.toString()),
            scan("out", "12:00", "CHECK_OUT").copy(scheduleDate = date.toString()))
        assertEquals(listOf("in", "out"),
            workItemAttendanceEvidence(item, rows, listOf(schedule), listOf(morning)).map { it.id })
    }

    @Test fun explicitForeignShiftAndOtherEmployeeNeverBecomeRelatedEvidence() {
        val rows = listOf(scan("own", "08:00"),
            scan("foreign-shift", "08:00").copy(scheduleDate = date.toString(), shiftId = "evening"),
            scan("other-employee", "08:00").copy(employeeId = "e2"))
        assertEquals(listOf("own"),
            workItemAttendanceEvidence(item, rows, listOf(schedule), listOf(morning)).map { it.id })
    }

    @Test fun overnightLegacyCheckoutBelongsToPreviousScheduleDate() {
        val night = morning.copy(id = "night", category = "NIGHT", startTime = "22:00", endTime = "06:00")
        val nightSchedule = schedule.copy(shiftId = night.id, shiftIds = listOf(night.id))
        val nightItem = item.copy(relatedShiftId = night.id)
        val rows = listOf(scan("in", "22:00"), scan("out", "06:00", "CHECK_OUT", date.plusDays(1)))
        assertEquals(listOf("in", "out"),
            workItemAttendanceEvidence(nightItem, rows, listOf(nightSchedule), listOf(night)).map { it.id })
    }

    @Test fun overlappingMultiShiftTieIsAttributedOnceRegardlessOfInputOrder() {
        val evening = morning.copy(id = "evening", category = "EVENING", startTime = "13:00", endTime = "17:00",
            allowEarlyMinutes = 150)
        val both = schedule.copy(shiftIds = listOf(evening.id, morning.id))
        val row = scan("tie", "10:30")
        val morningEvidence = workItemAttendanceEvidence(item, listOf(row), listOf(both), listOf(evening, morning))
        assertEquals(listOf("tie"), morningEvidence.map { it.id })
        assertTrue(workItemAttendanceEvidence(item.copy(relatedShiftId = evening.id), listOf(row),
            listOf(both), listOf(morning, evening)).isEmpty())
    }

    @Test fun rejectedAndUnverifiedRowsAreExcludedAndRetryCopiesAppearOnlyOnce() {
        val accepted = scan("accepted", "08:00")
        val rows = listOf(accepted, accepted,
            scan("unverified", "08:00").copy(verified = false),
            scan("duplicate", "08:01").copy(type = "DUPLICATE", resolutionStatus = "DUPLICATE", status = "ABNORMAL"),
            scan("pending", "08:02").copy(type = "SCAN", resolutionStatus = "PENDING", status = "PENDING"))
        assertEquals(listOf("accepted"),
            workItemAttendanceEvidence(item, rows, listOf(schedule), listOf(morning)).map { it.id })
    }

    @Test fun reassignedTaskUsesOnlyTheNewAssigneePresence() {
        val reassigned = item.copy(assigneeId = "e2", relatedScheduleId = "e2_2026-10-08")
        val otherSchedule = schedule.copy(id = reassigned.relatedScheduleId!!, employeeId = "e2")
        val rows = listOf(scan("original", "08:00"), scan("replacement", "08:00").copy(employeeId = "e2"))
        assertEquals(listOf("replacement"), workItemAttendanceEvidence(reassigned, rows,
            listOf(schedule, otherSchedule), listOf(morning)).map { it.id })
    }
}
