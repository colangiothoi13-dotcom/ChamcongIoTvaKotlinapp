# Admin Navigation and Task Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the Admin UI into the approved five-item navigation while keeping the existing weekly overview and every existing business screen reachable.

**Architecture:** Keep the existing MVVM state, Firebase repository, and feature screens unchanged. Add presentation-only navigation destinations and two small hubs: a task hub for secondary modules and a shift hub for the two existing shift/schedule screens. The Admin shell routes primary tabs to existing screens and uses task-card callbacks for secondary screens.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, existing Firebase/Compose dependencies.

**Spec:** Approved in chat on 2026-09-14: `Tổng quan`, `Tác vụ`, `Đơn từ`, `Phân ca`, `Nhân viên`; keep the current Overview and move missing functions into Tasks.

## Global Constraints

- Do not change Firebase collections, repository contracts, security rules, or weekly calculation logic.
- Preserve all existing Admin feature screens and their ViewModel callbacks.
- Keep Employee navigation and role restrictions unchanged.
- The approved Admin primary order must remain exact.
- Run unit tests and assemble the debug APK after implementation.
- Record all implementation and verification work in `README.md`.

### Task 1: Lock the approved navigation contract

**Files:**
- Modify: `app/src/test/java/vn/chamcong/iot/ui/NavigationStructureTest.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`

- [x] Update the Admin navigation assertion to `Tổng quan`, `Tác vụ`, `Đơn từ`, `Phân ca`, `Nhân viên` and add an assertion that all secondary modules are exposed by the task hub contract.
- [x] Run the targeted test and confirm it fails because the current Admin order is different.
- [x] Add `TASKS` and `SHIFT_MANAGEMENT` presentation destinations and update the Admin primary list.
- [x] Run the targeted test and confirm it passes.

### Task 2: Add the Admin task and shift hubs

**Files:**
- Create: `app/src/main/java/vn/chamcong/iot/ui/admin/AdminTasksScreen.kt`
- Create: `app/src/main/java/vn/chamcong/iot/ui/admin/ShiftManagementScreen.kt`
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`

- [x] Define stable task groups for Chấm công & thiết bị, Lịch & ca, Lương & báo cáo, and Quản trị.
- [x] Render each group as a white rounded card with icon, title, description, and click action, matching the supplied visual reference.
- [x] Render the shift hub with cards for `Ca làm` and `Lịch tuần`, delegating to the existing `ShiftsScreen` and `ScheduleScreen`.
- [x] Route all task cards to the current screens without changing their data or business logic.

### Task 3: Restyle the Admin shell around the approved navigation

**Files:**
- Modify: `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`

- [x] Keep `DashboardScreen` as the `Tổng quan` destination and preserve its week-based chart.
- [x] Make the bottom bar render exactly the five approved destinations.
- [x] Move secondary destinations out of the account dropdown into the task hub; keep only đổi mật khẩu and đăng xuất in the account menu.
- [x] Keep the existing employee dialog, fingerprint enrollment, salary, and retire/delete flows attached to the Admin shell.

### Task 4: Verify and document

**Files:**
- Modify: `README.md`

- [x] Run all unit tests and `:app:assembleDebug`.
- [x] Confirm no Firebase or Employee-role behavior changed.
- [x] Add a dated README log with the new navigation map, task groups, verification result, and APK path.
