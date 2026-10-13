package vn.chamcong.iot.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import vn.chamcong.iot.model.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class AdministrativeReportReadinessTest {
    private val filter = ReportFilter(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))
    private val slip = Payroll(employeeId = "e1", employeeCode = "NV0001", employeeName = "An", month = "2026-10",
        hoursWorked = 1.0, hourlyRate = 25000, baseSalary = 25000, bonus = 0, deduction = 5000, revision = 2)

    @Test fun savedPayrollExportWaitsForAuthoritativeSelectedMonthAndKeepsSnapshotWithNoAttendanceLoaded() {
        val state = MutableStateFlow(MainUiState(payroll = listOf(slip)))
        val queries = MainReportQueries(state, ZoneId.of("Asia/Ho_Chi_Minh"))
        assertThrows(IllegalStateException::class.java) { queries.reportCsv(ReportType.PAYROLL, filter) }
        state.value = state.value.copy(payrollLoadedMonth = YearMonth.parse("2026-09"))
        assertThrows(IllegalStateException::class.java) { queries.reportCsv(ReportType.PAYROLL, filter) }
        state.value = state.value.copy(payrollLoadedMonth = YearMonth.parse("2026-10"), payrollLoading = true)
        assertThrows(IllegalStateException::class.java) { queries.reportCsv(ReportType.PAYROLL, filter) }
        state.value = state.value.copy(payrollLoading = false, payrollError = "Không thể kết nối")
        assertThrows(IllegalStateException::class.java) { queries.reportCsv(ReportType.PAYROLL, filter) }
        state.value = state.value.copy(payrollError = null)
        val csv = queries.reportCsv(ReportType.PAYROLL, filter)
        assertTrue(csv.contains("NV0001,An,e1,2026-10,1.0,25000,25000,0,5000,20000,2"))
        assertEquals(slip, state.value.payroll.single())
        assertFalse(state.value.hasCompleteCalculationRange(filter.startDate, filter.endDate))
    }
}
