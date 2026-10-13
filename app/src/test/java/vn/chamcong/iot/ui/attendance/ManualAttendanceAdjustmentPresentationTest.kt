package vn.chamcong.iot.ui.attendance

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.ui.MainUiState

class ManualAttendanceAdjustmentPresentationTest {
    private val date = LocalDate.parse("2026-09-17")
    private val morning = WorkShift(id = "morning", name = "Ca sáng", startTime = "08:00", endTime = "12:00")
    private val afternoon = WorkShift(id = "afternoon", name = "Ca chiều", category = "EVENING", startTime = "13:00", endTime = "17:00")

    @Test
    fun singleShiftPrefillsItsPairAndHours() {
        val target = target(state(twoShifts = false))
        assertEquals(instant("01:00"), target.currentCheckIn?.toDate()?.toInstant())
        assertEquals(instant("05:00"), target.currentCheckOut?.toDate()?.toInstant())
        assertEquals(4.0, target.currentWorkedHours!!, 0.001)
    }

    @Test
    fun twoShiftsShowTotalHoursWithoutBridgingTheLunchGap() {
        val target = target(state())
        assertNull(target.currentCheckIn)
        assertNull(target.currentCheckOut)
        assertEquals(8.0, target.currentWorkedHours!!, 0.001)
        assertNull(target.currentWorkedHoursOverride)
    }

    @Test
    fun multiShiftDayCarriesExplicitSavedEndpointsAndOverride() {
        val previous = adjustment("saved", "11:00", "01:15", "05:00", 7.5)
        val target = target(state().copy(attendanceAdjustments = listOf(previous)))
        assertEquals(previous.checkInAt, target.currentCheckIn?.toDate()?.toInstant())
        assertEquals(previous.checkOutAt, target.currentCheckOut?.toDate()?.toInstant())
        assertEquals(7.5, target.currentWorkedHoursOverride!!, 0.001)
    }

    @Test
    fun partialSavedEndpointDoesNotBorrowAnEndpointFromAnotherShift() {
        val previous = adjustment("partial", "11:00", "06:15", null, null)
        val target = target(state().copy(attendanceAdjustments = listOf(previous)))
        assertEquals(previous.checkInAt, target.currentCheckIn?.toDate()?.toInstant())
        assertNull(target.currentCheckOut)
    }

    @Test
    fun newestHoursOnlyAdjustmentDoesNotResurrectOlderEndpoints() {
        val older = adjustment("old", "11:00", "01:00", "05:00", 7.0)
        val latest = adjustment("latest", "12:00", null, null, 6.5)
        val target = target(state().copy(attendanceAdjustments = listOf(older, latest)))
        assertNull(target.currentCheckIn)
        assertNull(target.currentCheckOut)
        assertEquals(6.5, target.currentWorkedHoursOverride!!, 0.001)
        assertEquals(6.5, target.currentWorkedHours!!, 0.001)
    }

    @Test
    fun approvedLeaveStillExcludesItsShiftFromTheDisplayedTotal() {
        val leave = LeaveRequest(id = "leave", employeeId = "e1", startDate = date.toString(), endDate = date.toString(),
            status = "APPROVED", leaveShiftsByDate = mapOf(date.toString() to listOf(morning.id)))
        val target = target(state().copy(leaveRequests = listOf(leave)))
        assertNull(target.currentCheckIn)
        assertNull(target.currentCheckOut)
        assertEquals(4.0, target.currentWorkedHours!!, 0.001)
    }

    private fun target(state: MainUiState) = manualAdjustmentTarget("e1", "Nguyễn Văn A", date, state)

    private fun state(twoShifts: Boolean = true): MainUiState {
        val selected = if (twoShifts) listOf(morning, afternoon) else listOf(morning)
        val schedule = WorkSchedule(id = "e1_$date", employeeId = "e1", date = date.toString(),
            shiftId = morning.id, shiftIds = selected.map { it.id })
        val scans = listOf(scan(morning, "CHECK_IN", "01:00"), scan(morning, "CHECK_OUT", "05:00")) +
            if (twoShifts) listOf(scan(afternoon, "CHECK_IN", "06:00"), scan(afternoon, "CHECK_OUT", "10:00")) else emptyList()
        return MainUiState(attendance = scans, schedules = listOf(schedule), shifts = selected)
    }

    private fun scan(shift: WorkShift, type: String, time: String) = Attendance(
        id = "${shift.id}_$type", employeeId = "e1", employeeName = "Nguyễn Văn A", type = type,
        timestamp = Timestamp(Date.from(instant(time))), scheduleDate = date.toString(), shiftId = shift.id
    )

    private fun adjustment(id: String, created: String, start: String?, end: String?, hours: Double?) = AttendanceAdjustment(
        id = id, employeeId = "e1", employeeName = "Nguyễn Văn A", scheduleDate = date.toString(),
        checkInAt = start?.let(::instant), checkOutAt = end?.let(::instant), workedHoursOverride = hours,
        reason = "Đã xác minh giờ công", actorId = "admin", actorName = "Admin", createdAt = instant(created)
    )

    private fun instant(time: String): Instant = Instant.parse("${date}T${time}:00Z")
}
