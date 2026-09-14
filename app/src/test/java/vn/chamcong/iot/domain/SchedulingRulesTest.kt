package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceType
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class SchedulingRulesTest {
    @Test
    fun acceptsOnlyTheThreeSupportedShiftCategories() {
        listOf(
            ShiftCategory.MORNING.name,
            ShiftCategory.EVENING.name,
            ShiftCategory.SUPPLEMENTARY.name
        ).forEach { category ->
            validateShift(WorkShift(name = category, category = category, effectiveFrom = "2026-09-14"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOvertimeOutsideZeroToThreeHours() {
        validateOvertimeHours(4)
    }

    @Test
    fun weekDatesAlwaysReturnsMondayThroughSunday() {
        val result = weekDates(mondayOfWeek(LocalDate.of(2026, 9, 16)))

        assertEquals(LocalDate.of(2026, 9, 14), result.first())
        assertEquals(LocalDate.of(2026, 9, 20), result.last())
        assertEquals(7, result.size)
    }

    @Test
    fun subtractsLunchAndCountsSelectedOvertimeOnlyWhenShiftAllowsIt() {
        val shift = WorkShift(
            name = "Ca sáng",
            category = ShiftCategory.MORNING.name,
            startTime = "08:00",
            endTime = "17:00",
            breakStartTime = "12:00",
            breakEndTime = "13:00",
            countsOvertime = true,
            effectiveFrom = "2026-09-14"
        )

        val result = calculateWorkTime(
            Instant.parse("2026-09-14T01:00:00Z"),
            Instant.parse("2026-09-14T11:00:00Z"),
            shift,
            overtimeHours = 1,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        )

        assertEquals(9.0, result.workedHours, 0.01)
        assertEquals(1.0, result.overtimeHours, 0.01)
        assertTrue(result.dayWorked)
    }

    @Test
    fun copyingWeekPreservesWeekdayAndSkipsExistingTarget() {
        val source = listOf(
            WorkSchedule(
                id = "e1_2026-09-14",
                employeeId = "e1",
                shiftId = "s1",
                date = "2026-09-14",
                overtimeHours = 2
            ),
            WorkSchedule(
                id = "e2_2026-09-15",
                employeeId = "e2",
                shiftId = "s1",
                date = "2026-09-15",
                overtimeHours = 0
            )
        )

        val result = copyScheduleToNextWeek(source, setOf("e1_2026-09-21"))

        assertEquals(listOf("e2_2026-09-22"), result.map { it.id })
        assertEquals("2026-09-22", result.single().date)
        assertEquals(0, result.single().overtimeHours)
    }

    @Test
    fun summarizesWorkedHoursAndWorkdaysForTheSelectedWeek() {
        val weekStart = LocalDate.of(2026, 9, 14)
        val shift = WorkShift(
            name = "Ca sáng",
            category = ShiftCategory.MORNING.name,
            startTime = "08:00",
            endTime = "17:00",
            breakStartTime = "12:00",
            breakEndTime = "13:00",
            effectiveFrom = weekStart.toString()
        )
        val result = summarizeWeeklyWork(
            employees = listOf(Employee(id = "e1", active = true)),
            attendance = listOf(
                attendance("e1", AttendanceType.CHECK_IN.name, "2026-09-14T01:00:00Z"),
                attendance("e1", AttendanceType.CHECK_OUT.name, "2026-09-14T10:00:00Z")
            ),
            schedules = listOf(WorkSchedule(employeeId = "e1", shiftId = "s1", date = "2026-09-14")),
            shifts = mapOf("s1" to shift),
            approvedRequests = emptyList(),
            weekStart = weekStart,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        )

        assertEquals(8.0, result.totalWorkedHours, 0.01)
        assertEquals(1, result.workdays)
        assertEquals(0.0, result.totalOvertimeHours, 0.01)
        assertEquals(8.0, result.dailyWorkedHours[weekStart] ?: 0.0, 0.01)
    }

    @Test
    fun usesAdminWorkedHoursOverrideWhenAttendanceIsIncomplete() {
        val weekStart = LocalDate.of(2026, 9, 14)
        val result = summarizeWeeklyWork(
            employees = listOf(Employee(id = "e1", active = true)),
            attendance = listOf(attendance("e1", AttendanceType.CHECK_IN.name, "2026-09-14T01:00:00Z")),
            schedules = listOf(
                WorkSchedule(
                    employeeId = "e1",
                    shiftId = "s1",
                    date = "2026-09-14",
                    workedHoursOverride = 7.5,
                    adjustmentNote = "Quên chấm ra"
                )
            ),
            shifts = mapOf("s1" to WorkShift(name = "Ca sáng", effectiveFrom = weekStart.toString())),
            approvedRequests = emptyList(),
            weekStart = weekStart,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        )

        assertEquals(7.5, result.totalWorkedHours, 0.01)
        assertEquals(1, result.workdays)
        assertEquals(0, result.unauthorizedAbsenceDays)
    }

    private fun attendance(employeeId: String, type: String, instant: String) = Attendance(
        employeeId = employeeId,
        type = type,
        timestamp = Timestamp(Date.from(Instant.parse(instant)))
    )
}
