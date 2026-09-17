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
