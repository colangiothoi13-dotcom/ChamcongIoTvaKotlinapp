package vn.chamcong.iot.ui.employee

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.ShiftCategory
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift

class EmployeeSchedulePresentationTest {
    @Test
    fun leapMonthKeepsAllDatesInMondayFirstCalendarRows() {
        val dates = employeeScheduleMonthDates(YearMonth.of(2024, 2))

        assertEquals(35, dates.size)
        assertEquals(listOf(null, null, null), dates.take(3))
        assertEquals(LocalDate.of(2024, 2, 1), dates[3])
        assertEquals(LocalDate.of(2024, 2, 29), dates[31])
        assertTrue(dates.drop(32).all { it == null })
        assertEquals(29, dates.filterNotNull().size)
    }

    @Test
    fun monthStartingOnSundayUsesSixRowsAndPadsLastWeek() {
        val dates = employeeScheduleMonthDates(YearMonth.of(2026, 3))

        assertEquals(42, dates.size)
        assertTrue(dates.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 3, 1), dates[6])
        assertEquals(LocalDate.of(2026, 3, 31), dates[36])
        assertTrue(dates.drop(37).all { it == null })
    }

    @Test
    fun mondayFirstCompleteMonthHasNoExtraCalendarRow() {
        val dates = employeeScheduleMonthDates(YearMonth.of(2027, 2))

        assertEquals(28, dates.size)
        assertEquals(LocalDate.of(2027, 2, 1), dates.first())
        assertEquals(LocalDate.of(2027, 2, 28), dates.last())
        assertEquals(28, dates.filterNotNull().size)
    }

    @Test
    fun weekIncludesBothMonthsAndEndsOnSunday() {
        val dates = employeeScheduleWeekDates(LocalDate.of(2026, 10, 1))

        assertEquals(LocalDate.of(2026, 9, 28), dates.first())
        assertEquals(LocalDate.of(2026, 10, 4), dates.last())
        assertEquals(7, dates.size)
        assertEquals(dates, employeeScheduleWeekDates(dates.last()))
    }

    @Test
    fun shiftIdsOnlyAssignmentRemainsVisibleAndRemovesBlankDuplicates() {
        val schedule = WorkSchedule(shiftIds = listOf("morning", "", "afternoon", "morning", " "))

        assertEquals(listOf("morning", "afternoon"), employeeScheduleShiftIds(schedule))
    }

    @Test
    fun legacyShiftIsFallbackOnlyWhenMultiShiftListHasNoUsableIds() {
        assertEquals(listOf("morning"), employeeScheduleShiftIds(WorkSchedule(shiftId = "morning")))
        assertEquals(listOf("morning"), employeeScheduleShiftIds(
            WorkSchedule(shiftId = "morning", shiftIds = listOf("", " "))
        ))
        assertEquals(listOf("afternoon"), employeeScheduleShiftIds(
            WorkSchedule(shiftId = "morning", shiftIds = listOf("afternoon"))
        ))
        assertTrue(employeeScheduleShiftIds(WorkSchedule()).isEmpty())
    }

    @Test
    fun registrationRetainsLegacySelectionsAndOrdersMorningBeforeAfternoon() {
        val shifts = registrationShifts()

        assertEquals(listOf("legacy-morning", "afternoon"), employeeRegistrationShiftIds(
            listOf("afternoon", "legacy-morning", "morning", "legacy-morning"), shifts
        ))
    }

    @Test
    fun registrationCopyFiltersUnavailableAndSupplementaryShifts() {
        val shifts = registrationShifts() + mapOf(
            "inactive" to WorkShift(id = "inactive", active = false),
            "supplementary" to WorkShift(id = "supplementary", category = ShiftCategory.SUPPLEMENTARY.name)
        )

        assertEquals(listOf("morning", "afternoon"), employeeRegistrationShiftIds(
            listOf("missing", "supplementary", "inactive", "", "afternoon", "morning"), shifts
        ))
    }

    @Test
    fun registrationCategoryToggleClearsLegacyMorningAndPreservesAfternoon() {
        val shifts = registrationShifts()

        assertEquals(listOf("afternoon"), employeeToggleRegistrationShift(
            listOf("legacy-morning", "morning", "afternoon"), shifts.getValue("morning"), shifts
        ))
        assertEquals(listOf("morning", "afternoon"), employeeToggleRegistrationShift(
            listOf("afternoon"), shifts.getValue("morning"), shifts
        ))
    }

    @Test
    fun registrationToggleAddsAndRemovesAfternoonIndependently() {
        val shifts = registrationShifts()
        val afternoon = shifts.getValue("afternoon")

        assertEquals(listOf("morning", "afternoon"), employeeToggleRegistrationShift(
            listOf("morning"), afternoon, shifts
        ))
        assertEquals(listOf("morning"), employeeToggleRegistrationShift(
            listOf("afternoon", "morning"), afternoon, shifts
        ))
        assertTrue(employeeToggleRegistrationShift(listOf("afternoon"), afternoon, shifts).isEmpty())
    }

    private fun registrationShifts(): Map<String, WorkShift> = listOf(
        WorkShift(id = "morning", category = ShiftCategory.MORNING.name),
        WorkShift(id = "legacy-morning", category = ShiftCategory.MORNING.name),
        WorkShift(id = "afternoon", category = ShiftCategory.EVENING.name,
            startTime = "13:00", endTime = "17:00")
    ).associateBy(WorkShift::id)
}
