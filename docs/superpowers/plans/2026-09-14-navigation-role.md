# Admin and Employee Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the Admin shell into five primary destinations and add a role-safe four-destination Employee shell while preserving existing modules.

**Architecture:** Keep the existing single `MainViewModel` and Firebase repository, but branch subscriptions after loading `users/{uid}`. Admin keeps company-wide listeners; Employee receives direct employee-scoped listeners using `employeeId`. Compose gets two small shells and focused Employee screens.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Firebase Authentication, Cloud Firestore, Kotlin coroutines/Flow, JUnit.

**Spec:** `docs/superpowers/specs/2026-09-14-navigation-role-design.md`

## Global Constraints

- Admin primary navigation must contain exactly `Tổng quan`, `Chấm công`, `Nhân viên`, `Đơn từ`, `Thiết bị`.
- Employee primary navigation must contain exactly `Trang chủ`, `Chấm công của tôi`, `Đơn từ`, `Cá nhân`.
- Employee data access is always scoped by `users/{uid}.employeeId`; no company-wide employee listener is allowed for Employee accounts.
- Existing Admin modules and actions remain reachable from the top/profile menu.
- A newly submitted Employee request is always `PENDING` and cannot set reviewer fields.

---

### Task 1: Role and Employee-domain rules

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt`
- Create: `app/src/main/java/vn/chamcong/iot/model/EmployeeModels.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/model/UserModels.kt`
- Test: `app/src/test/java/vn/chamcong/iot/domain/EmployeeRulesTest.kt`
- Test: `app/src/test/java/vn/chamcong/iot/domain/AuditRulesTest.kt`

**Interfaces:**
- Produces `canAccessEmployee(provider: String, profile: UserProfile?): Boolean`.
- Produces `EmployeeDaySummary` and `employeeDaySummary(...)` for one employee/date.
- Produces `employeeMonthSummaries(...)` for the selected month.
- Produces `employeeRequestDraft(...)` that always returns a `PENDING` request with no reviewer.

- [ ] **Step 1: Write failing tests** for active Employee access, missing employee link, day status/working hours, month rows, and pending request drafts.
- [ ] **Step 2: Run `EmployeeRulesTest` and `AuditRulesTest`** and confirm the new symbols/behavior fail because production APIs are missing.
- [ ] **Step 3: Implement the minimal models/rules** and add `employeeId: String?` to `UserProfile`.
- [ ] **Step 4: Run the targeted tests again** and confirm they pass.

### Task 2: Employee-scoped repository and ViewModel state

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`
- Modify: `firebase/firestore.rules`

**Interfaces:**
- Repository produces `observeEmployee(employeeId)`, `observeEmployeeAttendance(employeeId)`, `observeEmployeeSchedules(employeeId)`, `observeEmployeeRequests(employeeId)`, and `submitEmployeeRequest(request)`.
- ViewModel exposes `currentEmployee`, `employeeAttendance`, `employeeSchedules`, `employeeRequests`, `hasEmployeeAccess()`, `submitEmployeeRequest(...)`, and `employeeMonthSummaries(...)`.

- [ ] **Step 1: Add a failing repository contract/domain test** asserting a new employee draft cannot become approved or target another employee.
- [ ] **Step 2: Run the targeted test and confirm RED.**
- [ ] **Step 3: Add employee-scoped Firestore observers and role-aware subscription branching.**
- [ ] **Step 4: Update Rules** for self-only reads and PENDING request creation while keeping Admin rules unchanged.
- [ ] **Step 5: Run targeted tests and static Rules checks.**

### Task 3: Employee Compose screens

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeHomeScreen.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeAttendanceScreen.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeRequestsScreen.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeProfileScreen.kt`

- [ ] **Step 1: Add a small compile-level test fixture** for the screen-facing month summary API and run it RED.
- [ ] **Step 2: Implement the four screens** with current-month data, no direct attendance edit, request creation, request history, profile fields, change-password callback, and sign-out callback.
- [ ] **Step 3: Run the domain tests and Android compile** to catch Compose/API errors.

### Task 4: Admin five-item shell and role routing

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/SettingsScreen.kt` only if needed for the menu entry.

- [ ] **Step 1: Add a pure navigation structure test** asserting the exact five Admin and four Employee labels.
- [ ] **Step 2: Run it RED against the current twelve-item Admin list.**
- [ ] **Step 3: Replace the twelve-item bottom bar with the five-item Admin bar and an overflow/profile menu for existing secondary modules.**
- [ ] **Step 4: Route Employee accounts to the four-screen shell and preserve the access-blocked state for unlinked/inactive accounts.**
- [ ] **Step 5: Run the targeted navigation/domain tests.**

### Task 5: Documentation and full verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the new navigation, the `users/{uid}.employeeId` setup, self-only Rules, and the fact that attachments/settings backend are not yet implemented.**
- [ ] **Step 2: Run all unit tests.**
- [ ] **Step 3: Assemble the debug APK.**
- [ ] **Step 4: Inspect test XML, APK output, and Firestore Rules/index JSON.**
