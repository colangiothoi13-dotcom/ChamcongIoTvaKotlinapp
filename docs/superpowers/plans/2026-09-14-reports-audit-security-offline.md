# Báo cáo, audit, bảo mật và đồng bộ offline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bổ sung báo cáo có lọc/xuất CSV, audit log bất biến, bảo mật role Firebase và hàng đợi chấm công offline cho app hiện tại.

**Architecture:** Giữ MVVM và FirebaseRepository; thêm domain thuần cho báo cáo/audit, exporter CSV chạy local, user profile role trong Rules và outbox LittleFS trên ESP8266. Attendance/payroll gốc không bị sửa hoặc xóa.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose Material 3, Firebase Auth/Firestore, WorkManager, JUnit 4, Android FileProvider, ESP8266 LittleFS/ArduinoJson.

**Spec:** `docs/superpowers/specs/2026-09-14-reports-audit-security-offline-design.md`

## Global Constraints

- Báo cáo lọc theo khoảng ngày đóng, employee và department; CSV là định dạng xuất MVP.
- Audit log ghi actor/action/target/reason/details/server timestamp và không cho update/delete.
- Chỉ user Email/Password có role ADMIN mới dùng màn admin; anonymous chỉ dành cho thiết bị.
- Attendance/payroll cũ không update/delete; điều chỉnh giờ dùng override/audit.
- Outbox firmware giữ cùng `eventId` khi retry và không tạo bản ghi trùng.
- TDD: mỗi domain mới có test RED trước implementation, sau đó GREEN và full suite.
- Mọi file tạo/sửa phải ghi trong `README.md`; repo không có `.git`, không commit.

## File Map

- Create: `app/src/main/java/vn/chamcong/iot/model/ReportModels.kt`, `AuditModels.kt`, `UserModels.kt`.
- Create: `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt`, `AuditRules.kt`, `OfflineQueueRules.kt`.
- Create: `app/src/main/java/vn/chamcong/iot/data/CsvReportExporter.kt`.
- Create: `app/src/test/java/vn/chamcong/iot/domain/ReportRulesTest.kt`, `AuditRulesTest.kt`, `OfflineQueueRulesTest.kt`.
- Create: `app/src/test/java/vn/chamcong/iot/data/CsvReportExporterTest.kt`.
- Create: `app/src/main/java/vn/chamcong/iot/ui/reports/ReportsScreen.kt`, `ui/audit/AuditScreen.kt`.
- Create: `app/src/main/res/xml/file_paths.xml`.
- Modify: `FirebaseRepository.kt`, `MainViewModel.kt`, `ChamCongApp.kt`, `ui/devices/DevicesScreen.kt`, `AndroidManifest.xml`, `firestore.rules`, `firestore.indexes.json`, `README.md`.
- Modify: `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`.

### Task 1: Report domain and CSV exporter

**Files:** `ReportModels.kt`, `ReportRules.kt`, `CsvReportExporter.kt`, matching tests.

**Interfaces:**

```kotlin
data class ReportFilter(val startDate: LocalDate, val endDate: LocalDate, val employeeId: String? = null, val department: String? = null)
data class AttendanceReportRow(val date: String, val employeeId: String, val employeeName: String, val department: String, val checkIn: String, val checkOut: String, val status: String, val workedHours: Double, val overtimeHours: Double)
data class DeviceActivityRow(val deviceId: String, val status: String, val lastHeartbeat: String, val firmwareVersion: String, val fingerprintCount: Int?, val capacity: Int?, val failedCommandCount: Int)
fun validateReportFilter(filter: ReportFilter)
fun filterEmployees(employees: List<Employee>, filter: ReportFilter): List<Employee>
fun attendanceReportRows(filter: ReportFilter, employees: List<Employee>, attendance: List<Attendance>, schedules: List<WorkSchedule>, shifts: List<WorkShift>, approvedRequests: List<LeaveRequest>, zoneId: ZoneId): List<AttendanceReportRow>
fun deviceActivityRows(devices: List<DeviceSnapshot>, failedCommands: List<Map<String, Any>>): List<DeviceActivityRow>
fun attendanceRowsToCsv(rows: List<AttendanceReportRow>): String
fun deviceRowsToCsv(rows: List<DeviceActivityRow>): String
```

- [ ] Write tests for invalid range, employee+department filter, attendance row and CSV quoting.
- [ ] Run targeted tests and confirm RED because APIs do not exist.
- [ ] Implement models/rules by composing existing `calculateWorkTime`, `mondayOfWeek` and employee/attendance data.
- [ ] Implement UTF-8 BOM CSV with a shared `escapeCsv` helper and deterministic headers.
- [ ] Run targeted tests and confirm GREEN.

### Task 2: Audit and user domain

**Files:** `AuditModels.kt`, `UserModels.kt`, `AuditRules.kt`, tests.

**Interfaces:**

```kotlin
enum class AuditAction { LOGIN, EMPLOYEE_CREATE, EMPLOYEE_UPDATE, FINGERPRINT_DELETE, ATTENDANCE_ADJUST, LEAVE_REVIEW, SHIFT_UPDATE, DEVICE_CONFIG_UPDATE, PASSWORD_CHANGE }
data class AuditLog(val id: String = "", val actorId: String = "", val actorName: String = "", val action: String = "", val targetType: String = "", val targetId: String = "", val reason: String = "", val details: String = "", val createdAt: Timestamp = Timestamp.now())
data class UserProfile(val uid: String = "", val email: String = "", val displayName: String = "", val role: String = "EMPLOYEE", val active: Boolean = true, val updatedAt: Timestamp = Timestamp.now())
fun validateAuditLog(log: AuditLog)
fun canReviewAuditLog(log: AuditLog): Boolean
```

- [ ] Write tests for supported actions, required actor/target and immutable audit behavior.
- [ ] Run targeted tests and confirm RED.
- [ ] Implement validation and role constants without Firebase calls.
- [ ] Run targeted tests and confirm GREEN.

### Task 3: Repository, reports UI and share flow

**Files:** `FirebaseRepository.kt`, `MainViewModel.kt`, `ReportsScreen.kt`, `ChamCongApp.kt`, `CsvReportExporter.kt`, `file_paths.xml`, `AndroidManifest.xml`, `firestore.indexes.json`.

- [ ] Add audit observer and `writeAuditLog` using server timestamp; call it from employee/shift/schedule/leave review/password actions.
- [ ] Add `observeAuditLogs`, `observeUserProfile`, `sendPasswordReset`, `changePassword`, and repository report filter state.
- [ ] Add `ReportsScreen` for report type, date range, employee/department filters, table preview and CSV share through FileProvider.
- [ ] Wire report screen and audit screen into navigation; keep existing weekly dashboard.
- [ ] Add only required audit `createdAt` index and run compile.

### Task 4: Authentication and Firestore Rules

**Files:** `ChamCongApp.kt`, `MainViewModel.kt`, `FirebaseRepository.kt`, `firebase/firestore.rules`, `README.md`.

- [ ] Add forgot-password action from login and change-password dialog for signed-in admin.
- [ ] Make `isAdmin()` role-aware while preserving bootstrap email users with no profile; deny inactive/EMPLOYEE profile.
- [ ] Add Rules for `users`, `audit_logs`, `departments`, `settings`; make audit immutable; restrict `syncReceipts` to admin.
- [ ] Preserve anonymous device writes only for validated attendance/device snapshot paths.
- [ ] Add tests for domain role/audit behavior and inspect Rules text for all allow paths.

### Task 5: Firmware offline outbox

**Files:** `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`, README.

- [ ] Add `LittleFS` initialization and `/attendance.outbox` JSON-lines helpers with a bounded maximum size.
- [ ] Refactor attendance payload construction into `buildAttendanceEvent`; enqueue before network upload when offline or upload fails.
- [ ] Add `flushAttendanceOutbox` on every loop heartbeat/network opportunity; send original event id and remove only after 2xx/duplicate success.
- [ ] Show `CHO DONG BO` on pending events and log overflow/network retry; keep sensor failure feedback.
- [ ] Run Arduino compile if `arduino-cli` is available; otherwise document that Android tests/build are the verified checks.

### Task 6: Verification and documentation

**Files:** all paths in File Map and `README.md`.

- [ ] Run focused Report/Audit/CSV tests through the cached Gradle 8.9 setup if the wrapper jar is still absent.
- [ ] Run full `:app:testDebugUnitTest :app:assembleDebug` and inspect JUnit XML counts plus APK path.
- [ ] Run `rg` audit for report/audit/security/offline references and compare changed file list with README.
- [ ] Record actual test/build/firmware results and Firebase deployment status in README.
