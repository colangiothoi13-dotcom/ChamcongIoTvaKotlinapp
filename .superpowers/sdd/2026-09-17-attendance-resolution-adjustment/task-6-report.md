# Task 6 report: resolved attendance calculation integration

## Status and commit

Completed the bounded Task 6 implementation in the supplied isolated worktree, starting at `59a677a07280696f0afe97892ff8405eb3e4eafe`.

Implementation commit: `c4e62157aadae96f0eaa42f73f1e7561c072d053` — `feat: calculate work from resolved attendance pairs`.

Production and test Kotlin compile successfully. Direct focused JUnit: **29 passed, 0 failed**. Full direct JUnit: **95 passed, 0 failed**. Cloud Function tests: **15 passed, 0 failed**. The Android wrapper test command remains unavailable; it is not reported as passing.

## Changed files and behavior

- `app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt`: exposed the existing schedule-date membership predicate internally so consumers use the same date/window rules. Applied an adjustment's check-in before selecting an accepted checkout, allowing an orphaned raw checkout to pair with a corrected check-in. This small resolver extension was necessary for the integration regression; no Firebase I/O was added.
- `app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt`: replaced raw latest-scan classification and the 12-hour heuristic with the accepted effective pair and `isMissingCheckOut`. Retained approved-leave priority, schedule/shift forwarding and public defaults. Scheduled overnight scans use the start date and shift-end grace. Duplicate checkouts cannot mark an employee left. Pending, unscheduled, out-of-order and malformed/unverified states are abnormal. Without shift context, the shared resolver's next-local-day fallback applies.
- `app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt`: daily/monthly summaries now resolve accepted pairs by schedule date/window, overlay the latest employee/date adjustment and pass the schedule date into work-time calculations. Missing/abnormal states do not manufacture paired hours. Existing schedule hours overrides remain supported, with latest adjustment hours taking precedence.
- `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt`: uses employee day summaries for effective timestamps, hours and status. Explicit schedule dates take priority. Legacy scans are assigned to a matching known schedule before calendar-date fallback, preventing an extra report row on an overnight checkout day. Approved leave is scoped to its actual employee rather than being applied to every selected employee.
- `app/src/main/java/vn/chamcong/iot/model/PersonnelRules.kt`: monthly payroll hours sum the same employee monthly summaries. Added trailing defaulted schedules/shifts/adjustments arguments; existing calls and legacy rows remain readable.
- `app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt`: both payroll hour calculations receive schedules, shifts and attendance adjustments. Existing editable payroll hours and saved payroll snapshots retain their existing behavior.
- `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeAttendanceScreen.kt`: keeps abnormal days visible even when unresolved scans provide neither timestamps nor worked hours.
- Tests changed: `PresenceRulesTest.kt`, `ReportRulesTest.kt`, `EmployeeRulesTest.kt`, `PayrollRulesTest.kt`, and `AttendanceAdjustmentRulesTest.kt`. The last file updates the prior same-day 12-hour assertion to the shared fallback required by Task 6.
- Task artifacts: this report and `run-task6-tests.ps1`, which reproduces direct JUnit using the existing cached dependency classpath and relative compiled application/test paths.

Task 5's adjustment subscriptions, MainViewModel forwarding, scheduling integration, append-only data and raw attendance persistence were retained. No changes to repository persistence, Firebase rules, Cloud Functions or production data were needed. The deferred mapped-employee canonical-id observation remains outside this task.

## TDD and verification evidence

1. Read the supplied Task 6 brief first. Initial HEAD matched the requested base; tracked files were clean and prior `.superpowers/` artifacts were already untracked.
2. Added six regressions before production edits. Cached compilation succeeded and direct focused JUnit produced **24 tests, 6 failures**: duplicate checkout marked presence left; overnight presence expired early; overnight report split dates; payroll lost month-end overnight hours; rejected checkout added payable hours; employee summary lost overnight hours.
3. After the calculation migration, those **24 tests passed**.
4. Added adjustment and leave integration coverage. Direct JUnit produced **27 tests, 1 failure**: corrected check-in plus raw checkout produced zero rather than eight hours. Updated the shared resolver only after observing this failure.
5. Added boundary coverage for custom daytime grace, exact overnight deadline, next-day legacy checkout, rejected statuses across all hour consumers, latest adjustment checkout/hours, other-employee isolation, hours-only adjustment without scans and raw-row immutability. Existing approved-leave and adjustment tests were retained.
6. Final review added a legacy overnight report regression. Direct JUnit produced **29 tests, 1 failure**: expected only September 30, received September 30 and October 1. Fixed the report's legacy schedule grouping, then recompiled.
7. Final cached compilation: **BUILD SUCCESSFUL**, exit 0. Final focused direct JUnit: **OK (29 tests)**, exit 0. Final full direct JUnit: **OK (95 tests)**, exit 0. The full run includes the existing resolver, adjustment, scheduling, model and other domain suites.
8. `node --test` from `firebase/functions`: **15/15 passed**, exit 0. No deployment or cloud calls were made.
9. `git diff --check` passed before committing. Git emitted line-ending conversion warnings only. Reviewed the production diff and payroll call sites before the implementation commit.

Additional integration assertions were added after the main migration to strengthen coverage; only the explicitly recorded failures above are claimed as observed behavioral RED results. The UI forwarding/filter changes were compiled and reviewed, not exercised with instrumented UI tests.

## Exact commands and environment limitations

Requested wrapper command, attempted before implementation and again during final verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests vn.chamcong.iot.domain.PresenceRulesTest --tests vn.chamcong.iot.domain.ReportRulesTest --tests vn.chamcong.iot.model.PayrollRulesTest --tests vn.chamcong.iot.domain.EmployeeRulesTest
```

Both attempts failed before discovery: `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`; `gradle/wrapper/gradle-wrapper.jar` is absent. No wrapper or build configuration changes were made.

Successful compile command, from the isolated worktree:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

The first sandboxed cached-Gradle attempt could not load `native-platform.dll`; the approved cache-access retry succeeded. Git similarly needed approved access to the parent repository's worktree metadata to create the requested commit.

Successful test commands, from the isolated worktree:

```powershell
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

The runner uses real compiled production/test classes and cached Firebase/Android/Compose/JUnit dependencies, with no new test doubles. It depends on the existing local worker-classpath file documented inside the script; it is a machine-local verification aid, not a portable replacement for Gradle. Relative workspace classpaths follow Task 5's workaround for its observed Windows absolute-path test-worker problem. This task did not rerun the cached Gradle test worker or claim that limitation was fixed.

No device/emulator UI session was run. No Firebase deployment, production reads/writes or rules-emulator validation was performed. Those are outside this calculation-only change. Existing one-pair-per-schedule-date resolver semantics remain authoritative; legacy rows without a schedule or explicit schedule date retain calendar-date fallback.

The report and runner are committed separately so the report can name the exact implementation hash. Pre-existing untracked briefs/reviews/reports are preserved and are not included in the Task 6 commits.

## Fix round 1: verified eligibility and unique legacy assignment

Status: completed both Important findings. Fix commit: `4e0795cedaada2497b1b667661de4bf870f0eadc` — `fix: exclude unverified scans and assign legacy attendance once`.

### Findings and bounded implementation

- Confirmed `resolveAttendancePair` previously accepted `resolutionStatus == ACCEPTED` without checking `verified`. Added `verified == true` to shared raw-pair eligibility. Unverified check-ins and checkouts cannot supply effective raw timestamps or calculated work, even with an explicit schedule date or accepted-looking resolution status. Consumers retain ABNORMAL status for these rows. Valid administrative hours/timestamp adjustments remain independent correction sources; the fix does not discard authorized corrections because an unrelated raw scan is unverified.
- Checked the existing `Attendance` model (`verified: Boolean = true`) and repository deserialization (`toObject(Attendance::class.java)`). Retained the existing legacy model/default contract, with no rule that upgrades an explicit false value based on absent resolution metadata. Existing verified/default legacy tests still pass. This domain-only fix cannot retrospectively establish whether old records missing a verification field were physically verified; it does not change persistence/deserialization or claim to audit historical provenance.
- Confirmed reports previously used the first matching schedule, while employee monthly summaries independently matched each date's window. Extracted `assignAttendanceScheduleDates` into `AttendanceResolutionRules.kt`. It assigns each legacy row to at most one supplied employee schedule using copies, preserving raw input and explicit schedule dates.
- Assignment policy: retain inclusive early/grace windows; within matching windows choose the nearest shift start for CHECK_IN or nearest shift end for CHECK_OUT. Resolve equal distances by chronological shift start, schedule date, shift id, then schedule id. This is independent of input scan/schedule ordering. Unsupported scan types use the start boundary solely for display grouping and remain ineligible for work. Missing/unmatched schedule context retains existing fallback behavior.
- Employee monthly summaries and reports now consume that shared assignment. Payroll already calls monthly summaries. Presence list classification and weekly summaries also use it so their schedule matching does not diverge from the corrected hour consumers. Single-day primitives keep their compatible signatures; callers with multiple candidate schedules must use the shared assignment, as all production multi-schedule callers now do.
- Files changed: `AttendanceResolutionRules.kt`, `EmployeeRules.kt`, `ReportRules.kt`, `PresenceRules.kt`, `SchedulingRules.kt`, and `PayrollRulesTest.kt`. No Firebase repository, rules, Cloud Function, firmware, UI or production-data changes.

### Regression evidence and verification

Used the review and TDD workflows: inspected both reported paths, then wrote regressions before production edits.

- RED after successful cached test compilation: **31 focused tests, 2 failures**. Accepted-looking unverified input returned **9.0 hours instead of 0.0**. Overlapping legacy windows returned report hours **[0.5, 0.0] instead of [0.0, 0.0]**.
- Unverified coverage exercises false/true, true/false and false/false check-in/checkout combinations, both with explicit schedule dates and without resolution-date metadata. It asserts zero worked hours in employee day summaries, reports and monthly payroll, ABNORMAL statuses, and exclusion of unverified effective timestamps.
- Assignment coverage exercises 22:00–06:00 on September 30 followed by 06:00–14:00 on October 1. A 06:00 check-in and 06:30 checkout in overlapping windows are assigned to different intended shift boundaries and do not create or duplicate a half-hour pair. Complete adjacent shifts with a checkout and separate check-in exactly at 06:00 each produce eight hours on their own start date/month. Both cases run with reversed schedule and scan order, assert report/monthly-summary/payroll results, and verify original raw objects remain unchanged.
- GREEN: cached production/test Kotlin compilation **BUILD SUCCESSFUL**, exit 0. Direct focused JUnit **OK (31 tests)**, exit 0. Full direct JUnit **OK (97 tests)**, exit 0. Existing adjustment, resolver, scheduling, presence and legacy compatibility tests remain green.
- Commands were the same cached offline compile and `run-task6-tests.ps1` / `run-task6-tests.ps1 -All` documented above. This round did not rerun the known-broken wrapper or Cloud Function suite; no Cloud code changed. The missing wrapper JAR, local dependency-classpath requirement and absence of instrumented UI verification remain as documented.
- `git diff --check` passed before the fix commit, with only Git line-ending notices. Reviewed all production resolver/day-summary call sites to confirm shared assignment occurs before multi-schedule aggregation. Full schedule context is required to disambiguate legacy rows; explicit server-assigned dates always take priority.

The fix is committed; this evidence is appended in a separate report commit to record its exact hash. Pre-existing untracked Firebase CLI config remains untouched.
