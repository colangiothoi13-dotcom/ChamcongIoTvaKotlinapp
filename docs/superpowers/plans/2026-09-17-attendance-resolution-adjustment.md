# Resolve chấm công theo lịch ca và điều chỉnh công Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thay thế quyết định vào/ra theo mốc 12 giờ bằng resolve theo lịch ca/lượt gần nhất, hỗ trợ ca qua ngày, chống quét trùng, phát hiện thiếu chấm ra và cho phép Admin điều chỉnh công append-only có lý do/audit.

**Architecture:** Firmware chỉ ghi attendance raw với `type=SCAN`; Cloud Function trigger dùng schedule window và session transaction để resolve thành `CHECK_IN`, `CHECK_OUT`, `DUPLICATE`, `UNSCHEDULED` hoặc `OUT_OF_ORDER`. Android dùng cùng mô hình schedule date để tính công, áp dụng adjustment mới nhất mà không sửa attendance gốc; repository ghi adjustment và audit trong cùng batch.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.7.3, Jetpack Compose Material 3, Firebase Auth/Firestore, Firebase Cloud Functions v2, Node 22 built-in test runner, ESP8266 Arduino C++ và LittleFS outbox.

**Spec:** `docs/superpowers/specs/2026-09-17-attendance-resolution-adjustment-design.md`

## Global Constraints

- Cloud Function là nguồn quyết định trung tâm; firmware không tự quyết định vào/ra theo mốc 12 giờ.
- Attendance raw được giữ nguyên; không sửa/xóa event raw từ client.
- Ca qua ngày dùng `scheduleDate` là ngày bắt đầu ca.
- Cửa sổ resolve là `[start - allowEarlyMinutes, end + missingCheckOutGraceMinutes]`.
- Ngưỡng quét trùng là 3 phút; khoảng đệm thiếu chấm ra mặc định 60 phút và cấu hình trên `WorkShift`.
- `DUPLICATE`, `UNSCHEDULED` và `OUT_OF_ORDER` không được tính công.
- Adjustment là append-only, bắt buộc `reason`, và luôn tạo `ATTENDANCE_ADJUST` audit log.
- Kotlin domain không gọi Firebase; repository là nơi duy nhất đọc/ghi Firestore.
- Giữ tương thích với attendance cũ có `CHECK_IN`/`CHECK_OUT` nhưng không migrate/xóa dữ liệu cũ.
- Dùng timezone nghiệp vụ `Asia/Ho_Chi_Minh` ở Cloud Function và Android.
- Mỗi task phải có test đỏ trước implementation, test xanh sau implementation và một commit riêng.

---

## File Map và ranh giới trách nhiệm

- `app/src/main/java/vn/chamcong/iot/model/Models.kt`: mở rộng `Attendance` và trạng thái resolution.
- `app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt`: thêm cấu hình grace cho ca.
- `app/src/main/java/vn/chamcong/iot/model/AttendanceResolutionModels.kt`: model session, adjustment và cặp công đã resolve.
- `app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt`: quy tắc pure cho window ca, duplicate, pair và adjustment.
- `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt`: dùng lại resolver chung trong tổng hợp tuần và giữ validation ca.
- `app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt`, `EmployeeRules.kt`, `ReportRules.kt`, `model/PersonnelRules.kt`: dùng cùng cặp resolve khi hiện diện, báo cáo, bảng lương và màn hình nhân viên.
- `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`: listener adjustment, ghi adjustment + audit và mapping Firestore.
- `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`: state adjustment và action điều chỉnh.
- `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt`, `AttendanceAdjustmentDialog.kt`: hiển thị resolution và form Admin.
- `firebase/functions/attendanceResolver.js`: helper pure cho Cloud Function.
- `firebase/functions/index.js`: raw writer và Firestore trigger transaction.
- `firebase/functions/test/attendanceResolver.test.js`, `firebase/functions/package.json`: test Node.
- `firebase/firestore.rules`, `firebase/firestore.indexes.json`: quyền raw/session/adjustment và index.
- `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`: payload raw, bỏ logic mốc 12 giờ.
- `README.md`: cập nhật contract và file log theo quy ước repo.

## Task 1: Model, validation và contract Android

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/model/AttendanceResolutionModels.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/model/Models.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt`
- Create: `app/src/test/java/vn/chamcong/iot/domain/AttendanceResolutionRulesTest.kt`
- Create: `app/src/test/java/vn/chamcong/iot/domain/AttendanceAdjustmentRulesTest.kt`

**Interfaces:**
- Produces `AttendanceResolutionStatus { PENDING, ACCEPTED, DUPLICATE, UNSCHEDULED, OUT_OF_ORDER }`.
- Produces `AttendanceAdjustment(id, employeeId, employeeName, scheduleDate, checkInAt, checkOutAt, workedHoursOverride, reason, actorId, actorName, createdAt)`.
- Produces `AttendancePair(scheduleDate, checkIn, checkOut, adjustment)`.
- Extends `Attendance` with `receivedAt`, `resolutionStatus`, `scheduleDate`, `shiftId`, and `resolvedAt`, using `ACCEPTED` as the default for legacy documents.
- Extends `WorkShift` with `missingCheckOutGraceMinutes: Int = 60`.

- [ ] **Step 1: Write failing validation tests.** Add tests asserting that an adjustment with a blank reason, invalid date, check-out before check-in, or `workedHoursOverride` outside `0.0..24.0` throws `IllegalArgumentException`; assert a valid adjustment passes; assert a shift rejects negative grace.

- [ ] **Step 2: Run the focused tests and verify RED.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.AttendanceAdjustmentRulesTest --tests vn.chamcong.iot.domain.AttendanceResolutionRulesTest`

Expected: compile failure because the new model and validation functions do not exist.

- [ ] **Step 3: Implement the data classes and pure validators.** Add `validateAttendanceAdjustment(adjustment: AttendanceAdjustment)` with these exact checks: nonblank employee id/date/reason/actor id/actor name, ISO date, at least one edited value, paired timestamps when both are present, check-out after check-in, and finite override in `0.0..24.0`. Add `missingCheckOutGraceMinutes` to `WorkShift` and validate it as non-negative in `validateShift`.

- [ ] **Step 4: Run the focused tests and verify GREEN.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.AttendanceAdjustmentRulesTest --tests vn.chamcong.iot.domain.AttendanceResolutionRulesTest`

Expected: all focused tests pass.

- [ ] **Step 5: Commit the contract.**

```text
git add app/src/main/java/vn/chamcong/iot/model/Models.kt app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt app/src/main/java/vn/chamcong/iot/model/AttendanceResolutionModels.kt app/src/test/java/vn/chamcong/iot/domain/AttendanceResolutionRulesTest.kt app/src/test/java/vn/chamcong/iot/domain/AttendanceAdjustmentRulesTest.kt
git commit -m "feat: add attendance resolution and adjustment models"
```

## Task 2: Pure Kotlin schedule/session resolver

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt`
- Modify: `app/src/test/java/vn/chamcong/iot/domain/AttendanceResolutionRulesTest.kt`
- Modify: `app/src/test/java/vn/chamcong/iot/domain/SchedulingRulesTest.kt`

**Interfaces:**
- `data class ShiftWindow(val scheduleDate: LocalDate, val start: Instant, val end: Instant)`.
- `fun shiftWindow(scheduleDate: LocalDate, shift: WorkShift, zoneId: ZoneId): ShiftWindow`.
- `fun resolveAttendancePair(rows: List<Attendance>, scheduleDate: LocalDate, shift: WorkShift?, adjustments: List<AttendanceAdjustment> = emptyList(), zoneId: ZoneId): AttendancePair`.
- `fun isMissingCheckOut(pair: AttendancePair, scheduleDate: LocalDate, shift: WorkShift?, now: Instant, zoneId: ZoneId): Boolean`.
- `fun latestAdjustment(adjustments: List<AttendanceAdjustment>, employeeId: String, scheduleDate: LocalDate): AttendanceAdjustment?`.

- [ ] **Step 1: Write failing tests for the window and pair rules.** Cover a `22:00–06:00` shift whose end is the next day, accepted `23:00`/`05:30` events paired under one schedule date, `DUPLICATE`/`UNSCHEDULED`/`OUT_OF_ORDER` excluded, and the latest adjustment replacing a missing raw check-out.

- [ ] **Step 2: Write failing tests for missing checkout.** Assert a check-in before the end+60-minute grace is not missing, the same check-in after grace is `MISSING_CHECK_OUT`, and a completed pair is never missing. Add an approved-leave case through the existing presence rule.

- [ ] **Step 3: Run the focused tests and verify RED.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.AttendanceResolutionRulesTest --tests vn.chamcong.iot.domain.SchedulingRulesTest`

Expected: failures for missing resolver symbols or incorrect existing calendar-day pairing.

- [ ] **Step 4: Implement `shiftWindow` and pair resolution.** Convert local start/end into absolute instants, add one day when end is not after start, sort only accepted legacy/resolved check-in/out rows by event timestamp, ignore excluded resolution statuses, pair each check-in with the next later check-out, and overlay the latest validated adjustment without mutating rows.

- [ ] **Step 5: Replace the private calendar-day pairer in `SchedulingRules.kt`.** Make weekly summaries call `resolveAttendancePair` per `scheduleDate`; preserve current lunch/overtime calculations and worked-hours override behavior, but use the new pair's instants.

- [ ] **Step 6: Implement and test `isMissingCheckOut`.** Use shift end plus `missingCheckOutGraceMinutes`; for a past schedule date with an open check-in return true; for no shift keep the existing conservative behavior based on an open pair.

- [ ] **Step 7: Run the focused and existing scheduling tests.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.AttendanceResolutionRulesTest --tests vn.chamcong.iot.domain.SchedulingRulesTest`

Expected: all pass, including prior lunch, overtime, weekly summary, and worked-hours override tests.

- [ ] **Step 8: Commit the pure resolver.**

```text
git add app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt app/src/test/java/vn/chamcong/iot/domain/AttendanceResolutionRulesTest.kt app/src/test/java/vn/chamcong/iot/domain/SchedulingRulesTest.kt
git commit -m "feat: resolve attendance pairs by shift schedule"
```

## Task 3: Cloud Function raw event resolver

**Files:**
- Create: `firebase/functions/attendanceResolver.js`
- Create: `firebase/functions/test/attendanceResolver.test.js`
- Modify: `firebase/functions/index.js`
- Modify: `firebase/functions/package.json`

**Interfaces:**
- `const DUPLICATE_WINDOW_MS = 180000`.
- `const TIME_ZONE = "Asia/Ho_Chi_Minh"`.
- `function buildShiftWindow(scheduleDate, shift, timeZone = TIME_ZONE)` returns `{ scheduleDate, startMs, endMs }`.
- `function pickSchedule(scanMs, schedules)` returns the nearest schedule or `null`, accepting only the window `[startMs - allowEarlyMinutes*60000, endMs + missingCheckOutGraceMinutes*60000]`.
- `function resolveScan({ scan, schedules, session, latestAccepted })` returns `{ type, resolutionStatus, scheduleDate, shiftId, status, nextSession }` without Firebase imports.

- [ ] **Step 1: Add the Node test script and failing pure tests.** Update `firebase/functions/package.json` with `"scripts": { "test": "node --test" }`. Add tests for same-day window, overnight window, first scan near start, first scan near end, open check-in to check-out, 3-minute duplicate, closed-session rejection, no schedule, and out-of-order.

- [ ] **Step 2: Run the Node tests and verify RED.**

Run from `firebase/functions`: `npm test`

Expected: module/function-not-found failures.

- [ ] **Step 3: Implement the pure helper.** Use `Intl.DateTimeFormat` with `Asia/Ho_Chi_Minh` to derive local date/time, parse `HH:mm`, build a next-day end for overnight shifts, select the closest candidate window, and return deterministic updates. Do not call Firestore from this file.

- [ ] **Step 4: Run the Node tests and verify GREEN.**

Run from `firebase/functions`: `npm test`

Expected: all resolver tests pass.

- [ ] **Step 5: Refactor `recordAttendance` to write raw `SCAN`.** Keep the current auth/API-key checks and template validation, but write `type: "SCAN"`, `resolutionStatus: "PENDING"`, `status: "PENDING"`, device timestamp, and `receivedAt`; remove all `localHour < 12` logic. Keep eventId idempotency.

- [ ] **Step 6: Add the Firestore `onDocumentCreated` resolver.** Add `resolveAttendance` in `index.js`; re-read the active employee mapping by template, read the schedule documents for local date and previous date, read/update `attendanceSessions/{employeeId}_{scheduleDate}` and the raw event in one transaction, and apply `resolveScan`. If the event is already resolved, return without mutation. Use `FieldValue.serverTimestamp()` for `receivedAt`/`resolvedAt`/`updatedAt`.

- [ ] **Step 7: Run tests and inspect for the removed 12-hour heuristic.**

Run from `firebase/functions`: `npm test`

Run: `rg -n "localHour|< 12|tm_hour.*12|CHECK_IN.*12|CHECK_OUT.*12" firebase/functions/index.js firebase/functions/attendanceResolver.js`

Expected: Node tests pass and the search returns no old attendance-type heuristic.

- [ ] **Step 8: Restrict attendance notifications to accepted events.** Update `notifyAttendance` to return without sending a push when `resolutionStatus` is not `ACCEPTED`; include `scheduleDate` and the resolved type in the accepted notification payload.

- [ ] **Step 9: Commit the Cloud Function.**

```text
git add firebase/functions/index.js firebase/functions/attendanceResolver.js firebase/functions/test/attendanceResolver.test.js firebase/functions/package.json
git commit -m "feat: resolve raw attendance events in cloud function"
```

## Task 4: Firmware raw scan payload

**Files:**
- Modify: `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` around `buildAttendanceEvent`, `uploadAttendance`, and LCD result handling.
- Modify: `README.md` firmware/attendance contract section.

**Interfaces:**
- `buildAttendanceEvent` continues returning the existing event id/payload/output parameters so the LittleFS outbox stays compatible.
- New payload values are `type=SCAN`, `resolutionStatus=PENDING`, `status=PENDING`; `timestamp` remains the NTP UTC timestamp.

- [ ] **Step 1: Record the current firmware contract.** Run `rg -n "localTime\.tm_hour|attendanceType|fields\[\"type\"\]|fields\[\"status\"\]" firmware/esp8266_fingerprint/esp8266_fingerprint.ino` and confirm the only type decision is in `buildAttendanceEvent`.

- [ ] **Step 2: Remove the local 12-hour decision.** Keep local time only for the display string; set `attendanceType = "SCAN"`, write the pending resolution fields, and display a neutral “đã nhận/đang xử lý” message instead of “VÀO” or “RA”. Do not change eventId generation, outbox retry, or timestamp source.

- [ ] **Step 3: Verify the source contract.** Run the same `rg` command and inspect the payload block. Expected: no comparison of local hour against 12 and no locally generated `CHECK_IN`/`CHECK_OUT`.

- [ ] **Step 4: Compile if the Arduino CLI/toolchain is installed.** Run `arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 firmware/esp8266_fingerprint`; if the toolchain is unavailable, record that limitation and complete the source-level checks above.

- [ ] **Step 5: Commit the firmware contract.**

```text
git add firmware/esp8266_fingerprint/esp8266_fingerprint.ino README.md
git commit -m "feat: send raw fingerprint scans for cloud resolution"
```

## Task 5: Firestore repository, adjustment storage and security rules

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` `MainUiState` section.
- Modify: `firebase/firestore.rules`
- Modify: `firebase/firestore.indexes.json`
- Create or modify: repository/domain validation tests under `app/src/test/java/vn/chamcong/iot/domain/`.

**Interfaces:**
- `fun observeAttendanceAdjustments(): Flow<List<AttendanceAdjustment>>` for Admin.
- `fun observeEmployeeAttendanceAdjustments(employeeId: String): Flow<List<AttendanceAdjustment>>` for employee shell.
- `suspend fun saveAttendanceAdjustment(adjustment: AttendanceAdjustment): String` validates, reads the previous latest adjustment, and writes adjustment plus audit in one batch with server timestamps.
- `MainUiState.attendanceAdjustments: List<AttendanceAdjustment>`.
- `MainViewModel.adjustAttendance(adjustment: AttendanceAdjustment, done: () -> Unit)`.

- [ ] **Step 1: Add failing tests for adjustment persistence rules.** Test that repository-facing/domain input requires reason and one effective value, legacy attendance remains immutable by contract, and the latest adjustment is selected by creation time. Keep Firebase I/O out of unit tests.

- [ ] **Step 2: Implement Firestore mapping helpers.** Add `AttendanceAdjustment.toFirestoreData()` with nullable timestamps preserved and `DocumentSnapshot.toAttendanceAdjustment()`; add `observeAttendanceAdjustments` and employee-scoped observation sorted by `createdAt` descending.

- [ ] **Step 3: Implement `saveAttendanceAdjustment`.** Validate the adjustment, generate an auto id, read the current latest adjustment for before/after details, and use `runBatch` to create the adjustment and an audit document. Put `FieldValue.serverTimestamp()` in both `createdAt` fields and use `ATTENDANCE_ADJUST`/`attendanceAdjustment`.

- [ ] **Step 4: Subscribe the new flow in `MainViewModel`.** Add the list to Admin state, load it with the existing data subscriptions, pass it into presence/report/payroll domain calls, and expose `adjustAttendance` through the existing `perform` helper.

- [ ] **Step 5: Tighten Firestore rules.** Require device-created attendance to have `type == 'SCAN'`, `resolutionStatus == 'PENDING'`, active mapping, and immutable client updates/deletes. Add `attendanceSessions` deny-all for clients. Add admin create/read and no update/delete for `attendanceAdjustments`; add employee read only for their own employee id if the employee UI consumes it. Keep `audit_logs` append-only and require actor identity/reason for adjustment audit.

- [ ] **Step 6: Add indexes.** Add only the composite indexes required by the repository queries: `attendanceAdjustments(employeeId ASC, scheduleDate ASC, createdAt DESC)` and any `attendance` employee/timestamp query that the emulator reports as missing.

- [ ] **Step 7: Run Android tests and rules syntax checks.**

Run: `gradlew.bat :app:testDebugUnitTest`

Run if Firebase CLI is installed: `firebase firestore:rules --help` and use the project’s available rules validation/emulator command; do not deploy production rules in this task.

- [ ] **Step 8: Commit repository and rules.**

```text
git add app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt firebase/firestore.rules firebase/firestore.indexes.json app/src/test/java/vn/chamcong/iot/domain
git commit -m "feat: persist audited attendance adjustments"
```

## Task 6: Integrate resolved pairs into presence, reports, payroll and employee summaries

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/model/PersonnelRules.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeAttendanceScreen.kt` only if the displayed summary needs the new resolution fields.
- Modify: existing `PresenceRulesTest.kt`, `ReportRulesTest.kt`, `PayrollRulesTest.kt`, `EmployeeRulesTest.kt`.

**Interfaces:**
- Preserve existing public function call compatibility with default `adjustments = emptyList()` where tests or screens do not yet pass adjustment state.
- Extend `classifyPresence`/`classifyPresenceForEmployees`, `attendanceReportRows`, and `workedHoursForMonth` to accept schedules, shifts, and adjustments.

- [ ] **Step 1: Write failing integration tests.** Add tests proving a `DUPLICATE` does not add hours, an overnight pair appears on its start date, missing checkout is based on shift end plus grace, the latest adjustment supplies a checkout or worked-hours override, and reports/payroll use the same effective pair.

- [ ] **Step 2: Run the focused suite and verify RED.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.PresenceRulesTest --tests vn.chamcong.iot.domain.ReportRulesTest --tests vn.chamcong.iot.model.PayrollRulesTest --tests vn.chamcong.iot.domain.EmployeeRulesTest`

Expected: failures or incorrect assertions because these paths still group by calendar date and ignore adjustments/resolution status.

- [ ] **Step 3: Update presence classification.** For a selected schedule date, include the date and next local day when the shift crosses midnight, use accepted resolved pairs, prioritize approved leave, and call `isMissingCheckOut` instead of the old “older than 12 hours” rule.

- [ ] **Step 4: Update employee monthly summaries and reports.** Group rows by `scheduleDate` when present, use the shared pair resolver, show `MISSING_CHECK_OUT`/`ABNORMAL` for unresolved states, and overlay the latest adjustment without mutating attendance.

- [ ] **Step 5: Update monthly payroll hours.** Add optional schedules/shifts/adjustments parameters to `workedHoursForMonth`, pass the state values from `PayrollScreen`, and preserve raw-attendance fallback for legacy data and existing tests.

- [ ] **Step 6: Run all focused tests and verify GREEN.**

Run: `gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.PresenceRulesTest --tests vn.chamcong.iot.domain.ReportRulesTest --tests vn.chamcong.iot.model.PayrollRulesTest --tests vn.chamcong.iot.domain.EmployeeRulesTest`

Expected: all new and existing assertions pass.

- [ ] **Step 7: Commit the calculation integration.**

```text
git add app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt app/src/main/java/vn/chamcong/iot/model/PersonnelRules.kt app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeAttendanceScreen.kt app/src/test/java/vn/chamcong/iot/domain/PresenceRulesTest.kt app/src/test/java/vn/chamcong/iot/domain/ReportRulesTest.kt app/src/test/java/vn/chamcong/iot/model/PayrollRulesTest.kt app/src/test/java/vn/chamcong/iot/domain/EmployeeRulesTest.kt
git commit -m "feat: calculate work from resolved attendance pairs"
```

## Task 7: Admin attendance UI and visible resolution states

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentDialog.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt` only if callback wiring is needed.
- Modify: `app/src/main/java/vn/chamcong/iot/ui/audit/AuditScreen.kt` only if action labels/details need formatting.

**Interfaces:**
- `@Composable fun AttendanceAdjustmentDialog(target: AttendanceAdjustmentTarget, state: MainUiState, onDismiss: () -> Unit, onSubmit: (AttendanceAdjustment) -> Unit)`.
- `data class AttendanceAdjustmentTarget(employeeId: String, employeeName: String, scheduleDate: String, currentCheckIn: Timestamp?, currentCheckOut: Timestamp?, currentWorkedHours: Double?)`.

- [ ] **Step 1: Add a UI-level validation test or pure parser test.** Cover ISO date-time input `yyyy-MM-dd HH:mm`, blank optional fields, invalid time order, and blank reason. Keep parser in a small pure function if Compose testing is not configured.

- [ ] **Step 2: Build the dialog.** Show employee/date/current values, optional check-in/check-out fields, optional worked-hours override, a required reason field, and an error message when no value is changed or validation fails. Use the fixed `Asia/Ho_Chi_Minh` zone for parsing.

- [ ] **Step 3: Add the adjustment action to attendance rows.** Show `SCAN/PENDING`, `DUPLICATE`, `UNSCHEDULED`, and `OUT_OF_ORDER` distinctly from accepted events; add an Admin-only “Điều chỉnh” action that opens the dialog for the row’s employee and `scheduleDate` or local date fallback.

- [ ] **Step 4: Wire submission through `MainViewModel.adjustAttendance`.** Disable duplicate submits while `state.saving` is true, surface repository errors, close the dialog only after success, and rely on the adjustment listener to refresh reports/presence/payroll.

- [ ] **Step 5: Build the Android app and inspect UI compilation.**

Run: `gradlew.bat :app:assembleDebug`

Expected: successful compilation with no new Compose or model errors.

- [ ] **Step 6: Commit the Admin UI.**

```text
git add app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentDialog.kt app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt app/src/main/java/vn/chamcong/iot/ui/audit/AuditScreen.kt
git commit -m "feat: add audited attendance adjustment dialog"
```

## Task 8: Documentation, full verification and handoff

**Files:**
- Modify: `README.md`
- Modify: `firebase/functions/README.md` if a functions-specific README is introduced during implementation.

- [ ] **Step 1: Update README attendance documentation.** Document raw `SCAN`, Cloud Function resolution, `scheduleDate`, overnight shifts, duplicate window, missing checkout grace, adjustment collection, and audit behavior. Add every changed file to the project’s existing change log section.

- [ ] **Step 2: Run the complete Android test suite.**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: all unit tests pass.

- [ ] **Step 3: Build the Android debug artifact.**

Run: `gradlew.bat :app:assembleDebug`

Expected: `app/build/outputs/apk/debug/app-debug.apk` is generated.

- [ ] **Step 4: Run Cloud Function tests.**

Run from `firebase/functions`: `npm test`

Expected: all Node resolver tests pass.

- [ ] **Step 5: Run final static checks.**

Run: `rg -n "localHour|tm_hour.*12|< 12|CHECK_IN.*12|CHECK_OUT.*12" firmware firebase/functions app/src/main/java`

Expected: no attendance type decision remains based on 12 hours. Existing unrelated time comparisons must be manually inspected before accepting the result.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 6: Commit documentation and verification metadata.**

```text
git add README.md
git commit -m "docs: document schedule-based attendance workflow"
```

- [ ] **Step 7: Report evidence.** Include test commands/results, build artifact path, whether Arduino CLI and Firebase emulator validation were available, and any deployment step intentionally left for the user.
