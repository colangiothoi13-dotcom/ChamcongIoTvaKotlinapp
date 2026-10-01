package vn.chamcong.iot.domain

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import java.time.Instant
import java.time.LocalDate

class WeeklySchedulingTest {
    private val monday = LocalDate.parse("2026-09-21")
    private val employees = listOf(Employee(id = "e1", fullName = "An", department = "IT"), Employee(id = "e2", fullName = "Binh"))

    @Test fun weekSpansYearAndSaturdayBelongsToPreviousMonday() {
        val dates = weekDates(LocalDate.parse("2027-01-03"))
        assertEquals("2026-12-28", dates.first().toString())
        assertEquals("2027-01-02", dates.last().toString())
        assertEquals(6, dates.distinct().size)
        assertEquals(LocalDate.parse("2027-01-04"), weekDates(LocalDate.parse("2027-01-04")).first())
    }

    @Test fun incompleteWeekBecomesOverdueExactlyAtSaturday17VietnamTime() {
        assertFalse(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-19T09:59:59Z")).overdue)
        assertTrue(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-19T10:00:00Z")).overdue)
        assertTrue(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-19T10:00:01Z")).overdue)
    }

    @Test fun coverageCountsOnlyActiveEmployeesWithAssignmentsInsideWeek() {
        val status = weeklyScheduleStatus(monday, employees + Employee(id = "inactive", active = false), listOf(
            WorkSchedule(employeeId = "e1", date = "2026-09-21", shiftId = "s"),
            WorkSchedule(employeeId = "e2", date = "2026-09-20", shiftId = "s")
        ), Instant.parse("2026-09-20T10:00:00Z"))
        assertEquals(listOf("e2"), status.missingEmployeeIds)
        val complete = weeklyScheduleStatus(monday, employees.take(1), listOf(WorkSchedule(employeeId = "e1", date = "2026-09-26", shiftId = "s")), Instant.parse("2026-09-28T00:00:00Z"))
        assertFalse(complete.overdue)
    }

    @Test fun templatesResolveToRequiredTimesAndStableDistinctIds() {
        val templates = defaultShiftTemplates()
        assertEquals(2, templates.size)
        val morning = templates[0].resolve()
        val afternoon = templates[1].resolve()
        assertEquals("08:00", morning.startTime)
        assertEquals("12:00", morning.endTime)
        assertEquals("13:00", afternoon.startTime)
        assertEquals("17:00", afternoon.endTime)
        assertEquals(morning.id, templates[0].resolve().id)
        assertNotEquals(morning.id, afternoon.id)
        assertTrue(templates.all { it.category in setOf("MORNING", "EVENING") })
    }

    @Test fun weeklyAssignmentReusesStoredShiftSettingsButRejectsChangedTimeWindow() {
        val morning = defaultShiftTemplates().first().resolve()
        val stored = morning.copy(name = "Ca sáng hiện tại", allowEarlyMinutes = 30, lateGraceMinutes = 5)
        assertEquals(stored, weeklyAssignmentShift(morning, stored))
        assertEquals(morning, weeklyAssignmentShift(morning, null))
        assertTrue(runCatching {
            weeklyAssignmentShift(morning, stored.copy(endTime = "11:30"))
        }.isFailure)
        assertTrue(runCatching {
            weeklyAssignmentShift(morning, stored.copy(active = false))
        }.isFailure)
    }

    @Test fun weeklyAssignmentKeepsOtherDayShiftAndEarlierHourAdjustmentOnRetry() {
        val morning = defaultShiftTemplates()[0].resolve()
        val afternoon = defaultShiftTemplates()[1].resolve()
        val incoming = WorkSchedule(employeeId = "e1", employeeName = "An", date = "2026-09-21",
            shiftId = morning.id, shiftIds = listOf(morning.id), shiftName = morning.name)
        val existing = WorkSchedule(employeeId = "e1", date = incoming.date,
            shiftId = afternoon.id, shiftIds = listOf(afternoon.id), shiftName = afternoon.name,
            overtimeHours = 2, workedHoursOverride = 7.5, adjustmentNote = "Đã duyệt", note = "Ghi chú cũ")
        val shifts = listOf(morning, afternoon).associateBy { it.id }
        val merged = mergeWeeklyAssignment(incoming, existing, morning, shifts)
        assertEquals(listOf(morning.id, afternoon.id), merged.shiftIds)
        assertEquals("Ca sáng + Ca chiều", merged.shiftName)
        assertEquals(2, merged.overtimeHours)
        assertEquals(7.5, merged.workedHoursOverride!!, 0.0)
        assertEquals("Đã duyệt", merged.adjustmentNote)
        assertEquals("Ghi chú cũ", merged.note)
        assertEquals(merged, mergeWeeklyAssignment(incoming, merged, morning, shifts))
    }

    @Test fun weeklyAssignmentReplacesOnlyShiftOfSameCategory() {
        val morning = defaultShiftTemplates()[0].resolve()
        val afternoon = defaultShiftTemplates()[1].resolve()
        val oldMorning = morning.copy(id = "old_morning", startTime = "07:00", endTime = "11:00")
        val incoming = WorkSchedule(employeeId = "e1", date = "2026-09-21", shiftId = morning.id)
        val existing = incoming.copy(shiftId = oldMorning.id, shiftIds = listOf(oldMorning.id, afternoon.id))
        val merged = mergeWeeklyAssignment(incoming, existing, morning,
            listOf(morning, afternoon, oldMorning).associateBy { it.id })
        assertEquals(listOf(morning.id, afternoon.id), merged.shiftIds)
        assertFalse(oldMorning.id in merged.shiftIds)
    }

    @Test fun callerConstructedSupplementaryAndReservedVirtualShiftsCannotBeAssigned() {
        val custom = WorkShift(id = "custom-overtime", name = "Overtime", category = "SUPPLEMENTARY",
            startTime = "18:00", endTime = "21:00", effectiveFrom = "2026-01-01")
        listOf(custom, custom.copy(id = SUPPLEMENTARY_SHIFT_ID),
            custom.copy(id = SUPPLEMENTARY_SHIFT_ID, category = "MORNING")).forEach { shift ->
            assertTrue(runCatching {
                weeklyAssignmentPayload(employees, setOf("e1"), monday, setOf(monday), shift, "admin")
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun scheduleWriteValidationAllowsMainShiftsAndRejectsSupplementaryShifts() {
        defaultShiftTemplates().forEach { validateScheduleShift(it.resolve()) }
        val supplementary = WorkShift(id = "legacy-overtime", name = "Legacy overtime",
            category = "SUPPLEMENTARY", effectiveFrom = "2026-01-01")
        listOf(supplementary, supplementary.copy(id = SUPPLEMENTARY_SHIFT_ID, category = "MORNING")).forEach {
            assertFalse(canAssignScheduleShift(it))
            assertTrue(runCatching { validateScheduleShift(it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun bulkPayloadIsSelectedEmployeeDateProductWithCanonicalIds() {
        val payload = weeklyAssignmentPayload(employees, setOf("e2", "e1"), monday, setOf(monday, monday.plusDays(5)), defaultShiftTemplates()[0].resolve(), "admin")
        assertEquals(setOf("e1_2026-09-21", "e1_2026-09-26", "e2_2026-09-21", "e2_2026-09-26"), payload.map { it.id }.toSet())
        assertEquals(4, payload.size)
        assertTrue(payload.all { it.assignedBy == "admin" && it.shiftName == "Ca sáng" })
        assertEquals("IT", payload.first { it.employeeId == "e1" }.department)
    }

    @Test fun invalidSelectionsAreRejectedBeforeAnyPayloadIsSaved() {
        val shift = defaultShiftTemplates()[0].resolve()
        assertTrue(runCatching { weeklyAssignmentPayload(employees, emptySet(), monday, setOf(monday), shift, "a") }.isFailure)
        assertTrue(runCatching { weeklyAssignmentPayload(employees, setOf("e1"), monday, emptySet(), shift, "a") }.isFailure)
        assertTrue(runCatching { weeklyAssignmentPayload(employees, setOf("missing"), monday, setOf(monday), shift, "a") }.isFailure)
        assertTrue(runCatching { weeklyAssignmentPayload(employees, setOf("e1"), monday, setOf(monday.minusDays(1)), shift, "a") }.isFailure)
        assertTrue(runCatching { weeklyAssignmentPayload(employees.map { it.copy(active = false) }, setOf("e1"), monday, setOf(monday), shift, "a") }.isFailure)
    }
}
