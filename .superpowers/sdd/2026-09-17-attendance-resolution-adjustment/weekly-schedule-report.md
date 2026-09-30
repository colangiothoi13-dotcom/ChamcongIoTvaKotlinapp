# Weekly employee scheduling implementation report

Date: 2026-09-17. Branch: `feat/attendance-resolution-adjustment`.
Starting HEAD: `aa3dabda469eff88de860bcccaf88873f10811ba`.
Worktree: `D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment`.
Requested commit message: `feat: add weekly bulk employee scheduling`.

## Scope and implementation

Implemented the approved bounded change in the existing management flow: Quản lý ca làm → Lịch → Phân cho nhân viên. No separate destination, schema extension, backend change, deployment, push or merge. No subagents used.

Before editing, inspected ScheduleScreen, ShiftsScreen, ShiftManagementScreen, MainViewModel, FirebaseRepository, SchedulingModels, SchedulingRules and SchedulingRulesTest, together with the prior verification runner/report and relevant Firestore rules. The starting worktree had an unrelated untracked `.superpowers/firebase-cli-config/` directory; it is preserved and excluded from this commit.

The existing Monday-normalization/week helper is reused. The dialog selects multiple active employees and any subset of the seven dates, using labelled checkbox rows with checkbox semantics and 48dp minimum height. Week navigation and action rows now scroll horizontally; the schedule screen scrolls vertically. Selection state resets when the week changes. Switching supplemental templates or reopening the dialog requires entering supplemental times again.

The dialog offers three pure templates:

- Ca sáng: 08:00–12:00, category MORNING.
- Ca chiều: 13:00–17:00, existing category EVENING for compatibility.
- Ca bổ sung/tăng ca: no fixed hours; strict HH:mm validation, distinct start/end, overnight end supported and explained in the UI. Uses SUPPLEMENTARY and countsOvertime=true. Existing payroll/overtime calculations are unchanged.

The UI displays assignment count, replacement semantics, validation messages, save progress, and repository errors. Save is disabled for invalid/empty selections or while saving; dismiss is disabled during saving. The existing ViewModel perform path provides duplicate-submit guarding and success/error state. It revalidates employee selection against the latest loaded active employee list before generating the payload.

## Deadline and coverage interpretation

The deadline is 17:00 on the Sunday **before** the selected Monday, in Asia/Ho_Chi_Minh. For the week starting 2026-09-21, this is 2026-09-20T10:00:00Z. At the exact deadline, an incomplete week becomes overdue. This interpretation was stated during implementation and is explicit in README and UI.

Coverage means each active employee has at least one non-empty shift assignment in the selected week. The schema has no planned-rest-day or required-workday model, so this does not claim every required day has been filled or mandate seven working days. The UI explicitly asks Admin to check required dates and says blank days are not approved leave. It reports the count of employees without any weekly assignment. The warning refreshes once per second while composed and when schedule/employee/week state changes. There is no deadline gate on saving or editing.

## Persistence and compatibility

No Firebase I/O was added to domain code. `WeeklyScheduling.kt` contains templates, warning state derivation and employee/date Cartesian-product payload validation only. Models are unchanged.

Templates are persisted only on the explicit assignment save action. Stable IDs include template version, key, start and end. A Firestore transaction reads the template document, creates it only if absent, and checks equality if present. Concurrent identical saves therefore share one template ID. Different supplemental times create separate reusable snapshots. EffectiveFrom is 1970-01-01 so selected past and future weeks work without mutating shared effective dates.

Legacy shift documents are neither seeded over, renamed, merged nor removed. Existing duplicate legacy equivalents are retained deliberately. The shift editor offers “Tạo bản tùy chỉnh” for reserved weekly template IDs, creating a new ordinary shift instead of modifying the saved snapshot. If an older client modifies a reserved template, weekly save fails visibly rather than silently overwriting it. This is an app-level convention; Firestore rules were not changed to enforce immutability across old clients.

Bulk assignments use the existing canonical `${employeeId}_${date}` document ID. Each employee/date still has one schedule; assigning again replaces shift fields, not adds a second shift. Merge writes preserve existing workedHoursOverride, adjustmentNote and note fields. This uses the same canonical-ID convention as the existing single-cell and department writers; it is not a migration/deduplication of arbitrary externally created noncanonical schedule IDs.

The new writer touches only shifts, workSchedules and audit_logs. It never writes attendance, attendanceAdjustments, sessions, or saved payroll. Reassigning schedules can affect existing derived schedule-based calculations, as with the previous assignment flow; stored attendance history remains intact.

Writes are grouped into transactions of at most 400 schedules plus template and audit. Audit serialization reuses the existing server-timestamp helper and correct audit_logs collection. Chunks are atomic individually; an entire large selection is not atomic. Failure displays acknowledged saved/total counts and permits retry. Retrying reuses schedule/template IDs; it may create another audit entry for repeated successful assignments. An ambiguous network acknowledgement may under-report committed records, but retry still updates the same IDs. Concurrent Admin edits follow last-write-wins behavior.

Department assignment, older single-cell/month-cell assignment, and copy-previous-week callers remain intact. No attendance adjustment controls were removed from their legacy dialog.

## TDD and verification evidence

Added seven tests in `app/src/test/java/vn/chamcong/iot/domain/WeeklySchedulingTest.kt` before any production edits. They cover:

1. Monday–Sunday boundaries crossing the year, including Sunday and next Monday.
2. Sunday deadline before, exactly at, and after 17:00 Vietnam time.
3. Active employee coverage, out-of-week exclusion, and complete-week non-overdue behavior.
4. Required default shift times, supplemental absence of fixed times, and stable/distinct IDs.
5. Supplemental empty, malformed, out-of-range and equal times; valid overnight hours.
6. Exact selected employee/date product, canonical IDs, department/name/actor payload metadata.
7. Empty, stale, inactive and out-of-week selection rejection before persistence.

### RED attempt

Command, before production changes:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:compileDebugUnitTestKotlin --offline --no-daemon
```

The sandbox attempt failed before compilation because native-platform.dll could not load. Repeating with approved cache access reached the compiler and failed in 21s, exit 1, on missing weeklyScheduleStatus/defaultShiftTemplates/weeklyAssignmentPayload, with cascading inference errors. This was a missing-API compilation RED, **not an executed failing JUnit assertion**. The already-existing week boundary helper was not expected to fail. No stronger RED claim is made.

### GREEN compilation and APK

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

Initial implementation build: BUILD SUCCESSFUL in 1m 6s; 39 tasks, 9 executed, 30 up-to-date. Review then corrected audit collection/server timestamp serialization, made schedule controls scrollable, and protected template editing.

Final build after those changes: **BUILD SUCCESSFUL in 57s**, exit 0; **39 actionable tasks: 7 executed, 32 up-to-date**. Existing experimental android.overridePathCheck warning remains. Uses cached Gradle 8.9 and SDK, with approved cache access. This is an incremental offline build, not a clean build. The wrapper JAR remains absent, so the installed cached distribution was invoked directly.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `B7D9A147E3F44ED15A492FA657CEF3C513048B4D401561207504414249796C4C`.
APK was not installed or committed.

### Direct JUnit

```powershell
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

Initial GREEN: **OK (132 tests)**, exit 0, 0.342s.
Final GREEN after the final build: **OK (132 tests)**, exit 0, 0.311s; JUnit 4.13.2. Baseline was 125 tests. No failed/skipped tests reported. Runner uses actual compiled production/test classes and cached dependencies. Gradle's test-worker task itself was not run; this is the existing machine-specific direct-JUnit fallback, not a claim of portable Gradle test execution.

### Review and static checks

`git diff --check` passed with only LF/CRLF informational notices. Reviewed the transaction against existing shift/schedule/audit rules, including server timestamp requirements. Scanning the new domain file found no Firebase references. README includes all ten changed/new files in this wave, including this report. No npm tests were run because no JavaScript, Functions, rules or Node configuration changed.

## Remaining limits and handoff

- No Compose instrumentation, TalkBack/device interaction, screenshot/layout validation, authenticated emulator transaction test, or live Firebase end-to-end save was performed. Accessibility is implemented through Compose checkbox semantics and labels, but not verified with a screen reader.
- No automated repository transaction/concurrency/failure injection test; template deduplication, merge preservation and chunk behavior were reviewed in source and compiled. Pure tests establish payload and state rules, not remote persistence guarantees.
- Existing async employee/schedule loading can transiently affect warning counts. The warning uses the client clock and is not a server-enforced deadline or background notification.
- Large employee sets are rendered in the existing scroll-based UI; no paging/search was introduced.
- No merge, push, deployment, production writes, APK install, firmware change or hardware verification.

The implementation, tests, README and this report are included together in the requested implementation commit. The final response provides that commit ID and the SHA-256 of this report; the report intentionally does not embed its own hash or containing commit hash.

## Reviewer fix round 1 — 2026-09-17

Base: `1a131b4c6e731b4e7bb2726aaaf62f77ff73551f`.
Fix commit: **`a6b6c1bb18fd7e743baf9b33d91f71ba77c4ce7a`**, `fix: address weekly scheduling review findings`.
This appended evidence is committed separately so it can record the exact fix hash.

### Bounded corrections

1. Verified that inactive/missing selected employees disappeared from the selectable rows but their IDs remained in selection, causing payload validation to fail without a displayed reason. Added pure `unavailableWeeklyEmployeeIds`, a visible count/explanation, and an explicit “Bỏ nhân viên không còn khả dụng” recovery action. The action removes only unavailable IDs. The dialog also displays the actual payload validation error, so removing all stale selections shows “Chọn ít nhất một nhân viên” and missing dates show the existing date-selection guidance. Existing server/save-time validation remains intact. No automatic dropping during composition or saving was introduced.
2. Added pure `scheduleShiftLabel`, used both by every legacy picker menu item and the selected-value button. Supplemental entries now include start/end, e.g. `Ca bổ sung • 18:00–20:00` and `Ca bổ sung • 22:00–06:00 (ngày hôm sau)`. Category-based handling covers legacy names and new template names; ordinary shifts retain their existing name. Stored shifts and schedules are unchanged by this display fix.
3. Bulk audit targetType remains `workSchedule`; targetId now uses `scheduleDocumentId(chunk.first().employeeId, chunk.first().date)`, exactly the same canonical-ID helper used by the schedule writes. Chunks are nonempty. The existing details string retains every affected schedule ID. Actor, SHIFT_UPDATE action, audit_logs collection, transaction atomicity and server timestamp serialization are unchanged. Relevant existing audit rules were inspected; no rules or schema changes were needed.

### Tests and exact outcomes

Four new pure tests in `WeeklySchedulingPresentationTest` cover mixed active/inactive/missing selections, recovery to a valid payload, all-unavailable recovery with an explicit empty-selection validation message, supplemental labels with different times and overnight ends, and unchanged ordinary-shift labels.

Before production edits, ran the same cached Gradle command shown above with `:app:compileDebugUnitTestKotlin --offline --no-daemon`. Result: **BUILD FAILED in 29s**, exit 1, 18 tasks (1 executed, 17 up-to-date), due to unresolved `unavailableWeeklyEmployeeIds` and `scheduleShiftLabel`. This records a missing-helper compilation RED, not a failing executed JUnit assertion.

After implementation, ran:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

Final cached build: **BUILD SUCCESSFUL in 51s**, exit 0; **39 actionable tasks: 9 executed, 30 up-to-date**. Final direct JUnit: **OK (136 tests)**, exit 0, JUnit 4.13.2, 0.333s (132 baseline plus four new tests). Cached SDK/Gradle access was approved. Existing android.overridePathCheck warning remains. This was an incremental cached build; Gradle test-worker execution was not claimed.

APK SHA-256: `2D6862488BD1D7162E5FC68DDE240F0491F58CF6383EB78F5377DBC8BF727C6B` at `app/build/outputs/apk/debug/app-debug.apk`. APK was not installed or committed.

`git diff --check` and `git diff --cached --check` passed before the fix commit (LF/CRLF informational notices only). README inventory covers the six implementation/test/documentation files in the fix commit and this appended report. No npm tests were relevant: no JS, Functions, rules or Node changes.

### Verification limits and status

No Compose/device/TalkBack interaction or live/emulator Firebase transaction test was run. Pure tests exercise the helper results and the real payload validator; they do not execute the dialog. The small audit target correction was source-reviewed against the canonical write and audit rules, not covered by a new remote-writer assertion; no existing repository transaction test harness was available. No scope expansion, subagents, merge, push, deployment or production writes. The unrelated untracked `.superpowers/firebase-cli-config/` directory remains untouched; all fix implementation files are committed.
