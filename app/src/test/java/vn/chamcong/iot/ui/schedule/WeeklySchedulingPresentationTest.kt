package vn.chamcong.iot.ui.schedule

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.domain.defaultShiftTemplates
import vn.chamcong.iot.domain.weeklyAssignmentPayload
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceResolutionStatus
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkShift
import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Date

class WeeklySchedulingPresentationTest {
    @Test fun staleAndInactiveSelectionsAreIdentifiedAndCanBeRemovedToRestoreSave() {
        val employees = listOf(Employee(id = "active"), Employee(id = "retired", active = false))
        val selected = setOf("active", "retired", "missing")
        val unavailable = unavailableWeeklyEmployeeIds(employees, selected)
        assertEquals(setOf("retired", "missing"), unavailable)
        val monday = LocalDate.parse("2026-09-21")
        val shift = defaultShiftTemplates()[0].resolve()
        assertTrue(runCatching { weeklyAssignmentPayload(employees, selected, monday, setOf(monday), shift, "admin") }.isFailure)
        val recovered = weeklyAssignmentPayload(employees, selected - unavailable, monday, setOf(monday), shift, "admin")
        assertEquals(listOf("active_2026-09-21"), recovered.map { it.id })
    }

    @Test fun removingAllUnavailableSelectionsRequiresChoosingAnotherEmployee() {
        val selected = setOf("retired", "missing")
        val employees = listOf(Employee(id = "retired", active = false))
        val recovered = selected - unavailableWeeklyEmployeeIds(employees, selected)
        assertTrue(recovered.isEmpty())
        assertEquals("Chọn ít nhất một nhân viên", runCatching {
            val monday = LocalDate.parse("2026-09-21")
            weeklyAssignmentPayload(employees, recovered, monday, setOf(monday), defaultShiftTemplates()[0].resolve(), "admin")
        }.exceptionOrNull()?.message)
    }

    @Test fun supplementalPickerDistinguishesSameNameAndExplainsOvernightEnd() {
        val shift = WorkShift(name = "Ca bổ sung", category = "SUPPLEMENTARY", startTime = "18:00", endTime = "20:00")
        assertEquals("Ca bổ sung • 18:00–20:00", scheduleShiftLabel(shift))
        assertEquals("Ca bổ sung • 22:00–06:00 (ngày hôm sau)", scheduleShiftLabel(shift.copy(startTime = "22:00", endTime = "06:00")))
        assertNotEquals(scheduleShiftLabel(shift), scheduleShiftLabel(shift.copy(endTime = "21:00")))
    }

    @Test fun ordinaryShiftKeepsItsExistingPickerName() {
        assertEquals("Ca sáng", scheduleShiftLabel(WorkShift(name = "Ca sáng")))
    }

    @Test fun assignmentPickerKeepsAllAssignableMainShiftsVisible() {
        val shifts = listOf(
            WorkShift(id = "morning", name = "Ca sáng", category = ShiftCategory.MORNING.name),
            WorkShift(id = "evening", name = "Ca chiều", category = ShiftCategory.EVENING.name),
            WorkShift(id = "overtime", name = "Ca bổ sung", category = ShiftCategory.SUPPLEMENTARY.name)
        )

        assertEquals(listOf("morning", "evening"), assignableScheduleShifts(shifts).map { it.id })
    }

    @Test fun scheduleShowsEmployeeAsWorkingAfterLateCheckIn() {
        val date = LocalDate.parse("2026-09-21")
        val shift = WorkShift(
            id = "morning",
            name = "Ca sáng",
            category = ShiftCategory.MORNING.name,
            startTime = "08:00",
            endTime = "12:00",
            allowEarlyMinutes = 120
        )
        val attendance = listOf(
            Attendance(
                id = "scan-1",
                employeeId = "employee-1",
                type = "CHECK_IN",
                status = "LATE",
                resolutionStatus = AttendanceResolutionStatus.ACCEPTED.name,
                shiftId = shift.id,
                scheduleDate = date.toString(),
                timestamp = Timestamp(Date.from(Instant.parse("2026-09-21T03:00:00Z")))
            )
        )

        val status = scheduleShiftStatus(
            employeeId = "employee-1",
            date = date,
            shift = shift,
            attendance = attendance,
            zoneId = java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
            now = Instant.parse("2026-09-21T03:30:00Z")
        )

        assertEquals(ScheduleStatusTone.ACTIVE, status.tone)
        assertTrue(status.label.contains("2 giờ"))
    }
}
