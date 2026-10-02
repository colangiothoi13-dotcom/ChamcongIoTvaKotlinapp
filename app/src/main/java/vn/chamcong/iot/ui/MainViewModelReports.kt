// Chức năng: Tính bảng công, thưởng KPI và dữ liệu xuất báo cáo từ trạng thái hiện tại.
package vn.chamcong.iot.ui

import kotlinx.coroutines.flow.StateFlow
import vn.chamcong.iot.domain.employeeMonthSummaries as buildEmployeeMonthSummaries
import vn.chamcong.iot.domain.employeeApprovedLeaveShifts
import vn.chamcong.iot.domain.KpiBonusBreakdown
import vn.chamcong.iot.domain.calculateMonthlyKpiBonuses
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.EmployeeDaySummary
import vn.chamcong.iot.model.AttendanceReportRow
import vn.chamcong.iot.model.DeviceActivityRow
import vn.chamcong.iot.model.ReportFilter
import vn.chamcong.iot.model.ReportType
import vn.chamcong.iot.model.RequestStatus
import vn.chamcong.iot.model.RequestType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal class MainReportQueries(
    private val _state: StateFlow<MainUiState>,
    private val zoneId: ZoneId
) {
    fun employeeMonthSummaries(month: LocalDate = LocalDate.now()): List<EmployeeDaySummary> {
        val employee = _state.value.currentEmployee ?: return emptyList()
        val approvedLeaveDates = _state.value.employeeRequests
            .filter { it.status == RequestStatus.APPROVED.name && it.type == RequestType.LEAVE.name && it.leaveShiftsByDate == null }
            .flatMap { request ->
                val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
                if (start == null || end == null || end.isBefore(start)) emptyList()
                else generateSequence(start) { current ->
                    current.plusDays(1).takeUnless { it.isAfter(end) }
                }.toList()
            }
            .toSet()
        val approvedLeaveShifts = employeeApprovedLeaveShifts(
            employee.id, _state.value.employeeSchedules, _state.value.shifts, _state.value.employeeRequests
        )
        return buildEmployeeMonthSummaries(
            employeeId = employee.id,
            month = month,
            attendance = _state.value.employeeAttendanceForSummaries,
            schedules = _state.value.effectiveEmployeeSchedules,
            shifts = _state.value.shifts,
            approvedLeaveDates = approvedLeaveDates,
            approvedLeaveShiftsByDate = approvedLeaveShifts,
            zoneId = zoneId,
            adjustments = _state.value.attendanceAdjustments,
            overtimeRequests = _state.value.employeeOvertimeRequests
        )
    }

    fun employeeMonthSummaries(employeeId: String, month: YearMonth): List<EmployeeDaySummary> {
        val current = _state.value
        val leaveDates = current.leaveRequests
            .asSequence()
            .filter { it.employeeId == employeeId && it.status == RequestStatus.APPROVED.name && it.type == RequestType.LEAVE.name && it.leaveShiftsByDate == null }
            .flatMap { request ->
                val start = runCatching { LocalDate.parse(request.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(request.endDate) }.getOrNull()
                if (start == null || end == null || end.isBefore(start)) emptySequence()
                else generateSequence(start) { date -> date.plusDays(1).takeUnless { it.isAfter(end) } }
            }
            .filter { YearMonth.from(it) == month }
            .toSet()
        val approvedLeaveShifts = employeeApprovedLeaveShifts(
            employeeId, current.schedules, current.shifts, current.leaveRequests
        )
        return buildEmployeeMonthSummaries(
            employeeId = employeeId,
            month = month.atDay(1),
            attendance = current.historicalAttendanceForSummaries,
            schedules = current.effectiveSchedules,
            shifts = current.shifts,
            approvedLeaveDates = leaveDates,
            approvedLeaveShiftsByDate = approvedLeaveShifts,
            zoneId = zoneId,
            adjustments = current.attendanceAdjustments,
            overtimeRequests = current.overtimeRequests
        )
    }

    fun reportAttendanceRows(filter: ReportFilter): List<AttendanceReportRow> = vn.chamcong.iot.domain.attendanceReportRows(
        filter = filter,
        employees = _state.value.historicalEmployees(YearMonth.from(filter.startDate)),
        attendance = _state.value.historicalAttendanceForSummaries,
        schedules = _state.value.effectiveSchedules,
        shifts = _state.value.shifts,
        approvedRequests = _state.value.leaveRequests,
        zoneId = zoneId,
        adjustments = _state.value.attendanceAdjustments
    )

    fun kpiBonusBreakdowns(month: YearMonth): Map<String, KpiBonusBreakdown> {
        val current = _state.value
        return calculateMonthlyKpiBonuses(
            employees = current.historicalEmployees(month),
            month = month,
            attendance = current.historicalAttendanceForSummaries,
            schedules = current.effectiveSchedules,
            shifts = current.shifts,
            overtimeRequests = current.overtimeRequests,
            adjustments = current.attendanceAdjustments,
            zoneId = zoneId
        )
    }

    fun reportDeviceRows(): List<DeviceActivityRow> = vn.chamcong.iot.domain.deviceActivityRows(
        devices = _state.value.devices,
        failedCommands = _state.value.commands
    )

    fun reportCsv(type: ReportType, filter: ReportFilter): String = when (type) {
        ReportType.DEVICE_ACTIVITY -> vn.chamcong.iot.data.deviceRowsToCsv(reportDeviceRows())
        ReportType.ATTENDANCE, ReportType.WORK_SUMMARY, ReportType.LATE_EARLY, ReportType.LEAVE, ReportType.OVERTIME -> {
            val rows = reportAttendanceRows(filter).filter { row ->
                when (type) {
                    ReportType.LATE_EARLY -> row.status == "LATE" || row.status == "EARLY_LEAVE"
                    ReportType.LEAVE -> row.status == "LEAVE"
                    ReportType.OVERTIME -> row.overtimeHours > 0
                    else -> true
                }
            }
            vn.chamcong.iot.data.attendanceRowsToCsv(rows)
        }
    }
}
