package vn.chamcong.iot.ui

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.data.CalculationData
import vn.chamcong.iot.domain.attendanceReportRows
import vn.chamcong.iot.model.*
import java.time.*
import java.util.Date

class CalculationReadinessStateTest {
    private val start = LocalDate.parse("2020-01-01")
    private val end = LocalDate.parse("2020-01-31")
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant()
    private fun timestamp(value: String) = Timestamp(Date.from(at(value)))
    private fun complete(employeeId: String? = null) = MainUiState(
        attendanceHistoryQueryKey = "$start|$end|${employeeId.orEmpty()}",
        historicalCalculationData = CalculationData()
    )

    @Test fun readinessRequiresServerCalculationContextEvenWhenAttendanceQueryIsComplete() {
        val loaded = complete()
        assertTrue(loaded.hasCompleteCalculationRange(start, end))
        assertFalse(loaded.copy(historicalCalculationData = null).hasCompleteCalculationRange(start, end))
        assertFalse(loaded.copy(attendanceHistoryQueryKey = null).hasCompleteCalculationRange(start, end))
        // An empty server result is complete; a non-null bundle represents all dependencies finishing.
        assertTrue(loaded.copy(historicalAttendance = emptyList(), attendanceHistoryLoadedCount = 0)
            .hasCompleteCalculationRange(start, end))
    }

    @Test fun loadingErrorsAndTruncationBlockReadinessUntilACompleteRetry() {
        val loaded = complete()
        assertFalse(loaded.copy(attendanceHistoryLoading = true).hasCompleteCalculationRange(start, end))
        assertFalse(loaded.copy(attendanceHistoryError = "Mất mạng").hasCompleteCalculationRange(start, end))
        assertFalse(loaded.copy(attendanceHistoryTruncated = true).hasCompleteCalculationRange(start, end))
        assertTrue(loaded.hasCompleteCalculationRange(start, end))
    }

    @Test fun readinessMatchesExactPeriodAndEmployeeScopeAndNormalizesWhitespace() {
        val loaded = complete("e1")
        assertTrue(loaded.hasCompleteCalculationRange(start, end, "e1"))
        assertTrue(loaded.hasCompleteCalculationRange(start, end, "  e1  "))
        assertFalse(loaded.hasCompleteCalculationRange(start, end, "e2"))
        assertFalse(loaded.hasCompleteCalculationRange(start, end))
        assertFalse(loaded.hasCompleteCalculationRange(start.plusDays(1), end, "e1"))
        assertFalse(loaded.hasCompleteCalculationRange(start, end.minusDays(1), "e1"))
        assertFalse(loaded.hasCompleteCalculationRange(start.minusDays(1), end.plusDays(1), "e1"))
        assertTrue(complete().hasCompleteCalculationRange(start, end, "   "))
        assertFalse(complete().hasCompleteCalculationRange(start, end, "e1"))
    }

    @Test fun accountSubscriptionResetCannotReuseEarlierAccountReadiness() {
        val reset = complete("e1").copy(
            historicalCalculationData = null, historicalAttendance = emptyList(),
            attendanceHistoryQueryKey = null, attendanceHistoryLoading = false,
            attendanceHistoryError = null, attendanceHistoryTruncated = false, attendanceHistoryLoadedCount = 0
        )
        assertFalse(reset.hasCompleteCalculationRange(start, end, "e1"))
        assertFalse(reset.hasCompleteCalculationRange(start, end, "e2"))
        assertFalse(reset.hasCompleteCalculationRange(start, end))
    }

    @Test fun historicalNightUsesNextMonthCheckoutAndCorrectionCreatedYearsLaterEverywhere() {
        val date = end
        val night = WorkShift(id = "old-night", name = "Ca đêm cũ", startTime = "22:00", endTime = "06:00",
            effectiveFrom = "2019-01-01")
        val schedule = WorkSchedule(id = "e1_$date", employeeId = "e1", date = date.toString(), shiftId = night.id)
        val rows = listOf(
            Attendance(id = "old-in", employeeId = "e1", type = "CHECK_IN", timestamp = timestamp("2020-01-31T22:20")),
            Attendance(id = "old-out", employeeId = "e1", type = "CHECK_OUT", timestamp = timestamp("2020-02-01T06:00"))
        )
        val correction = AttendanceAdjustment(id = "late-created", employeeId = "e1", employeeName = "An",
            scheduleDate = date.toString(), checkInAt = at("2020-01-31T22:00"), reason = "Sửa lượt vào cũ",
            actorId = "admin", actorName = "Admin", createdAt = at("2026-10-04T09:00"))
        val state = complete().copy(
            employees = listOf(Employee(id = "e1", fullName = "An")), historicalAttendance = rows,
            selectedWeekStart = LocalDate.parse("2020-01-27"), selectedPresenceDate = date,
            attendanceDatePreset = "SINGLE", attendanceDateFilter = date.toString(),
            historicalCalculationData = CalculationData(schedules = listOf(schedule), shifts = listOf(night),
                adjustments = listOf(correction))
        )
        assertTrue(state.schedules.isEmpty())
        assertTrue(state.shifts.isEmpty())
        assertTrue(state.attendanceAdjustments.isEmpty())
        assertEquals(8.0, state.weeklyWorkSummary.totalWorkedHours, 0.001)
        assertEquals(0, state.weeklyWorkSummary.lateCount)
        assertEquals(0, state.dashboard.lateEmployees)
        assertEquals(listOf("old-in", "old-out"), state.visibleAttendance.map { it.id })
        assertTrue(state.copy(attendanceStatusFilter = "LATE").visibleAttendance.isEmpty())
        val report = attendanceReportRows(ReportFilter(startDate = date, endDate = date), state.employees,
            state.attendanceForSummaries, state.effectiveSchedules, state.calculationShifts,
            state.calculationLeaveRequests, zone, state.calculationAdjustments).single()
        assertEquals(8.0, report.workedHours, 0.001)
        assertEquals("06:00", report.checkOut)
        assertEquals(8.0, workedHoursForMonth(state.attendanceForSummaries, "e1", YearMonth.of(2020, 1), zone,
            state.effectiveSchedules, state.calculationShifts, state.calculationAdjustments,
            state.calculationOvertimeRequests, state.calculationLeaveRequests), 0.001)
        assertEquals(0.0, workedHoursForMonth(state.attendanceForSummaries, "e1", YearMonth.of(2020, 2), zone,
            state.effectiveSchedules, state.calculationShifts, state.calculationAdjustments,
            state.calculationOvertimeRequests, state.calculationLeaveRequests), 0.001)
    }

    @Test fun historicalOvertimeOnlyApprovalFeedsWeeklyReportAndPayrollFromContext() {
        val date = end
        val overtime = OvertimeRequest(id = "approved-old-overtime", employeeId = "e1", workDate = date.toString(), status = "APPROVED")
        val state = complete().copy(
            employees = listOf(Employee(id = "e1", fullName = "An")), selectedWeekStart = LocalDate.parse("2020-01-27"),
            historicalAttendance = listOf(
                Attendance(id = "ot-in", employeeId = "e1", type = "CHECK_IN", shiftId = "SUPPLEMENTARY_1800_2200",
                    scheduleDate = date.toString(), overtimeRequestId = overtime.id, timestamp = timestamp("2020-01-31T18:00")),
                Attendance(id = "ot-out", employeeId = "e1", type = "CHECK_OUT", shiftId = "SUPPLEMENTARY_1800_2200",
                    scheduleDate = date.toString(), overtimeRequestId = overtime.id, timestamp = timestamp("2020-01-31T22:00"))
            ),
            historicalCalculationData = CalculationData(overtimeRequests = listOf(overtime))
        )
        assertEquals(0.0, state.weeklyWorkSummary.totalWorkedHours, 0.001)
        assertEquals(4.0, state.weeklyWorkSummary.totalOvertimeHours, 0.001)
        val report = attendanceReportRows(ReportFilter(startDate = date, endDate = date), state.employees,
            state.attendanceForSummaries, state.effectiveSchedules, state.calculationShifts,
            state.calculationLeaveRequests, zone, state.calculationAdjustments,
            overtimeRequests = state.calculationOvertimeRequests).single()
        assertEquals(0.0, report.workedHours, 0.001)
        assertEquals(4.0, report.overtimeHours, 0.001)
        assertEquals(4.0, workedHoursForMonth(state.attendanceForSummaries, "e1", YearMonth.of(2020, 1), zone,
            state.effectiveSchedules, state.calculationShifts, state.calculationAdjustments,
            state.calculationOvertimeRequests, state.calculationLeaveRequests), 0.001)
    }

    @Test fun employeeContextGettersExcludeAnotherEmployeesHistoricalDependencies() {
        val state = complete("e1").copy(currentEmployee = Employee(id = "e1"), historicalCalculationData = CalculationData(
            schedules = listOf(WorkSchedule(employeeId = "e1", date = end.toString()), WorkSchedule(employeeId = "e2", date = end.toString())),
            leaveRequests = listOf(LeaveRequest(id = "l1", employeeId = "e1"), LeaveRequest(id = "l2", employeeId = "e2")),
            overtimeRequests = listOf(OvertimeRequest(id = "o1", employeeId = "e1"), OvertimeRequest(id = "o2", employeeId = "e2"))
        ))
        assertEquals(listOf("e1"), state.calculationEmployeeSchedules.map { it.employeeId })
        assertEquals(listOf("l1"), state.calculationEmployeeLeaveRequests.map { it.id })
        assertEquals(listOf("o1"), state.calculationEmployeeOvertimeRequests.map { it.id })
    }
}
