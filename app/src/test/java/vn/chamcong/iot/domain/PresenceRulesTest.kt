package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.PresenceStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class PresenceRulesTest {
    @Test
    fun rawShortShiftUsesCustomGraceAndLegacyOvernightRowsIncludeNextDay() {
        val day = vn.chamcong.iot.model.WorkShift(startTime = "08:00", endTime = "10:00", missingCheckOutGraceMinutes = 15)
        val rows = listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z"))
        assertEquals(PresenceStatus.PRESENT, classifyPresence(Employee(id = "e1"), rows, emptyList(), date, zone, Instant.parse("2026-09-14T03:15:00Z"), shift = day).status)
        assertEquals(PresenceStatus.MISSING_CHECK_OUT, classifyPresence(Employee(id = "e1"), rows, emptyList(), date, zone, Instant.parse("2026-09-14T03:15:01Z"), shift = day).status)
        val nightRows = listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T15:00:00Z"), attendance("e1", "CHECK_OUT", "NORMAL", "2026-09-14T23:00:00Z"))
        assertEquals(PresenceStatus.LEFT, classifyPresence(Employee(id = "e1"), nightRows, emptyList(), date, zone, shift = day.copy(startTime = "22:00", endTime = "06:00")).status)
    }

    @Test
    fun rawOvernightCheckInUsesShiftEndGraceAcrossMidnight() {
        val night = vn.chamcong.iot.model.WorkShift(startTime = "22:00", endTime = "06:00")
        val rows = listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T15:00:00Z").copy(scheduleDate = date.toString()))
        for ((time, expected) in listOf("2026-09-15T00:00:00Z" to PresenceStatus.PRESENT, "2026-09-15T00:00:01Z" to PresenceStatus.MISSING_CHECK_OUT)) {
            assertEquals(expected, classifyPresence(Employee(id = "e1"), rows, emptyList(), date, zone, Instant.parse(time), shift = night).status)
        }
    }

    @Test
    fun duplicateCheckoutCannotMarkEmployeeLeft() {
        val rows = listOf(
            attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z"),
            attendance("e1", "CHECK_OUT", "NORMAL", "2026-09-14T02:00:00Z").copy(resolutionStatus = "DUPLICATE")
        )
        assertEquals(PresenceStatus.PRESENT, classifyPresence(Employee(id = "e1"), rows, emptyList(), date, zone, Instant.parse("2026-09-14T03:00:00Z")).status)
    }

    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = LocalDate.of(2026, 9, 14)

    @Test
    fun classifiesEmployeeWithoutAttendanceAsNotCheckedIn() {
        val result = classifyPresence(Employee(id = "e1", fullName = "An"), emptyList(), emptyList(), date, zone, now = instant("2026-09-14T03:00:00Z"))

        assertEquals(PresenceStatus.NOT_CHECKED_IN, result.status)
    }

    @Test
    fun approvedLeaveTakesPriorityOverAttendance() {
        val result = classifyPresence(
            Employee(id = "e1"),
            listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z")),
            listOf(approvedLeave("e1", "2026-09-14", "2026-09-14")),
            date,
            zone,
            now = instant("2026-09-14T03:00:00Z")
        )

        assertEquals(PresenceStatus.ON_LEAVE, result.status)
    }

    @Test
    fun latestCheckoutClassifiesEmployeeAsLeft() {
        val result = classifyPresence(
            Employee(id = "e1"),
            listOf(
                attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z"),
                attendance("e1", "CHECK_OUT", "NORMAL", "2026-09-14T10:00:00Z")
            ),
            emptyList(), date, zone, now = instant("2026-09-14T11:00:00Z")
        )

        assertEquals(PresenceStatus.LEFT, result.status)
    }

    @Test
    fun recentCheckInClassifiesEmployeeAsPresent() {
        val result = classifyPresence(
            Employee(id = "e1"),
            listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z")),
            emptyList(), date, zone, now = instant("2026-09-14T03:00:00Z")
        )

        assertEquals(PresenceStatus.PRESENT, result.status)
    }

    @Test
    fun oldCheckInWithoutCheckoutClassifiesAsMissingCheckout() {
        val result = classifyPresence(
            Employee(id = "e1"),
            listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z")),
            emptyList(), date, zone, now = instant("2026-09-15T03:00:00Z")
        )

        assertEquals(PresenceStatus.MISSING_CHECK_OUT, result.status)
    }

    @Test
    fun unverifiedLatestAttendanceClassifiesAsAbnormal() {
        val result = classifyPresence(
            Employee(id = "e1"),
            listOf(attendance("e1", "CHECK_IN", "NORMAL", "2026-09-14T01:00:00Z", verified = false)),
            emptyList(), date, zone, now = instant("2026-09-14T03:00:00Z")
        )

        assertEquals(PresenceStatus.ABNORMAL, result.status)
    }

    @Test
    fun listHelperIncludesOnlyActiveEmployees() {
        val result = classifyPresenceForEmployees(
            listOf(Employee(id = "active", active = true), Employee(id = "retired", active = false)),
            emptyList(), emptyList(), date, zone, now = instant("2026-09-14T03:00:00Z")
        )

        assertEquals(listOf("active"), result.map { it.employee.id })
    }

    private fun attendance(employeeId: String, type: String, status: String, instant: String, verified: Boolean = true) = Attendance(
        employeeId = employeeId,
        type = type,
        status = status,
        verified = verified,
        timestamp = Timestamp(Date.from(Instant.parse(instant)))
    )

    private fun approvedLeave(employeeId: String, start: String, end: String) = vn.chamcong.iot.model.LeaveRequest(
        employeeId = employeeId,
        type = vn.chamcong.iot.model.RequestType.LEAVE.name,
        startDate = start,
        endDate = end,
        status = vn.chamcong.iot.model.RequestStatus.APPROVED.name
    )

    private fun instant(value: String) = Instant.parse(value)
}
