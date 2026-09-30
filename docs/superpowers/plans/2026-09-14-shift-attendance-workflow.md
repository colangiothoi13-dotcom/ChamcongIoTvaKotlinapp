# Quản lý ca, lịch tuần, đơn từ và cảnh báo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bổ sung cho app admin khả năng quản lý ba loại ca, phân ca theo tuần, chọn tăng ca 0–3 giờ, theo dõi có mặt, duyệt đơn từ, tổng hợp giờ làm/tăng ca và xem cảnh báo.

**Architecture:** Giữ MVVM hiện tại. Model và quy tắc tuần/ca/tính giờ/trạng thái là hàm domain thuần; `MainViewModel` giữ tuần đang chọn và gọi repository; Compose chỉ đọc `MainUiState` và phát callback. Firestore bổ sung `shifts`, `workSchedules`, `leaveRequests`, `notifications`; attendance/payroll cũ không bị xóa hoặc ghi đè.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.7.3, Jetpack Compose Material 3, Firebase Auth/Firestore, JUnit 4, Java Time, WorkManager hiện có.

**Spec:** `docs/superpowers/specs/2026-09-14-shift-attendance-workflow-design.md`

## Global Constraints

- Chỉ admin được tạo/sửa ca và phân ca; nhân viên không tự phân ca trong app admin.
- Phân loại ca trong MVP chỉ gồm `Ca sáng`, `Ca tối` và `Ca bổ sung`.
- Tăng ca trên từng lịch phân ca chỉ nhận `0`, `1`, `2` hoặc `3` giờ; mặc định là `0`.
- Tuần bắt đầu từ thứ Hai và biểu đồ chỉ tổng hợp đúng tuần đang chọn, không dùng cửa sổ 7 ngày trượt.
- Dùng Kotlin + Jetpack Compose + MVVM; UI không gọi Firebase trực tiếp.
- Không lưu ảnh hoặc template vân tay; không gửi command LED/còi mà firmware hiện tại chưa xử lý.
- Nhân viên đã nghỉ dùng `active=false`; không xóa attendance hoặc payroll.
- Attendance và payroll cũ không bị ghi đè; điều chỉnh giờ dùng override/ghi chú riêng.
- Firestore Rules giữ anonymous device chỉ được tạo attendance; ca, lịch, đơn từ và notification chỉ dành cho admin.
- TDD bắt buộc: test fail trước implementation, sau đó test pass và chạy lại toàn bộ suite.
- Project hiện không có `.git`, nên không commit; dùng file list, unit test và build làm bằng chứng.
- Mọi file tạo/sửa phải được ghi vào nhật ký file trong `README.md`.

## File Map

- Create: `app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt`, `RequestModels.kt`, `PresenceModels.kt`.
- Create: `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt`, `PresenceRules.kt`, `RequestRules.kt`.
- Create: `app/src/test/java/vn/chamcong/iot/domain/SchedulingRulesTest.kt`, `PresenceRulesTest.kt`, `RequestRulesTest.kt`.
- Modify: `DashboardModels.kt`, `DashboardRules.kt`, `DashboardRulesTest.kt`, `FirebaseRepository.kt`, `MainViewModel.kt`, `ChamCongApp.kt`, `DashboardScreen.kt`.
- Create: `ui/shifts/ShiftsScreen.kt`, `ui/schedule/ScheduleScreen.kt`, `ui/presence/PresenceScreen.kt`, `ui/requests/RequestsScreen.kt`, `ui/reports/WorkSummaryScreen.kt`.
- Modify: `firebase/firestore.rules`, `firebase/firestore.indexes.json`, `README.md`.

---

### Task 1: Model/domain cho ca, lịch, giờ làm và tuần

**Files:** `SchedulingModels.kt`, `SchedulingRules.kt`, `SchedulingRulesTest.kt`, `DashboardModels.kt`, `DashboardRules.kt`, `DashboardRulesTest.kt`, `README.md`.

**Interfaces:**

```kotlin
enum class ShiftCategory { MORNING, EVENING, SUPPLEMENTARY }
data class WorkShift(val id: String = "", val name: String = "", val category: String = "MORNING", val startTime: String = "08:00", val endTime: String = "17:00", val allowEarlyMinutes: Int = 0, val lateGraceMinutes: Int = 0, val earlyLeaveAllowedMinutes: Int = 0, val breakStartTime: String? = null, val breakEndTime: String? = null, val countsOvertime: Boolean = false, val effectiveFrom: String = "", val effectiveTo: String? = null, val active: Boolean = true)
data class WorkSchedule(val id: String = "", val employeeId: String = "", val employeeName: String = "", val department: String = "", val shiftId: String = "", val shiftName: String = "", val date: String = "", val overtimeHours: Int = 0, val workedHoursOverride: Double? = null, val adjustmentNote: String = "", val assignedBy: String? = null, val source: String = "EMPLOYEE", val note: String = "")
data class WorkTimeSummary(val workedHours: Double, val overtimeHours: Double, val lateMinutes: Int, val earlyLeaveMinutes: Int, val dayWorked: Boolean)
fun mondayOfWeek(date: LocalDate): LocalDate
fun weekDates(weekStart: LocalDate): List<LocalDate>
fun validateShift(shift: WorkShift)
fun validateOvertimeHours(hours: Int)
fun calculateWorkTime(checkIn: Instant?, checkOut: Instant?, shift: WorkShift?, overtimeHours: Int, zoneId: ZoneId): WorkTimeSummary
fun copyScheduleToNextWeek(source: List<WorkSchedule>, existingTargetIds: Set<String>): List<WorkSchedule>
```

- [ ] **Step 1: RED tests.** Test only `MORNING`, `EVENING`, `SUPPLEMENTARY`; `validateOvertimeHours(4)` fails; any date normalizes to Monday; `weekDates` returns exactly Monday–Sunday.
- [ ] **Step 2: Verify RED.** Run `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.SchedulingRulesTest`; expect unresolved references because models/rules do not exist.
- [ ] **Step 3: GREEN implementation.** Add models and validation for non-empty name/effective date, valid `HH:mm`, non-negative minute settings, the three categories and overtime `0..3`. End time earlier than start means next day.
- [ ] **Step 4: RED work-time tests.** For 08:00–17:00 with lunch 12:00–13:00, check-in 08:00, check-out 18:00, overtime enabled and selected `1`, assert 9 worked hours and 1 overtime hour. Test copy preserves weekday and skips existing target ids.
- [ ] **Step 5: GREEN calculation.** Subtract only lunch overlap, calculate late/early minutes from grace settings, cap overtime by selected 0–3 hours, and never mutate attendance.
- [ ] **Step 6: Replace rolling chart.** Rename `lastSevenDays` to `weeklyAttendance`, add `weekStart`, and make `summarizeDashboard` count only the selected Monday–Sunday. Add a test event from the previous week that is excluded.
- [ ] **Step 7: Verify/document.** Run `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.SchedulingRulesTest --tests vn.chamcong.iot.domain.DashboardRulesTest`; record changed paths and the weekly/0–3 rules in README.

---

### Task 2: Presence, request and notification domain rules

**Files:** `PresenceModels.kt`, `RequestModels.kt`, `PresenceRules.kt`, `RequestRules.kt`, `PresenceRulesTest.kt`, `RequestRulesTest.kt`, `README.md`.

**Interfaces:**

```kotlin
enum class PresenceStatus { PRESENT, NOT_CHECKED_IN, ON_LEAVE, LEFT, MISSING_CHECK_OUT, ABNORMAL }
data class PresenceRecord(val employee: Employee, val status: PresenceStatus, val latestAttendance: Attendance? = null)
data class AppNotification(val id: String = "", val type: String = "", val title: String = "", val body: String = "", val referenceId: String? = null, val createdAt: Timestamp = Timestamp.now(), val read: Boolean = false)
enum class RequestType { LEAVE, LATE, EARLY_LEAVE, REMOTE, ATTENDANCE_ADJUSTMENT, SHIFT_CHANGE }
enum class RequestStatus { PENDING, APPROVED, REJECTED }
data class LeaveRequest(val id: String = "", val employeeId: String = "", val employeeName: String = "", val department: String = "", val type: String = "LEAVE", val startDate: String = "", val endDate: String = "", val reason: String = "", val attachmentUrl: String? = null, val status: String = "PENDING", val reviewerId: String? = null, val reviewerName: String? = null, val reviewedAt: Timestamp? = null, val reviewNote: String? = null, val createdAt: Timestamp = Timestamp.now())
fun classifyPresence(employee: Employee, rows: List<Attendance>, approvedRequests: List<LeaveRequest>, date: LocalDate, zoneId: ZoneId): PresenceRecord
fun classifyPresenceForEmployees(employees: List<Employee>, attendance: List<Attendance>, requests: List<LeaveRequest>, date: LocalDate, zoneId: ZoneId): List<PresenceRecord>
fun validateRequest(request: LeaveRequest)
fun reviewRequest(request: LeaveRequest, status: RequestStatus, reviewerId: String, reviewerName: String, note: String, reviewedAt: Timestamp): LeaveRequest
fun notificationForRequest(request: LeaveRequest): AppNotification
```

- [ ] **Step 1: RED presence tests.** Cover no event, approved leave precedence, latest checkout, latest verified check-in without checkout, paired check-in/out, unverified/conflicting data, and exclusion of retired employees.
- [ ] **Step 2: Verify RED.** Run `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.PresenceRulesTest`; expect unresolved model/rule references.
- [ ] **Step 3: GREEN presence rules.** Filter by local date, sort by timestamp, let approved leave win, distinguish `LEFT`/`MISSING_CHECK_OUT`, and preserve the latest attendance.
- [ ] **Step 4: RED request tests.** Test valid approval, reviewer/note persistence on rejection, blank rejection note failure, invalid date range failure, and `REQUEST` notification creation.
- [ ] **Step 5: GREEN request rules.** Validate supported types/statuses and `yyyy-MM-dd`, permit review only from `PENDING`, and require a non-blank rejection note.
- [ ] **Step 6: Verify/document.** Run both new test classes and add status precedence, transitions and file paths to README.

---

### Task 3: Firestore repository and security rules

**Files:** `FirebaseRepository.kt`, `firebase/firestore.rules`, `firebase/firestore.indexes.json`, `README.md`.

**Interfaces:**

```kotlin
fun observeShifts(): Flow<List<WorkShift>>
fun observeSchedules(startDate: String, endDate: String): Flow<List<WorkSchedule>>
fun observeLeaveRequests(): Flow<List<LeaveRequest>>
fun observeNotifications(): Flow<List<AppNotification>>
suspend fun saveShift(shift: WorkShift): String
suspend fun saveSchedule(schedule: WorkSchedule)
suspend fun assignShiftToDepartment(department: String, dates: List<String>, shift: WorkShift, overtimeHours: Int, assignedBy: String)
suspend fun copyPreviousWeek(sourceWeekStart: String, targetWeekStart: String, assignedBy: String): Int
suspend fun submitLeaveRequest(request: LeaveRequest): String
suspend fun reviewLeaveRequest(requestId: String, status: RequestStatus, reviewerId: String, reviewerName: String, note: String)
suspend fun markNotificationRead(notificationId: String)
```

- [ ] **Step 1: Add explicit mappers and Flow observers.** Copy document ids into models; bound schedules by `date >= startDate` and `date <= endDate`; order requests/notifications by `createdAt`; preserve existing flows.
- [ ] **Step 2: Add transactional writes.** Validate and save shifts; save schedules at `${employeeId}_${date}`; batch department assignment for active employees; reject overtime outside `0..3`.
- [ ] **Step 3: Add safe weekly copy.** Read seven source dates, add one week, create only missing target ids, preserve shift/overtime/note, and mark source `COPY_WEEK`.
- [ ] **Step 4: Add requests/notifications.** Submit as `PENDING` with server timestamp and notification; review in a transaction only once and store reviewer/server review time; mark notification read as admin.
- [ ] **Step 5: Update Rules/indexes.** Admin-only access to all four new collections with type/category/status/overtime/date validation; anonymous devices cannot access them; add only required date/createdAt indexes.
- [ ] **Step 6: Verify/document.** Run `gradlew.bat :app:testDebugUnitTest`, then document collection paths, deterministic ids, rules and files. Do not claim Firebase deployment unless executed.

---

### Task 4: ViewModel state, weekly selection and admin intents

**Files:** `MainViewModel.kt`, `ChamCongApp.kt`, `README.md`.

**Interfaces:**

```kotlin
data class MainUiState(val signedIn: Boolean = false, val loading: Boolean = false, val employees: List<Employee> = emptyList(), val attendance: List<Attendance> = emptyList(), val payroll: List<Payroll> = emptyList(), val commands: List<Map<String, Any>> = emptyList(), val devices: List<DeviceSnapshot> = emptyList(), val dashboard: DashboardSummary = DashboardSummary(), val employeeQuery: String = "", val departmentFilter: String? = null, val showRetired: Boolean = false, val attendanceStatusFilter: String? = null, val attendanceTypeFilter: String? = null, val selectedWeekStart: LocalDate = mondayOfWeek(LocalDate.now()), val selectedPresenceDate: LocalDate = LocalDate.now(), val shifts: List<WorkShift> = emptyList(), val schedules: List<WorkSchedule> = emptyList(), val leaveRequests: List<LeaveRequest> = emptyList(), val notifications: List<AppNotification> = emptyList(), val selectedRequestFilter: String? = null, val saving: Boolean = false, val message: String? = null, val error: String? = null)
fun selectWeek(weekStart: LocalDate)
fun moveWeek(delta: Long)
fun selectPresenceDate(date: LocalDate)
fun saveShift(shift: WorkShift, done: () -> Unit)
fun assignShift(schedule: WorkSchedule, done: () -> Unit)
fun assignShiftToDepartment(department: String, dates: List<String>, shift: WorkShift, overtimeHours: Int, done: () -> Unit)
fun copyPreviousWeek(done: () -> Unit)
fun reviewRequest(requestId: String, status: RequestStatus, note: String, done: () -> Unit)
fun markNotificationRead(notificationId: String)
```

- [ ] **Step 1:** Default `selectedWeekStart` to `mondayOfWeek(LocalDate.now(zoneId))` and presence date to today; preserve current auth/loading/error/saving/message behavior.
- [ ] **Step 2:** Subscribe to shifts, requests and notifications; recreate the bounded schedule observer on week change and cancel the old job.
- [ ] **Step 3:** Implement all writes through the existing `perform` helper; pass signed-in admin identity for review; invoke callbacks only after success.
- [ ] **Step 4:** Derive presence and weekly summary from pure rules; derive pending/warning counts without repeatedly persisting duplicate alerts.
- [ ] **Step 5:** Run `gradlew.bat :app:testDebugUnitTest` and document ViewModel ownership of week/date selection.

---

### Task 5: Ca làm and lịch tuần/tháng UI

**Files:** `ui/shifts/ShiftsScreen.kt`, `ui/schedule/ScheduleScreen.kt`, `ChamCongApp.kt`, `README.md`.

**Interfaces:**

```kotlin
@Composable fun ShiftsScreen(state: MainUiState, vm: MainViewModel)
@Composable fun ScheduleScreen(state: MainUiState, vm: MainViewModel)
```

- [ ] **Step 1:** Render only `Ca sáng`, `Ca tối`, `Ca bổ sung`; form edits name, times, lunch, early/late/early-leave rules, overtime eligibility and effective dates.
- [ ] **Step 2:** Render Monday–Sunday columns, active employee rows, assigned shift and `+N giờ`; add previous/next/“Tuần này”.
- [ ] **Step 3:** Assignment dialog selects employee or department, a shift from `state.shifts`, and overtime 0/1/2/3; call the corresponding ViewModel intent.
- [ ] **Step 4:** Add “Sao chép tuần trước” with created-row count and a month grid with schedule counts; do not use rolling-seven-day wording.
- [ ] **Step 5:** Wire `SHIFTS` and `SCHEDULE`, then run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.

---

### Task 6: Có mặt, đơn từ, tổng hợp và dashboard

**Files:** `ui/presence/PresenceScreen.kt`, `ui/requests/RequestsScreen.kt`, `ui/reports/WorkSummaryScreen.kt`, `DashboardScreen.kt`, `ChamCongApp.kt`, `README.md`.

**Interfaces:**

```kotlin
@Composable fun PresenceScreen(state: MainUiState, vm: MainViewModel)
@Composable fun RequestsScreen(state: MainUiState, vm: MainViewModel)
@Composable fun WorkSummaryScreen(state: MainUiState, vm: MainViewModel)
```

- [ ] **Step 1:** Show six presence groups for the selected date: present, not checked in, on leave, left, missing checkout, abnormal.
- [ ] **Step 2:** Show request details, attachment URL, status, reviewer/time; pending rows approve or reject, and rejection requires a reason.
- [ ] **Step 3:** Show selected-week totals for worked hours, workdays, late, early leave, overtime, leave and unauthorized absence; chart exactly seven Monday–Sunday bars.
- [ ] **Step 4:** Add dashboard week controls and warning cards for pending requests, missing checkout, abnormal attendance, failed commands, offline/unknown devices and late employees.
- [ ] **Step 5:** Wire `PRESENCE`, `REQUESTS`, `REPORTS` and run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`; expect exit code 0 and APK at `app/build/outputs/apk/debug/app-debug.apk`.

---

### Task 7: Final acceptance, security and documentation audit

**Files:** `firebase/firestore.rules`, `firebase/firestore.indexes.json`, `README.md`, and every path in the File Map.

- [ ] **Step 1:** Re-read the spec and check three categories, admin employee/department assignment, weekly calendar/chart, copy/manual change/month view, six presence states, request review, overtime 0–3, weekly summary, warnings, and preservation of old data.
- [ ] **Step 2:** Confirm anonymous cannot write new collections, admin validation matches model enums, deterministic schedule ids match repository, and attendance/payroll remain immutable.
- [ ] **Step 3:** Run fresh `gradlew.bat :app:testDebugUnitTest` and `gradlew.bat :app:assembleDebug`; record exit codes, failure count and APK path.
- [ ] **Step 4:** Compare changed files with `git diff --name-only` when available, otherwise compare the File Map with `rg --files app firebase docs README.md`; add missing entries to README.
- [ ] **Step 5:** Report only evidence-backed results and explicitly list Firebase/hardware limitations; do not claim Push/email or firmware changes without verification.
