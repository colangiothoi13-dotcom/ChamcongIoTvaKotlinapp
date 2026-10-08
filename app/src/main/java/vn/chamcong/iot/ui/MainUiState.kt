// Chức năng: Lưu trạng thái giao diện và tính các danh sách, thống kê hiển thị từ dữ liệu hiện tại.
package vn.chamcong.iot.ui

import vn.chamcong.iot.domain.filterAttendance
import vn.chamcong.iot.domain.filterEmployees
import vn.chamcong.iot.domain.applyAttendanceClassificationOverrides
import vn.chamcong.iot.domain.applyOffScheduleReviewDecisions
import vn.chamcong.iot.domain.applyApprovedOffScheduleReviewsToSchedules
import vn.chamcong.iot.domain.resolveSparkPendingAttendance
import vn.chamcong.iot.domain.classifyPresenceForEmployees
import vn.chamcong.iot.domain.mondayOfWeek
import vn.chamcong.iot.domain.summarizeDashboard
import vn.chamcong.iot.domain.summarizeDailyDashboard
import vn.chamcong.iot.domain.AttendanceDateRange
import vn.chamcong.iot.domain.filterAttendanceByDateRange
import vn.chamcong.iot.domain.parseAttendanceDateRange
import vn.chamcong.iot.domain.summarizeWeeklyWork
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceAdjustment
import vn.chamcong.iot.model.AttendanceClassificationOverride
import vn.chamcong.iot.model.DashboardSummary
import vn.chamcong.iot.model.DailyDashboardSummary
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.Department
import vn.chamcong.iot.model.Announcement
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeResource
import vn.chamcong.iot.model.visibleOutsideRetiredList
import vn.chamcong.iot.model.AppNotification
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.PresenceRecord
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OffScheduleAttendanceReview
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift
import vn.chamcong.iot.model.WeeklyScheduleRequest
import vn.chamcong.iot.model.WeeklyWorkSummary
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MainUiState(
    val signedIn: Boolean = false,
    val profileResolved: Boolean = false,
    val profileError: String? = null,
    val loading: Boolean = false,
    val employees: List<Employee> = emptyList(),
    val attendance: List<Attendance> = emptyList(),
    val historicalAttendance: List<Attendance> = emptyList(),
    val historicalCalculationData: CalculationData? = null,
    val attendanceAdjustments: List<AttendanceAdjustment> = emptyList(),
    val attendanceClassificationOverrides: List<AttendanceClassificationOverride> = emptyList(),
    val offScheduleAttendanceReviews: List<OffScheduleAttendanceReview> = emptyList(),
    val payroll: List<Payroll> = emptyList(),
    val employeePayroll: List<Payroll> = emptyList(),
    val commands: List<Map<String, Any>> = emptyList(),
    val devices: List<DeviceSnapshot> = emptyList(),
    val departments: List<Department> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val employeeResources: List<EmployeeResource> = emptyList(),
    val employeeResourcesLoading: Boolean = false,
    val employeeResourcesError: String? = null,
    val selectedWeekStart: LocalDate = mondayOfWeek(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))),
    val selectedPresenceDate: LocalDate = LocalDate.now(),
    val shifts: List<WorkShift> = emptyList(),
    val schedules: List<WorkSchedule> = emptyList(),
    val weeklyScheduleRequests: List<WeeklyScheduleRequest> = emptyList(),
    val weeklyScheduleRequestsLoading: Boolean = false,
    val weeklyScheduleRequestsError: String? = null,
    val weeklyScheduleRequestsLoadedWeekStart: LocalDate? = null,
    val employeeWeeklyScheduleRequest: WeeklyScheduleRequest? = null,
    val employeeWeeklyScheduleRequestLoading: Boolean = false,
    val employeeWeeklyScheduleRequestError: String? = null,
    val employeeWeeklyScheduleRequestLoadedWeekStart: LocalDate? = null,
    val employeeWeeklyTargetWeekStart: LocalDate = mondayOfWeek(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))).plusWeeks(1),
    val leaveRequests: List<LeaveRequest> = emptyList(),
    val overtimeRequests: List<OvertimeRequest> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val auditLogs: List<AuditLog> = emptyList(),
    val userProfile: UserProfile? = null,
    val currentEmployee: Employee? = null,
    val employeeAttendance: List<Attendance> = emptyList(),
    val employeeAttendanceHistory: List<Attendance> = emptyList(),
    val employeeAttendanceHistoryLoading: Boolean = false,
    val employeeAttendanceHistoryHasMore: Boolean = false,
    val employeeSchedules: List<WorkSchedule> = emptyList(),
    val selectedEmployeeScheduleMonth: YearMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")),
    val employeeRequests: List<LeaveRequest> = emptyList(),
    val employeeOvertimeRequests: List<OvertimeRequest> = emptyList(),
    val employeeNotifications: List<AppNotification> = emptyList(),
    val selectedRequestFilter: String? = null,
    val selectedAdminTimesheetMonth: YearMonth = YearMonth.now(),
    val selectedPayrollMonth: YearMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")),
    val employeeQuery: String = "",
    val departmentFilter: String? = null,
    val showRetired: Boolean = false,
    val attendanceStatusFilter: String? = null,
    val attendanceTypeFilter: String? = null,
    val attendanceDateFilter: String = "",
    val attendanceDatePreset: String? = null,
    val attendanceRangeStart: String = "",
    val attendanceRangeEnd: String = "",
    val attendanceEmployeeFilter: String? = null,
    val attendanceDepartmentFilter: String? = null,
    val saving: Boolean = false,
    val attendanceHistoryLoading: Boolean = false,
    val attendanceHistoryQueryKey: String? = null,
    val attendanceHistoryError: String? = null,
    val attendanceHistoryTruncated: Boolean = false,
    val attendanceHistoryLoadedCount: Int = 0,
    val message: String? = null,
    val error: String? = null
) {
    val employeeWeeklyScheduleRequestReady: Boolean
        get() = employeeWeeklyScheduleRequestLoadedWeekStart == employeeWeeklyTargetWeekStart &&
            !employeeWeeklyScheduleRequestLoading && employeeWeeklyScheduleRequestError == null

    val weeklyScheduleRequestsReady: Boolean
        get() = weeklyScheduleRequestsLoadedWeekStart == selectedWeekStart &&
            !weeklyScheduleRequestsLoading && weeklyScheduleRequestsError == null

    fun hasCompleteCalculationRange(start: LocalDate, end: LocalDate, employeeId: String? = null): Boolean =
        !attendanceHistoryLoading && attendanceHistoryError == null && !attendanceHistoryTruncated &&
            historicalCalculationData != null &&
            attendanceHistoryQueryKey == "$start|$end|${employeeId?.trim().orEmpty()}"

    val calculationSchedules: List<WorkSchedule>
        get() = (schedules + historicalCalculationData?.schedules.orEmpty())
            .distinctBy { "${it.employeeId}|${it.date}" }
    val calculationShifts: List<WorkShift>
        get() = (shifts + historicalCalculationData?.shifts.orEmpty()).distinctBy { it.id }
    val calculationLeaveRequests: List<LeaveRequest>
        get() = (leaveRequests + historicalCalculationData?.leaveRequests.orEmpty()).distinctBy { it.id }
    val calculationOvertimeRequests: List<OvertimeRequest>
        get() = (overtimeRequests + historicalCalculationData?.overtimeRequests.orEmpty()).distinctBy { it.id }
    val calculationAdjustments: List<AttendanceAdjustment>
        get() = (attendanceAdjustments + historicalCalculationData?.adjustments.orEmpty()).distinctBy { it.id }
    val calculationClassificationOverrides: List<AttendanceClassificationOverride>
        get() = (attendanceClassificationOverrides + historicalCalculationData?.classificationOverrides.orEmpty())
            .distinctBy { it.id }
    val calculationOffScheduleReviews: List<OffScheduleAttendanceReview>
        get() = (offScheduleAttendanceReviews + historicalCalculationData?.offScheduleReviews.orEmpty())
            .distinctBy { it.id }
    val calculationEmployeeSchedules: List<WorkSchedule>
        get() = (employeeSchedules + historicalCalculationData?.schedules.orEmpty()
            .filter { it.employeeId == currentEmployee?.id }).distinctBy { "${it.employeeId}|${it.date}" }
    val calculationEmployeeLeaveRequests: List<LeaveRequest>
        get() = (employeeRequests + historicalCalculationData?.leaveRequests.orEmpty()
            .filter { it.employeeId == currentEmployee?.id }).distinctBy { it.id }
    val calculationEmployeeOvertimeRequests: List<OvertimeRequest>
        get() = (employeeOvertimeRequests + historicalCalculationData?.overtimeRequests.orEmpty()
            .filter { it.employeeId == currentEmployee?.id }).distinctBy { it.id }

    val operationalEmployees: List<Employee>
        get() = employees.filter { it.visibleOutsideRetiredList() }

    fun historicalEmployees(month: YearMonth): List<Employee> =
        employees.filter { it.visibleOutsideRetiredList(month) }

    internal val attendanceSelectedRange: AttendanceDateRange?
        get() {
            val zone = ZoneId.of("Asia/Ho_Chi_Minh")
            val today = LocalDate.now(zone)
            return when (attendanceDatePreset) {
                "TODAY" -> AttendanceDateRange(today, today)
                "YESTERDAY" -> AttendanceDateRange(today.minusDays(1), today.minusDays(1))
                "THIS_WEEK" -> AttendanceDateRange(mondayOfWeek(today), mondayOfWeek(today).plusDays(6))
                "THIS_MONTH" -> AttendanceDateRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
                "SINGLE" -> parseAttendanceDateRange(attendanceDateFilter, attendanceDateFilter)
                "CUSTOM" -> parseAttendanceDateRange(attendanceRangeStart, attendanceRangeEnd)
                else -> if (attendanceDateFilter.isBlank()) null else parseAttendanceDateRange(attendanceDateFilter, attendanceDateFilter)
            }
        }

    val attendanceFilterEmployees: List<Employee>
        get() = attendanceSelectedRange?.let { historicalEmployees(YearMonth.from(it.start)) } ?: operationalEmployees

    val effectiveSchedules: List<WorkSchedule>
        get() = applyApprovedOffScheduleReviewsToSchedules(calculationSchedules, calculationOffScheduleReviews, calculationShifts)

    val effectiveEmployeeSchedules: List<WorkSchedule>
        get() = applyApprovedOffScheduleReviewsToSchedules(calculationEmployeeSchedules, calculationOffScheduleReviews, calculationShifts)

    private val hiddenRetiredEmployeeIds: Set<String>
        get() = employees.filterNot { it.visibleOutsideRetiredList() }.mapTo(mutableSetOf()) { it.id }

    val visibleAdminNotifications: List<AppNotification>
        get() = notifications.filter { it.recipientEmployeeId !in hiddenRetiredEmployeeIds }

    val visibleAuditLogs: List<AuditLog>
        get() {
            val hidden = employees.filterNot { it.visibleOutsideRetiredList() }
            return auditLogs.filter { log -> hidden.none { employee ->
                log.targetId == employee.id || log.details.contains("employeeId=${employee.id}") ||
                    (employee.fullName.isNotBlank() && Regex(
                        "(?<!\\p{L})${Regex.escape(employee.fullName)}(?!\\p{L})", RegexOption.IGNORE_CASE
                    ).containsMatchIn(log.details))
            } }
        }

    private val allAttendance: List<Attendance>
        get() = (attendance + historicalAttendance)
            .distinctBy { row ->
                row.id.ifBlank {
                    "${row.employeeId}|${row.timestamp.seconds}|${row.timestamp.nanoseconds}|${row.type}"
                }
            }

    /** Effective rows are derived locally in Spark mode; Firestore raw scans stay immutable. */
    val sparkResolvedAttendance: List<Attendance>
        get() = resolveSparkPendingAttendance(
            applyAttendanceClassificationOverrides(allAttendance, calculationClassificationOverrides),
            calculationSchedules, calculationShifts, ZoneId.of("Asia/Ho_Chi_Minh"), calculationOvertimeRequests, calculationOffScheduleReviews
        )
    private val allEmployeeAttendance: List<Attendance>
        get() = (employeeAttendance + employeeAttendanceHistory + historicalAttendance.filter { it.employeeId == currentEmployee?.id })
            .distinctBy { row ->
                row.id.ifBlank {
                    "${row.employeeId}|${row.timestamp.seconds}|${row.timestamp.nanoseconds}|${row.type}"
                }
            }
    val employeeSparkResolvedAttendance: List<Attendance>
        get() = resolveSparkPendingAttendance(
            applyAttendanceClassificationOverrides(allEmployeeAttendance, calculationClassificationOverrides),
            calculationEmployeeSchedules, calculationShifts, ZoneId.of("Asia/Ho_Chi_Minh"), calculationEmployeeOvertimeRequests,
            calculationOffScheduleReviews
        )
    val employeeAttendanceForSummaries: List<Attendance>
        get() = applyOffScheduleReviewDecisions(
            applyAttendanceClassificationOverrides(employeeSparkResolvedAttendance, calculationClassificationOverrides),
            calculationOffScheduleReviews, ZoneId.of("Asia/Ho_Chi_Minh"), calculationShifts
        )
            .filterNot { it.type == "DISCARDED" }
    val historicalAttendanceForSummaries: List<Attendance>
        get() = applyOffScheduleReviewDecisions(
            applyAttendanceClassificationOverrides(sparkResolvedAttendance, calculationClassificationOverrides),
            calculationOffScheduleReviews,
            ZoneId.of("Asia/Ho_Chi_Minh"), calculationShifts
        ).filter { it.type != "DISCARDED" }

    val attendanceForSummaries: List<Attendance>
        get() = historicalAttendanceForSummaries.filter { it.employeeId !in hiddenRetiredEmployeeIds }

    // Derived on every state snapshot, including schedule, shift and adjustment emissions.
    val dashboard: DashboardSummary
        get() = summarizeDashboard(operationalEmployees, attendanceForSummaries, selectedWeekStart,
            schedules = effectiveSchedules, shifts = calculationShifts, adjustments = calculationAdjustments,
            requests = calculationLeaveRequests)

    val dailyDashboard: DailyDashboardSummary
        get() = summarizeDailyDashboard(
            employees = operationalEmployees,
            attendance = attendanceForSummaries,
            schedules = effectiveSchedules,
            shifts = calculationShifts,
            requests = calculationLeaveRequests,
            adjustments = calculationAdjustments,
            date = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            overtimeRequests = calculationOvertimeRequests
        )

    val visibleEmployees: List<Employee>
        get() = filterEmployees(employees, employeeQuery, departmentFilter, showRetired)

    val visibleAttendance: List<Attendance>
        get() {
            val zone = ZoneId.of("Asia/Ho_Chi_Minh")
            val selectedRange = attendanceSelectedRange
            if ((attendanceDatePreset != null || attendanceDateFilter.isNotBlank()) && selectedRange == null) return emptyList()
            val byEmployee = attendanceFilterEmployees.associateBy(Employee::id)
            // Spark mode keeps Firestore scans immutable, so the list screen
            // must use the locally resolved copies to show CHECK_IN/CHECK_OUT
            // instead of exposing the raw SCAN/PENDING device event.
            val resolvedAttendance = historicalAttendanceForSummaries.filter { it.employeeId in byEmployee }
            val rangedAttendance = selectedRange?.let {
                filterAttendanceByDateRange(resolvedAttendance, it, effectiveSchedules, calculationShifts, zone)
            } ?: resolvedAttendance
            return filterAttendance(rangedAttendance, attendanceStatusFilter, attendanceTypeFilter,
                effectiveSchedules, calculationShifts, calculationAdjustments, leaveRequests = calculationLeaveRequests)
                .filter { row -> attendanceEmployeeFilter.isNullOrBlank() || row.employeeId == attendanceEmployeeFilter }
                .filter { row -> attendanceDepartmentFilter.isNullOrBlank() || byEmployee[row.employeeId]?.department == attendanceDepartmentFilter }
        }

    val visibleLeaveRequests: List<LeaveRequest>
        get() = leaveRequests.filter {
            it.employeeId !in hiddenRetiredEmployeeIds && (selectedRequestFilter.isNullOrBlank() || it.status == selectedRequestFilter)
        }

    val presenceRecords: List<PresenceRecord>
        get() = classifyPresenceForEmployees(
            employees = operationalEmployees,
            attendance = attendanceForSummaries,
            requests = calculationLeaveRequests,
            date = selectedPresenceDate,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            adjustments = calculationAdjustments,
            schedules = effectiveSchedules,
            shifts = calculationShifts
        )

    val weeklyWorkSummary: WeeklyWorkSummary
        get() = summarizeWeeklyWork(
            employees = operationalEmployees,
            attendance = attendanceForSummaries,
            schedules = effectiveSchedules,
            shifts = calculationShifts.associateBy { it.id },
            approvedRequests = calculationLeaveRequests,
            weekStart = selectedWeekStart,
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
            adjustments = calculationAdjustments,
            overtimeRequests = (calculationOvertimeRequests + calculationEmployeeOvertimeRequests).distinctBy { it.id }
        )
}
