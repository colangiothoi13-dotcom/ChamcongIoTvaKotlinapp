# Final review fix wave — 2026-09-17

Status: all nine supplied findings addressed within the isolated worktree. Local verification passed with the limitations below. No deployment, production data writes, merge, push, APK installation or firmware flash.

## Scope and commits

- Base: `80b5d36`, branch `feat/attendance-resolution-adjustment`.
- Implementation, regression tests, rules and README commit: `ec82b3a6e4e19d028d98d5d9b8eb17bf0089d48d` — `fix: resolve final attendance review findings across payroll and presence` (19 files).
- This report is committed separately to identify the implementation commit without a self-referential hash. Obtain its commit with `git log -1 --format=%H -- .superpowers/sdd/2026-09-17-attendance-resolution-adjustment/final-fix-report.md`.
- Worktree: `D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment`.
- Existing untracked `.superpowers/firebase-cli-config/` was preserved and excluded. Generated build outputs were not staged. No unrelated code or firmware changed.
- Used the TDD and verification-before-completion skills: observed failing regressions before the corresponding behavior fixes, then verified the final compiled implementation. Follow-up boundary/state coverage and the small Compose binding change have the specific limitations described below.

## Findings addressed

| Finding | Change and evidence |
| --- | --- |
| 1 — canonical employee ID | `getMappedActiveEmployee` now spreads document data first and snapshot ID last. The Node test executes the real `recordAttendance` handler with an Android-shaped employee document containing `id=""`; it asserts successful raw-scan creation using `employee-1`. Only Firebase registration, secrets and transaction IO are replaced at the boundary. |
| 2 — period-independent schedules | Admin `observeSchedules()` listens to all schedules. Week selection no longer clears/replaces schedule context. Payroll, reports and presence use this context, including overnight dates outside the selected week. The state/domain test checks a September 30–October 1 shift while selecting two unrelated weeks: September payroll/report is 7.17 hours, October payroll is zero, checkout is 06:00, presence is LEFT. Payroll's automatic input follows arriving context until the user explicitly edits hours. |
| 3 — production rejection shapes | Shared accepted/abnormal predicates recognize verified server-shaped duplicates and older typed duplicate rows. Duplicates do not override a completed/open valid pair; UNSCHEDULED and OUT_OF_ORDER stay abnormal. Unknown types/resolution/status and unverified rows are excluded from accepted pairs. Domain tests exercise the server's actual type/resolution/status shapes. |
| 4 — missing-checkout deadline | Employee day/month summaries and reports receive a defaulted `Instant.now()` parameter and use shared `isMissingCheckOut`. Added PRESENT with the Vietnamese in-progress label. Tests cover daytime and overnight deadlines with 0/60-minute grace, one second before, exactly at, and one second after, plus the no-shift next-day fallback. |
| 5 — leave ownership | Weekly approved leave keys now use only `request.employeeId`, restricted to active employees. Two-employee regression expects one approved leave day and one unauthorized absence. |
| 6 — overnight breaks | Break start/end anchor within the shift window, including post-midnight start and midnight-crossing breaks. 22:00–06:00 with 02:00–02:30 now pays 7.5 hours. Same-day and 23:45–00:15 regressions also pass. |
| 7 — malformed accepted status | Shared accepted eligibility validates `Attendance.status` against supported values. Blank, UNKNOWN, PENDING and padded invalid statuses on either pair endpoint cannot create payable hours. Payroll, report, employee, presence and weekly-summary assertions pass; model-default legacy rows still pay 9 hours in the fixture. Explicit authorized hours overrides retain their existing precedence. |
| 8 — effective late/dashboard/history | Dashboard and history derive lateness from schedule/effective check-in; history uses computed copies while preserving source order. Dashboard is derived from each `MainUiState`, so adjustment/shift/schedule subscription emissions recompute it without a new scan. Tests cover server NORMAL being late, correction to on-time, adjustment-only presence, shift grace updates, invalid/rejected exclusion and immutable raw rows. No-shift legacy status fallback remains. |
| 9 — optional shift grace rules | `missingCheckOutGraceMinutes` may be omitted; if present it must be an integer >= 0. Authenticated local emulator tests cover both create and update for omitted, zero, positive, negative, fractional, string and null values. |

New attendance calculation helpers use model values and `java.time`, with no Firebase APIs or IO in the calculation logic. Removed the direct Timestamp import from the touched EmployeeRules file by retaining the LeaveRequest model's existing timestamp default. The pre-existing `RequestRules.kt` Timestamp parameter/model coupling was not refactored; no new Firebase dependency was introduced into domain code.

## RED evidence

1. Before production edits, `npm test` in `firebase/functions`: **16 tests, 15 passed, 1 failed**, exit 1. The Android document test got HTTP **404** / `FINGERPRINT_NOT_REGISTERED` instead of 200.
2. Compiled the initial new Kotlin regressions against unchanged production code using cached Gradle; compilation passed. Direct JUnit: **120 tests, 8 failures**, exit 1. Failures were leave ownership (2 vs 1), malformed payable hours (9 vs 0), duplicate presence (ABNORMAL vs LEFT), premature missing checkout, overnight break (8 vs 7.5), two dashboard state cases, and outside-week monthly calculation (7.67 vs 7.17 because the break was not deducted).
3. Before the rules edit, authenticated demo-emulator tests: **8 reported tests, 3 passed, 5 failed**, exit 1. Omitted/zero/positive passed; negative/fraction/string/null incorrectly returned 200 on creation instead of 403. The fifth reported failure is the parent test.

The outside-week regression proves calculation/state behavior with a supplied full schedule set; its original RED specifically caught break anchoring. The all-schedules query and no-clear-on-week-selection wiring were inspected and compiled, not exercised against a live Android Firebase listener. Explicit clock-boundary and additional state assertions were added after the initial behavior RED. The Compose automatic-hours binding was inspected/compiled without an instrumented RED/GREEN UI test.

## Final verification

All commands run from the worktree unless otherwise noted.

### Cached Android assembly and compilation — PASS

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

Final result: **BUILD SUCCESSFUL in 1m 28s**, exit 0; **39 actionable tasks: 7 executed, 32 up-to-date**. Production Kotlin, unit-test Kotlin, runtime classes and APK packaging executed. Existing `android.overridePathCheck=true` warning remains. An earlier full Kotlin compilation also emitted existing deprecated FactCheck icon warnings.

The first sandboxed compilation attempt failed to load `native-platform.dll`. Approved access to the existing SDK/Gradle caches allowed compilation. `gradle/wrapper/gradle-wrapper.jar` is still absent (confirmed by `Test-Path`, False); wrapper commands and the previously problematic Gradle test-worker launcher were not rerun or claimed as passing in this wave. No build configuration or wrapper artifacts were changed.

APK: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 **`2E40179A206400C7ECE241794DFDB0E1B3B18A190D84C388F1ACB2C71C3E1EEA`**. This is an incremental cached build, not a clean build. APK was not installed or committed.

### Direct JUnit — PASS

```powershell
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

Final result: **JUnit 4.13.2, OK (125 tests)**, exit 0, 0.345 seconds. All discovered compiled tests run, including 16 new tests across FinalReviewRulesTest and FinalReviewStateTest (baseline 109). Intermediate GREEN was 122 tests before the last three state assertions were added.

The existing runner uses real compiled classes and machine-cached Android/Firebase/Compose/JUnit dependencies from its recorded worker classpath. It is a local fallback, not a portable replacement for the Gradle unit-test task. No Android/Compose instrumentation or live ViewModel/Firebase subscription test was run.

### Cloud Functions Node tests — PASS

```powershell
# Working directory: firebase/functions
npm test
```

Final result: **16 passed, 0 failed/cancelled/skipped/todo**, exit 0. Available runtime: **Node v24.21.0**; package target is Node 22, so exact deployed-runtime parity was not verified. Tests include the real endpoint/mapping path with controlled transaction IO; deployed transactions, triggers and notification delivery were not exercised.

### Authenticated local Firestore rules — PASS

Started the cached emulator directly with a relative rules path (avoids the previously recorded Unicode absolute-path CLI issue):

```powershell
& 'C:/Users/DELL/.jdks/jbr-21.0.11/bin/java.exe' '-Duser.language=en' -jar 'C:/Users/DELL/.cache/firebase/emulators/cloud-firestore-emulator-v1.22.0.jar' --host 127.0.0.1 --port 8085 --project_id demo-attendance-final-fix --rules firebase/firestore.rules --single_project_mode true
node --test firebase/test/shiftRules.test.js
```

Restarted the emulator after the rules edit to load the final rules. Final result: **8 reported tests passed, 0 failed/cancelled/skipped/todo**, exit 0 (one parent plus seven data cases). Each case checks authenticated Admin create and update: omitted/0/60 return 200; -1/1.5/string/null return 403. Test-only Admin seeding and writes used the fixed localhost demo endpoint; no configured production project was contacted. Emulator logs corroborated denied invalid creates/updates at rule L169. Emulator stopped with Ctrl+C after verification (exit 1 due to interruption, not a test failure).

Coverage is limited to the changed shift-grace contract; this is not a full authorization/rules suite.

### Static checks and inventory — PASS

- `git diff --check` and `git diff --cached --check`: exit 0 before the implementation commit. Only LF/CRLF informational notices.
- `node --check` on index.js, attendanceResolver.js, attendanceNotification.js, employeeMapping.test.js and shiftRules.test.js: no syntax errors.
- `firebase/firestore.indexes.json` parsed with PowerShell `ConvertFrom-Json`.
- Requested noon-heuristic scan found only existing firmware UTF-8 operations at lines 68 (`< 128`) and 124 (12-bit shift); neither classifies attendance. Firmware unchanged.
- Domain dependency scan: only pre-existing RequestRules Timestamp import remains. No Firebase repository/client imports in the attendance calculation changes.
- README inventory checked against every tracked changed path plus new source/test files: all present. README records all 19 implementation-commit files plus this report and explains changed behavior and local rules testing.

## Remaining limitations

- All-schedule Admin listening trades query simplicity/correct period context for more reads and memory. Existing attendance/adjustment history limits still apply; this wave does not backfill history or remove those limits. Saved payroll documents remain unchanged.
- A user's manual payroll hours override intentionally stays fixed while automatic context changes.
- No device/Compose instrumentation, deployed Firebase integration, exact Node 22 run, firmware compilation or hardware validation. No changes to those environments were attempted.
- No deployment, production mutations, merge, push, flash or installation. Only bounded implementation/documentation commits and local verification were performed.
