# Task 7 implementation report

Implemented in the supplied isolated worktree on `feat/attendance-resolution-adjustment`, starting at Task 6 base `22721d5`. No subagents were used.

Implementation commit: `005a15436ed4b9918a93a80a49717b448c0fa2a3` — `feat: add audited attendance adjustment dialog`.

## Delivered behavior

- Added the requested `AttendanceAdjustmentTarget` and `AttendanceAdjustmentDialog(target, state, onDismiss, onSubmit)` interfaces. The scrollable Material 3 dialog shows employee identity, schedule date, current effective check-in/check-out and worked hours. It provides optional time/hour inputs and a required reason, with Vietnamese guidance and inline validation/repository errors.
- Parsing is a small Firebase-free function using `java.time.Instant`, strict `uuuu-MM-dd HH:mm` parsing (the user-facing format is `yyyy-MM-dd HH:mm`), and fixed `Asia/Ho_Chi_Minh`. It rejects impossible dates, malformed formats, invalid or equal/reversed time pairs, blank reasons, all-blank values, unchanged values, and non-finite/out-of-range worked hours. Zero is valid; the permitted hours range remains 0–24. A single edited endpoint is checked against the current opposite endpoint. Overnight times require the explicit following date.
- Added an Admin-only `Điều chỉnh` action to history rows. Rows without employee identity or with malformed explicit schedule dates cannot open a correction. Valid explicit `scheduleDate` wins over event time; legacy rows without one fall back to their Vietnam calendar date. An explicit malformed date is not silently replaced.
- Replaced the shared history row's unconditional green success icon with resolution-aware presentation. `ACCEPTED`, `SCAN/PENDING`, `DUPLICATE`, `UNSCHEDULED`, and `OUT_OF_ORDER` have distinct text labels. Accepted rows use a green check, pending rows a tertiary-colored warning, and other unresolved/abnormal rows an error-colored warning. Unverified rows, unknown types/resolution statuses, invalid dates, and accepted rows with unknown attendance statuses display `ABNORMAL`. Event type remains visible, and row time formatting now uses the same fixed Vietnam zone.
- Submission goes exclusively through the existing `MainViewModel.adjustAttendance(adjustment, done)`. The UI supplies domain Instants and no actor identity; the existing repository supplies the authenticated actor, maps Firestore timestamps, and writes the append-only adjustment/audit batch. No repository, ViewModel, domain model, rules, or server changes were necessary.
- `state.saving` disables inputs, submit, cancel, outside/back dismissal, and row actions. The ViewModel's existing saving guard also prevents duplicate writes before recomposition. Local validation does not submit. Repository failure leaves the dialog and entered text in place and displays `state.error`; only the success callback closes it after submission. Explicit cancellation is available while idle. Opening a correction clears old repository errors using the existing method.
- The screen uses the existing adjustment listener and derived summaries; there is no UI-side data fetch or manual report/presence/payroll refresh.

## Compatible extension and effective-value semantics

The existing `AttendanceScreen` delegated to `AttendanceList`, and the shared row had no callback. The smallest compatible extension was an optional `(Attendance) -> Unit` callback plus an enabled flag on the shared list/row. Defaults preserve existing callers, including an older dashboard row call. Navigation remains unchanged; only the attendance screen supplies the Admin-gated callback.

A raw row has one scan timestamp, not a complete effective pair or worked-hours total. `attendanceAdjustmentTarget` derives those from the full loaded `state.attendance`, schedules, shifts, and adjustments via the existing schedule assignment and `employeeDaySummary` functions. It does not use the filtered visible subset or assume that a clicked duplicate/pending scan is an accepted endpoint. Employee-name fallback uses loaded employee data, then the identifier.

The parser returns null for optional blank inputs. When constructing the adjustment, the dialog carries current effective endpoints forward for blank timestamp fields, because the domain consumes the latest adjustment rather than merging all historical corrections. A blank hours field preserves the latest valid adjustment's explicit hours override, if any; calculated hours are not converted into a new hours override. This lets edited timestamps recalculate hours when no earlier explicit hours override exists. Blank inputs are not a clearing operation. Previously overridden hours can be replaced with an explicit number. The dialog explains the preservation behavior.

`AuditScreen.kt` was inspected and left unchanged: it already renders the action, actor, target, details, reason, and timestamp, which cover `ATTENDANCE_ADJUST` and the existing before/after details.

## Changed files

All paths below are relative to the isolated worktree.

| File | Change |
| --- | --- |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentDialog.kt` | New target interface and dialog, validation/error/saving UI, domain adjustment construction. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentInput.kt` | New pure time/hour/reason parser and validation. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceRowPresentation.kt` | New testable resolution labels and schedule-date fallback. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt` | Dialog selection, Admin callback and ViewModel wiring, effective target derivation. |
| `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt` | Optional shared row/list callback, resolution rendering, fixed-zone display. |
| `app/src/test/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentInputTest.kt` | Six focused parser tests. |
| `app/src/test/java/vn/chamcong/iot/ui/attendance/AttendanceRowPresentationTest.kt` | Four resolution/date/effective-target tests. |

This report is recorded separately so it can identify the exact implementation commit.

## Verification commands and results

Commands were run from:

```text
D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment
```

1. Requested wrapper build:

```powershell
.\gradlew.bat :app:assembleDebug
```

Failed before Gradle started: `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`. The repository lacks `gradle/wrapper/gradle-wrapper.jar`. No wrapper or build configuration files were changed.

2. Initial cached compilation after writing parser tests:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

The sandboxed attempt could not load `native-platform.dll`. The approved cache-access retry ran successfully to test compilation, where the new tests failed with unresolved `parseAttendanceAdjustmentInput`, confirming the parser had not been implemented. This was a compile-time missing-function failure, not a runtime assertion-red run. The remaining presentation/target coverage was added during implementation; no claim is made that every new test had a separate assertion-red run.

3. Final cached build and test compilation:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

**BUILD SUCCESSFUL**, exit 0, 39 actionable tasks (11 executed, 28 up-to-date). The initial UI compile caught a legacy direct `AttendanceRow` caller missing the new arguments; optional defaults fixed that compatibility issue before the successful final build. The debug APK exists at `app/build/outputs/apk/debug/app-debug.apk`. Existing `FactCheck` icon deprecation warnings and the existing experimental `android.overridePathCheck` warning remain. An earlier packaging pass reported that `libandroidx.graphics.path.so` would be packaged without stripping; final assembly succeeded.

4. Real compiled JVM tests using the existing Task 6 direct runner:

```powershell
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

**OK (107 tests)**, exit 0, including all 10 new Task 7 tests and 97 existing tests. New coverage includes strict Vietnam-zone/overnight parsing, whitespace and blank optional fields, zero hours, invalid dates/formats/numbers, blank reason, no changes, reversed/equal endpoints, validation against an unchanged endpoint, each resolution label, malformed/unverified rows, explicit schedule date versus local fallback, and effective current values from the latest correction when opening a duplicate row.

The direct runner uses real production/test classes and cached Android/Firebase/Compose/JUnit dependencies. It reads the existing machine-local worker-classpath file documented in the Task 6 runner; this is not a portable Gradle replacement. The known Windows Gradle test-worker path limitation was not re-investigated or claimed fixed.

5. `git diff --check` and `git diff --cached --check` passed. Git emitted only line-ending notices. Final diff review checked all shared row/list callers and the existing repository audit path. Creating the requested commit needed approved access to the parent repository's worktree index metadata after the initial sandboxed `index.lock` write was denied.

## Limitations and scope

- No Compose instrumented tests are configured in the existing app, and no emulator/device interaction or visual runtime inspection was performed. Build verification confirms Kotlin/Compose compilation and APK assembly. Saving/error/dismissal behavior was reviewed in code; it was not exercised against a running Firebase session.
- Current values use the already-loaded state snapshot. They do not fetch additional history and cannot recover scans outside that loaded set. The selection is a snapshot taken when the dialog opens; concurrent administrator corrections remain subject to the existing append-only/latest-adjustment contract.
- Legacy rows without explicit dates use the requested calendar-date fallback for the correction target. Shared domain assignment still determines which available scans contribute to its current summary; explicit server schedule dates remain preferable for overnight work.
- No Firebase I/O was added to UI. No deployment, production-data reads/writes, security-rule changes, Cloud Function changes, or firmware changes were performed.
- The pre-existing untracked `.superpowers/firebase-cli-config/` directory was preserved and excluded from commits. Generated APK/build outputs were not staged.

## Fix round 1: server resolution event types

Completed in commit `7e6d890b94b65ea2801add80ae4fcf17db4b1464` — `fix: recognize server attendance resolution event types`.

- Verified `firebase/functions/attendanceResolver.js` and `firebase/functions/index.js`: legitimate rejected/resolved events can have matching `type` and `resolutionStatus` values of `DUPLICATE`, `UNSCHEDULED`, or `OUT_OF_ORDER`, with `status = ABNORMAL`. The UI's earlier type allowlist rejected these before reaching the dedicated labels.
- Updated only `AttendanceRowPresentation.kt` and `AttendanceRowPresentationTest.kt` in the fix commit. The allowlist now recognizes those three event types, and their dedicated label branches recognize either type or resolution status. They remain non-accepted presentations. Existing pending handling and the earlier unverified, invalid-date, unknown-type, and unknown-resolution guards remain in place.
- Added production-shaped fixtures with matching type/resolution status and `status = ABNORMAL`, asserting each dedicated label and `accepted == false`. Added coverage for unverified, malformed-date, unknown-type, and unknown-resolution variants of all three types, asserting `ABNORMAL` and non-accepted presentation. Existing accepted, pending, legacy date, target derivation, and parser tests remain covered in the focused run.
- RED: before the production fix, direct JUnit ran 12 focused tests with one assertion failure: expected a `DUPLICATE` label but received `ABNORMAL` / invalid type. The initial red fixture used matching type/resolution status; final fixtures additionally set the server's `status = ABNORMAL`.
- GREEN: cached offline production/test Kotlin compilation completed with **BUILD SUCCESSFUL**, exit 0 (19 actionable tasks; 4 executed, 15 up-to-date). Direct focused JUnit completed with **OK (12 tests)**, exit 0. `git diff --check` and staged whitespace checks passed; only Git line-ending notices were emitted.

Compile command, from the same isolated worktree:

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

Direct focused test command (same cached dependencies as the Task 6 runner):

```powershell
$worker = Get-Content -Encoding UTF8 'C:/Users/DELL/.gradle/.tmp/gradle-worker-classpath16396057681371034652txt'
$dependencies = $worker[1].Trim('"').Replace('\\','\').Split(';') | Where-Object { $_ -match '^C:' -and $_ -match 'junit-4|hamcrest|kotlin-stdlib|firebase-common|firebase-firestore|play-services-basement|ui-graphics|ui-unit|ui-geometry|runtime-android|ui-util' }
$classpath = (@('app/build/tmp/kotlin-classes/debugUnitTest', 'app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar', 'C:/Users/DELL/AppData/Local/Android/Sdk/platforms/android-35/android.jar') + $dependencies) -join ';'
& java -cp $classpath org.junit.runner.JUnitCore vn.chamcong.iot.ui.attendance.AttendanceRowPresentationTest vn.chamcong.iot.ui.attendance.AttendanceAdjustmentInputTest
```

This round did not rerun APK assembly, the full JVM suite, or device/Compose instrumentation. The missing wrapper JAR and machine-local cached classpath limitations remain as documented above. No server code, unrelated UI, production data, or deployment was changed. The pre-existing untracked Firebase CLI config was preserved. This evidence is committed separately to record the exact fix hash.
