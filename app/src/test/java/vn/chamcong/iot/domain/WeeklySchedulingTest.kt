package vn.chamcong.iot.domain

import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.WorkSchedule
import java.time.Instant
import java.time.LocalDate

class WeeklySchedulingTest {
    private val monday = LocalDate.parse("2026-09-21")
    private val employees = listOf(Employee(id = "e1", fullName = "An", department = "IT"), Employee(id = "e2", fullName = "Binh"))

    @Test fun weekSpansYearAndSundayBelongsToPreviousMonday() {
        val dates = weekDates(LocalDate.parse("2027-01-03"))
        assertEquals("2026-12-28", dates.first().toString())
        assertEquals("2027-01-03", dates.last().toString())
        assertEquals(7, dates.distinct().size)
        assertEquals(LocalDate.parse("2027-01-04"), weekDates(LocalDate.parse("2027-01-04")).first())
    }

    @Test fun incompleteWeekBecomesOverdueExactlyAtSunday17VietnamTime() {
        assertFalse(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-20T09:59:59Z")).overdue)
        assertTrue(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-20T10:00:00Z")).overdue)
        assertTrue(weeklyScheduleStatus(monday, employees, emptyList(), Instant.parse("2026-09-20T10:00:01Z")).overdue)
    }

    @Test fun coverageCountsOnlyActiveEmployeesWithAssignmentsInsideWeek() {
        val status = weeklyScheduleStatus(monday, employees + Employee(id = "inactive", active = false), listOf(
            WorkSchedule(employeeId = "e1", date = "2026-09-21", shiftId = "s"),
            WorkSchedule(employeeId = "e2", date = "2026-09-20", shiftId = "s")
        ), Instant.parse("2026-09-20T10:00:00Z"))
        assertEquals(listOf("e2"), status.missingEmployeeIds)
        val complete = weeklyScheduleStatus(monday, employees.take(1), listOf(WorkSchedule(employeeId = "e1", date = "2026-09-27", shiftId = "s")), Instant.parse("2026-09-28T00:00:00Z"))
        assertFalse(complete.overdue)
    }

    @Test fun templatesResolveToRequiredTimesAndStableDistinctIds() {
        val templates = defaultShiftTemplates()
        assertEquals(3, templates.size)
        val morning = templates[0].resolve()
        val afternoon = templates[1].resolve()
        assertEquals("08:00", morning.startTime)
        assertEquals("12:00", morning.endTime)
        assertEquals("13:00", afternoon.startTime)
        assertEquals("17:00", afternoon.endTime)
        assertEquals(morning.id, templates[0].resolve().id)
        assertNull(templates[2].startTime)
        assertEquals("19:00", templates[2].resolve("18:00", "19:00").endTime)
        assertNotEquals(templates[2].resolve("18:00", "19:00").id, templates[2].resolve("18:00", "20:00").id)
    }

    @Test fun supplementalRequiresStrictNonEqualTimesAndSupportsOvernight() {
        val template = defaultShiftTemplates()[2]
        listOf("" to "", "24:00" to "25:00", "8:00" to "09:00", "18:00" to "18:00").forEach { (start, end) ->
            assertTrue(runCatching { template.resolve(start, end) }.isFailure)
        }
        assertEquals("06:00", template.resolve("22:00", "06:00").endTime)
    }

    @Test fun bulkPayloadIsSelectedEmployeeDateProductWithCanonicalIds() {
        val payload = weeklyAssignmentPayload(employees, setOf("e2", "e1"), monday, setOf(monday, monday.plusDays(6)), defaultShiftTemplates()[0].resolve(), "admin")
        assertEquals(setOf("e1_2026-09-21", "e1_2026-09-27", "e2_2026-09-21", "e2_2026-09-27"), payload.map { it.id }.toSet())
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
