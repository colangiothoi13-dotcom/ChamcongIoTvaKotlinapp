// Chức năng: Tính bảng công, thưởng KPI và dữ liệu xuất báo cáo từ trạng thái hiện tại.
package vn.chamcong.iot.ui

import kotlinx.coroutines.flow.StateFlow
import vn.chamcong.iot.domain.employeeMonthSummaries as buildEmployeeMonthSummaries
import vn.chamcong.iot.domain.filterAttendanceReportRows
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ReportType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal class MainReportQueries(
    private val _state: StateFlow<MainUiState>,
    private val zoneId: ZoneId
) {
    fun employeeMonthSummaries(month: LocalDate = LocalDate.now()): List<EmployeeDaySummary> {
        val current = _state.value
        val employee = current.currentEmployee ?: return emptyList()
        return buildEmployeeMonthSummaries(
            employeeId = employee.id,
            month = month,
            attendance = current.employeeAttendanceForSummaries,
            schedules = current.effectiveEmployeeSchedules,
            shifts = current.calculationShifts,
            approvedLeaveDates = emptySet(),
            zoneId = zoneId,
            adjustments = current.calculationAdjustments,
            overtimeRequests = current.calculationEmployeeOvertimeRequests,
            leaveRequests = current.calculationEmployeeLeaveRequests
        )
    }

    fun employeeMonthSummaries(employeeId: String, month: YearMonth): List<EmployeeDaySummary> {
        val current = _state.value
        return buildEmployeeMonthSummaries(
            employeeId = employeeId,
            month = month.atDay(1),
            attendance = current.historicalAttendanceForSummaries,
            schedules = current.effectiveSchedules,
            shifts = current.calculationShifts,
            approvedLeaveDates = emptySet(),
            zoneId = zoneId,
            adjustments = current.calculationAdjustments,
            overtimeRequests = current.calculationOvertimeRequests,
            leaveRequests = current.calculationLeaveRequests
        )
    }

    fun reportAttendanceRows(filter: ReportFilter): List<AttendanceReportRow> = vn.chamcong.iot.domain.attendanceReportRows(
        filter = filter,
        employees = _state.value.historicalEmployees(YearMonth.from(filter.startDate)),
        attendance = _state.value.historicalAttendanceForSummaries,
        schedules = _state.value.effectiveSchedules,
        shifts = _state.value.calculationShifts,
        approvedRequests = _state.value.calculationLeaveRequests,
        zoneId = zoneId,
        adjustments = _state.value.calculationAdjustments,
        overtimeRequests = _state.value.calculationOvertimeRequests
    )

    fun kpiBonusBreakdowns(month: YearMonth): Map<String, KpiBonusBreakdown> {
        val current = _state.value
        return calculateMonthlyKpiBonuses(
            employees = current.historicalEmployees(month),
            month = month,
            attendance = current.historicalAttendanceForSummaries,
            schedules = current.effectiveSchedules,
            shifts = current.calculationShifts,
            overtimeRequests = current.calculationOvertimeRequests,
            adjustments = current.calculationAdjustments,
            zoneId = zoneId,
            leaveRequests = current.calculationLeaveRequests
        )
    }

    fun reportDeviceRows(): List<DeviceActivityRow> = vn.chamcong.iot.domain.deviceActivityRows(
        devices = _state.value.devices,
        failedCommands = _state.value.commands
    )

    fun reportCsv(type: ReportType, filter: ReportFilter): String = when (type) {
        ReportType.DEVICE_ACTIVITY -> vn.chamcong.iot.data.deviceRowsToCsv(reportDeviceRows())
        ReportType.ATTENDANCE, ReportType.WORK_SUMMARY, ReportType.LATE_EARLY, ReportType.LEAVE, ReportType.OVERTIME -> {
            check(_state.value.hasCompleteCalculationRange(filter.startDate, filter.endDate, filter.employeeId)) {
                "Cần tải đủ lịch, lượt quét, nghỉ phép, tăng ca và điều chỉnh trước khi xuất báo cáo"
            }
            val rows = filterAttendanceReportRows(reportAttendanceRows(filter), type)
            vn.chamcong.iot.data.attendanceRowsToCsv(rows)
        }
    }
}
