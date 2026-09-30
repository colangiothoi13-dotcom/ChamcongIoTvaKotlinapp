# MVVM Admin MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng cấp app Android Kotlin hiện có thành MVP quản trị chấm công dùng MVVM rõ ràng với dashboard, nhân viên, chấm công và thiết bị, đồng thời giữ nguyên các luồng Firebase/AS608 đang hoạt động.

**Architecture:** Composable nhận state bất biến và phát callback; `MainViewModel` giữ `StateFlow<MainUiState>` và điều phối intent; `FirebaseRepository` là lớp duy nhất gọi Auth/Firestore. Quy tắc dashboard và bộ lọc nằm trong các hàm domain thuần để kiểm thử độc lập.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.7.3, Jetpack Compose Material 3, Firebase Auth/Firestore, WorkManager, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-13-mvvm-admin-mvp-design.md`

## Global Constraints

- Dùng Kotlin + Jetpack Compose + MVVM; UI không gọi Firebase trực tiếp.
- Không lưu ảnh/template vân tay; chỉ lưu `fingerprintTemplateId`, device id và trạng thái liên kết.
- Không gửi command LED/còi nếu firmware hiện tại chưa xử lý command đó.
- Nhân viên đã nghỉ dùng `active=false`; không xóa attendance hoặc payroll.
- Lệnh device `REQUESTED`, `PROCESSING` hoặc `COMPLETED` chưa `applied` không được ghi đè.
- Mọi file sửa/tạo phải được ghi vào mục “Thiết kế MVVM và nhật ký file” của `README.md` ngay trong task tương ứng.
- TDD bắt buộc: viết test, chạy thấy FAIL đúng nguyên nhân, viết implementation tối thiểu, chạy PASS, rồi mới refactor.
- Workspace không có `.git`, vì vậy không thực hiện commit; mọi thay đổi phải được kiểm chứng bằng diff/file list và build/test.

## File Map

- Create: `app/src/main/java/vn/chamcong/iot/model/DashboardModels.kt` — summary và dữ liệu biểu đồ.
- Create: `app/src/main/java/vn/chamcong/iot/model/DeviceModels.kt` — snapshot thiết bị và command presentation.
- Create: `app/src/main/java/vn/chamcong/iot/domain/DashboardRules.kt` — hàm tính summary thuần.
- Create: `app/src/main/java/vn/chamcong/iot/domain/FilterRules.kt` — bộ lọc employee/attendance thuần.
- Create: `app/src/test/java/vn/chamcong/iot/domain/DashboardRulesTest.kt` — test dashboard.
- Create: `app/src/test/java/vn/chamcong/iot/domain/FilterRulesTest.kt` — test bộ lọc.
- Create: `app/src/test/java/vn/chamcong/iot/model/DeviceModelsTest.kt` — test heartbeat và nhãn command.
- Modify: `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt` — expose `observeDevices()` và đọc snapshot thiết bị.
- Modify: `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` — state, subscriptions và intent MVVM.
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt` — login và navigation shell.
- Create: `app/src/main/java/vn/chamcong/iot/ui/dashboard/DashboardScreen.kt` — cards, chart và cảnh báo.
- Create: `app/src/main/java/vn/chamcong/iot/ui/employees/EmployeesScreen.kt` — tìm kiếm, lọc và thao tác nhân viên.
- Create: `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt` — feed và bộ lọc chấm công.
- Create: `app/src/main/java/vn/chamcong/iot/ui/devices/DevicesScreen.kt` — trạng thái thiết bị/lệnh.
- Modify: `README.md` — nhật ký tất cả file đã sửa/tạo và lệnh kiểm tra.

### Task 1: Tạo model/domain thuần cho dashboard và bộ lọc

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/model/DashboardModels.kt`
- Create: `app/src/main/java/vn/chamcong/iot/domain/DashboardRules.kt`
- Create: `app/src/main/java/vn/chamcong/iot/domain/FilterRules.kt`
- Create: `app/src/test/java/vn/chamcong/iot/domain/DashboardRulesTest.kt`
- Create: `app/src/test/java/vn/chamcong/iot/domain/FilterRulesTest.kt`
- Modify: `README.md`

**Interfaces:**
- `data class DashboardSummary(activeEmployees: Int, checkedEmployees: Int, lateEmployees: Int, unmarkedEmployees: Int, unresolvedPresenceEmployees: Int, lastSevenDays: List<DailyAttendance>)`
- `data class DailyAttendance(date: LocalDate, count: Int)`
- `fun summarizeDashboard(employees: List<Employee>, attendance: List<Attendance>, today: LocalDate, zoneId: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")): DashboardSummary`
- `fun filterEmployees(employees: List<Employee>, query: String, department: String?, includeRetired: Boolean): List<Employee>`
- `fun filterAttendance(attendance: List<Attendance>, status: String?, type: String?): List<Attendance>`

- [ ] **Step 1: Write failing dashboard tests**

```kotlin
private fun attendance(
    employeeId: String,
    type: String,
    status: String,
    time: LocalDateTime = LocalDateTime.of(2026, 9, 13, 8, 0)
) = Attendance(
    employeeId = employeeId,
    type = type,
    status = status,
    timestamp = Timestamp(Date.from(time.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()))
)

@Test fun summaryCountsActiveCheckedLateAndUnmarkedEmployees() {
    val today = LocalDate.of(2026, 9, 13)
    val employees = listOf(
        Employee(id = "a", fullName = "An", active = true),
        Employee(id = "b", fullName = "Bình", active = true),
        Employee(id = "c", fullName = "Chi", active = false)
    )
    val attendance = listOf(
        attendance("a", "CHECK_IN", "LATE", today.atTime(8, 20)),
        attendance("a", "CHECK_OUT", "NORMAL", today.atTime(17, 0))
    )

    val result = summarizeDashboard(employees, attendance, today)

    assertEquals(2, result.activeEmployees)
    assertEquals(1, result.checkedEmployees)
    assertEquals(1, result.lateEmployees)
    assertEquals(1, result.unmarkedEmployees)
    assertEquals(0, result.unresolvedPresenceEmployees)
}

@Test fun summaryMarksLatestCheckInWithoutCheckOutAsUnresolvedPresence() {
    val today = LocalDate.of(2026, 9, 13)
    val result = summarizeDashboard(
        listOf(Employee(id = "a", active = true)),
        listOf(attendance("a", "CHECK_IN", "NORMAL", today.atTime(8, 0))),
        today
    )

    assertEquals(1, result.unresolvedPresenceEmployees)
}
```

Place the helper and the imports (`java.time.LocalDate`, `java.time.LocalDateTime`, `java.time.ZoneId`, `java.util.Date`, `com.google.firebase.Timestamp`) in each test file that uses it.

- [ ] **Step 2: Run only the new dashboard test and verify it fails because the functions/models do not exist**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.DashboardRulesTest`

Expected: FAIL with unresolved references for `DashboardSummary`/`summarizeDashboard`.

- [ ] **Step 3: Write failing filter tests**

```kotlin
@Test fun employeeFilterMatchesNameCodeAndDepartmentWithoutCaseSensitivity() {
    val employees = listOf(
        Employee(id = "1", code = "NV0001", fullName = "Nguyễn An", department = "Kỹ thuật", active = true),
        Employee(id = "2", code = "NV0002", fullName = "Trần Bình", department = "Kinh doanh", active = true),
        Employee(id = "3", code = "NV0003", fullName = "Lê Chi", department = "Kỹ thuật", active = false)
    )

    val result = filterEmployees(employees, query = "nv0001", department = "Kỹ thuật", includeRetired = false)

    assertEquals(listOf("1"), result.map { it.id })
}

@Test fun attendanceFilterCanSelectLateCheckIns() {
    val rows = listOf(
        attendance("1", "CHECK_IN", "LATE"),
        attendance("2", "CHECK_OUT", "NORMAL")
    )

    assertEquals(listOf("1"), filterAttendance(rows, status = "LATE", type = "CHECK_IN").map { it.employeeId })
}
```

- [ ] **Step 4: Run filter tests and verify the expected unresolved-reference failure**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.FilterRulesTest`

Expected: FAIL before implementation.

- [ ] **Step 5: Implement the minimal models and pure rules**

Use `Attendance.timestamp.toDate().toInstant().atZone(zoneId).toLocalDate()` for local-day grouping. Count a checked employee once per day. For presence, group today’s rows by employee and inspect the row with the latest timestamp; it is unresolved only when that latest row is `CHECK_IN`. `filterEmployees` must include only active employees unless `includeRetired=true`, then match `query` against code/name and `department` exactly when non-null/non-blank. `filterAttendance` must treat null/blank status/type as no filter.

- [ ] **Step 6: Run both test classes and confirm PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.DashboardRulesTest --tests vn.chamcong.iot.domain.FilterRulesTest`

Expected: all tests in both new classes PASS.

- [ ] **Step 7: Add the five model/domain/test files to the README file log**

Record the exact paths and one-line responsibility in `README.md` before moving to Task 2.

### Task 2: Add device snapshot reading without inventing unsupported commands

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/model/DeviceModels.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`
- Create: `app/src/test/java/vn/chamcong/iot/model/DeviceModelsTest.kt`
- Modify: `README.md`

**Interfaces:**
- `data class DeviceSnapshot(id: String = "", name: String = "", location: String = "", status: String = "UNKNOWN", lastHeartbeat: Timestamp? = null, firmwareVersion: String = "", fingerprintCount: Int? = null, capacity: Int? = null, capabilities: Set<String> = emptySet())`
- `fun DeviceSnapshot.isOnline(now: Instant, timeout: Duration = Duration.ofMinutes(2)): Boolean`
- `fun FirebaseRepository.observeDevices(): Flow<List<DeviceSnapshot>>`

- [ ] **Step 1: Write the failing device model test**

```kotlin
@Test fun deviceIsOfflineWhenHeartbeatIsOlderThanTwoMinutes() {
    val now = Instant.parse("2026-09-13T10:00:00Z")
    val device = DeviceSnapshot(lastHeartbeat = Timestamp(Date.from(now.minusSeconds(121))))

    assertFalse(device.isOnline(now))
}

@Test fun deviceWithNoHeartbeatIsUnknown() {
    assertFalse(DeviceSnapshot(status = "UNKNOWN").isOnline(Instant.parse("2026-09-13T10:00:00Z")))
}
```

- [ ] **Step 2: Run the test and verify it fails because `DeviceSnapshot` is missing**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.model.DeviceModelsTest`

Expected: FAIL with unresolved references.

- [ ] **Step 3: Implement `DeviceSnapshot` and its heartbeat rule**

Parse missing or malformed heartbeat fields as null; a device is online only if the heartbeat exists, the snapshot status is not `OFFLINE`, and its age is within two minutes. Do not infer an online state from a missing document.

- [ ] **Step 4: Implement `observeDevices()` in the repository**

Listen to `devices`, map each document explicitly from string/long/timestamp fields, preserve the document id, and close the listener in `awaitClose`. If the listener reports an error, close the flow with that error so the ViewModel can expose it.

- [ ] **Step 5: Run the device test and the existing unit tests**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: PASS with zero failures.

- [ ] **Step 6: Update README with the new device model/repository/test files**

Explain that the device screen only shows commands supported by the current firmware and will not send unrecognized test commands.

### Task 3: Refactor `MainViewModel` state and intents for MVVM

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`
- Modify: `README.md`

**Interfaces:**
- Extend `MainUiState` with `dashboard: DashboardSummary`, `devices: List<DeviceSnapshot>`, `employeeQuery: String`, `departmentFilter: String?`, `showRetired: Boolean`, `attendanceStatusFilter: String?`, and `attendanceTypeFilter: String?`.
- Add `fun setEmployeeQuery(value: String)`, `fun setDepartmentFilter(value: String?)`, `fun setShowRetired(value: Boolean)`, `fun setAttendanceFilters(status: String?, type: String?)`.
- Add `val visibleEmployees: List<Employee>` and `val visibleAttendance: List<Attendance>` as state-derived properties in the ViewModel, using the pure filter functions.

- [ ] **Step 1: Add a small ViewModel-facing state test seam before changing behavior**

Extract a private `withUpdatedDashboard` helper that accepts the current `MainUiState` and replacement employee/attendance lists. The helper must call `summarizeDashboard` and return a new state; keep it pure so it can be exercised through the existing domain tests without Firebase.

- [ ] **Step 2: Run the current unit test suite to establish the baseline**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: existing tests PASS before the ViewModel refactor.

- [ ] **Step 3: Add device subscription and update state in the ViewModel**

Subscribe to `repository.observeDevices()` beside employees, attendance, payroll and commands. On employee/attendance changes, update the raw list and `dashboard` together through `withUpdatedDashboard`. On errors, preserve the previous list and set `error`.

- [ ] **Step 4: Add filter intents and expose derived lists**

Each setter updates only the corresponding state field. The derived employee list calls `filterEmployees`; the derived attendance list calls `filterAttendance`. The Composable must never duplicate filtering rules.

- [ ] **Step 5: Keep existing enrollment, deletion, payroll, sign-in and receipt behavior unchanged**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: PASS; no existing personnel/payroll behavior regresses.

- [ ] **Step 6: Update README with the ViewModel state/intent changes**

Record that Firebase operations remain in `FirebaseRepository` and that screens consume `visibleEmployees`, `visibleAttendance`, `dashboard` and `devices` from the ViewModel.

### Task 4: Build the dashboard screen and navigation shell

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/dashboard/DashboardScreen.kt`
- Modify: `README.md`

**Interfaces:**
- `enum class AppDestination { DASHBOARD, EMPLOYEES, ATTENDANCE, DEVICES, PAYROLL, PERFORMANCE }`
- `@Composable fun DashboardScreen(state: MainUiState, onOpenAttendance: () -> Unit)`
- Existing dialog callbacks remain in the shell and call ViewModel intents.

- [ ] **Step 1: Add the screen-level UI testable data contract**

Keep `DashboardScreen` stateless: it receives `MainUiState` and a callback only. Dashboard cards must read `state.dashboard`; they may not count employees/attendance inside the Composable.

- [ ] **Step 2: Run the unit suite before UI edits**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 3: Implement dashboard cards and chart**

Render cards for active employees, checked today, late, unmarked and unresolved presence. Render seven `DailyAttendance` bars using `lastSevenDays`, a device status summary from `state.devices`, and recent alerts from commands/errors. Use green/orange/red/gray semantics from the spec.

- [ ] **Step 4: Replace the current tab labels with the destination shell**

Keep `NavigationBar` on phone-sized layouts. `HomeScreen` selects the destination and delegates content to feature Composables. Keep the top app bar title, logout action, saving progress, global error and success message in the shell.

- [ ] **Step 5: Build the debug APK**

Run: `gradlew.bat :app:assembleDebug`

Expected: BUILD SUCCESSFUL and an APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Update README with the navigation and dashboard files**

Document the destination list and confirm that dashboard metrics are calculated by domain/ViewModel, not by UI code.

### Task 5: Build employee and attendance feature screens

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/ui/employees/EmployeesScreen.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`
- Modify: `README.md`

**Interfaces:**
- `@Composable fun EmployeesScreen(state: MainUiState, vm: MainViewModel, onAdd: () -> Unit)`
- `@Composable fun AttendanceScreen(state: MainUiState, vm: MainViewModel)`

- [ ] **Step 1: Run the existing unit suite before screen extraction**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Extract the employee list and dialogs without changing callbacks**

Move employee list rendering, fingerprint enrollment dialog, employee removal dialog and salary entry trigger into `EmployeesScreen`. Use `state.visibleEmployees` and expose search/filter controls that call `vm.setEmployeeQuery`, `vm.setDepartmentFilter` and `vm.setShowRetired`.

- [ ] **Step 3: Add employee presentation details**

Each row shows avatar initials, full name, code, position/department, active/retired status, salary, fingerprint status and pending command status. Preserve the current safe delete semantics and existing fingerprint callbacks.

- [ ] **Step 4: Extract attendance feed and add filters**

Move `AttendanceList`/`AttendanceRow` into `AttendanceScreen`. Add filter chips for all/normal/late/early leave and check-in/check-out; call `vm.setAttendanceFilters`. Render empty state and timestamp/device details.

- [ ] **Step 5: Build and run all unit tests**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: all unit tests PASS and debug APK builds successfully.

- [ ] **Step 6: Update README with employee/attendance screen files and behavior**

Record that search/filter state is owned by the ViewModel and that fingerprint operations still use the existing transactional repository flow.

### Task 6: Add the device screen, final verification and documentation audit

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/ui/devices/DevicesScreen.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`
- Modify: `README.md`

**Interfaces:**
- `@Composable fun DevicesScreen(state: MainUiState)`
- `fun commandStatusLabel(command: Map<String, Any>): String` in `DeviceModels.kt`, covered by `DeviceModelsTest.kt`.

- [ ] **Step 1: Write failing command label tests**

```kotlin
@Test fun processingEnrollmentCommandHasActionableLabel() {
    assertEquals(
        "Thiết bị đang chờ thao tác",
        commandStatusLabel(mapOf("type" to "ENROLL_FINGERPRINT", "status" to "PROCESSING"))
    )
}

@Test fun failedDeleteCommandExplainsRetryState() {
    assertEquals(
        "Xóa vân tay thất bại; vị trí được giữ lại",
        commandStatusLabel(mapOf("type" to "DELETE_FINGERPRINT", "status" to "FAILED"))
    )
}
```

- [ ] **Step 2: Run the test and verify it fails before implementing the label helper**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.model.DeviceModelsTest`

Expected: FAIL because `commandStatusLabel` is not defined.

- [ ] **Step 3: Implement labels and the device screen**

Show device name/id/location, online/offline/unknown badge, last heartbeat, firmware, fingerprint usage and capacity. Show enrollment/deletion command rows with actionable text. Show LED/còi test controls disabled with “Firmware hiện tại chưa công bố capability” unless the snapshot contains `TEST_LED_GREEN`, `TEST_LED_RED` or `TEST_BUZZER` in `capabilities`; do not write a command from these disabled controls.

- [ ] **Step 4: Wire the destination into the shell**

Add `DEVICES` to navigation and delegate to `DevicesScreen`. Keep the existing payroll and performance screens intact.

- [ ] **Step 5: Run full verification**

Run:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

Expected: both commands exit with code 0; unit tests report zero failures; debug APK exists at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Perform a documentation audit**

Run: `rg -n "Create:|Modify:" docs/superpowers/plans/2026-09-13-mvvm-admin-mvp.md` and compare every listed path with `rg --files app README.md docs/superpowers`. Add any missed file to the README file log. Document the exact verification commands and any environment limitation, such as missing Firebase credentials or Android SDK.
