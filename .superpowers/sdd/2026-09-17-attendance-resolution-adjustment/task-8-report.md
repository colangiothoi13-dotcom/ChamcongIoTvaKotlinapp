# Task 8: documentation, final verification and handoff

Status: completed with verification limitations. The requested wrapper commands did not pass; cached Android assembly and direct JUnit did pass. Arduino compilation remains unavailable. Firestore rules loaded through a direct emulator fallback, with one unauthenticated denial smoke check; authenticated rules behavior and Cloud Function integration were not exercised.

## Scope and commits

- Started from Task 7 report commit `7ab8c0f` in the supplied isolated worktree on `feat/attendance-resolution-adjustment`.
- Read `task-8-brief.md` first. Used the verification-before-completion workflow and fresh command results; no subagents.
- Documentation/verification-instructions commit: **`728784db4c90eeaa42758e1eeab5f9ed2250743a`**, `docs: document schedule-based attendance workflow`.
- This report is committed separately so it can name the exact documentation commit without a self-referential hash. Obtain the report commit with `git log -1 --format=%H -- .superpowers/sdd/2026-09-17-attendance-resolution-adjustment/task-8-report.md`.
- Changed only `README.md` and this report. No application, functions, tests, firmware, rules, build configuration or production data were modified in Task 8. Existing untracked `.superpowers/firebase-cli-config/` was preserved and excluded. Generated build outputs/debug logs were not staged.

All commands below were run from this worktree unless another directory is specified:

```text
D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment
```

## README delivered

- Documented raw `SCAN`, `PENDING` resolution/status, NTP UTC timestamp and in-place Cloud Function resolution on the original event document. Distinguished immutable scan identity/time from mutable server resolution fields and described client update/delete restrictions.
- Documented mapping/employee validation, local current/previous-day schedule lookup, `Asia/Ho_Chi_Minh`, early/grace windows, session state and schedule selection.
- Explained `scheduleDate` as the shift-start date, with a 22:00–06:00 overnight example and next-day end when end time is less than or equal to start time.
- Specified the inclusive 3-minute/180,000-ms duplicate window against the last accepted event in the selected session, out-of-order and unscheduled outcomes, and the absence of a noon-based attendance decision.
- Documented missing checkout only strictly after shift end plus grace, default 60 minutes for legacy missing fields, explicit zero support, the no-shift calendar fallback and no automatic invented checkout/hours.
- Documented Admin correction UI, full local date/time for overnight entries, 0–24 hours, required nonblank reason, strict endpoint order, blank-input preservation, and latest valid adjustment semantics.
- Documented append-only `attendanceAdjustments` plus same-ID atomic `audit_logs` entry with `ATTENDANCE_ADJUST`, authenticated actor, reason, timestamp and previous/new adjustment values. Clarified that audit details are adjustment values, not a snapshot of all underlying scans.
- Covered effective pairing/report/presence/employee/payroll use, loaded-history limits and the fact that existing saved payroll documents are not automatically rewritten.
- Corrected obsolete claims that Cloud Functions are unnecessary. Kept runtime guidance grounded in the checked-in package (Node 22) and existing secret-bound HTTPS exports. Removed the hard-coded production deploy command from this section and provided local verification commands.
- Described the notification helper exactly as implemented: valid object/string status fields, previous status other than `ACCEPTED`, next status `ACCEPTED`; it does not itself require previous `PENDING` or validate attendance type. Earlier report/commit titles are not evidence of stronger guards in the current source.
- Added a file-by-file table inside the existing change-log section. A PowerShell comparison of `git diff --name-only 2c5c2f6..HEAD` with that subsection found **all 40 pre-Task-8 changed tracked paths present** (36 source/test/rules/index/functions/firmware files plus README and three prior verification/report files). The table additionally includes this report. It distinguishes newly created versus modified files and does not claim `AuditModels.kt` or `AuditScreen.kt` changed in this feature.
- No functions-specific README existed or was introduced; functions documentation remains in the root README.

## Android verification

### Requested wrapper commands: blocked, exit 1 each

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
Test-Path gradle\wrapper\gradle-wrapper.jar
```

Both commands failed before Gradle started:

```text
Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
Caused by: java.lang.ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain
```

`Test-Path` returned `False`. No wrapper JAR was added; neither wrapper command is reported as passing.

### Cached Gradle test task: failed to initialize test classes

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:testDebugUnitTest --offline --no-daemon
```

The first sandboxed attempt failed to initialize native services: `Failed to load native library 'native-platform.dll' for Windows 11 amd64`. An approved cache-access retry reached the test task but failed with **21 class-level initialization errors**, each `ClassNotFoundException`; it did not run 21 ordinary test assertions. Output: `21 tests completed, 21 failed`, `BUILD FAILED in 25s`, exit 1; 23 actionable tasks (2 executed, 21 up-to-date).

Gradle's failed-worker report remains at `app/build/reports/tests/testDebugUnitTest/index.html`. These failures are consistent with the existing Windows/Unicode worker-classpath limitation; no claim is made that Task 8 fixes that launcher or proves its precise root cause.

### Cached APK assembly and test compilation: passed

```powershell
$env:ANDROID_HOME='C:/Users/DELL/AppData/Local/Android/Sdk'
& 'C:/Users/DELL/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat' :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --offline --no-daemon
```

Run with approved access to the existing SDK/Gradle cache. **BUILD SUCCESSFUL in 19s**, exit 0; **39 actionable tasks: 3 executed, 36 up-to-date**. Production/test Kotlin classes were up-to-date; dex merging and APK packaging executed against the final Task 7 code. This was an incremental verified build, not a clean rebuild. Existing `android.overridePathCheck=true` experimental warning remains.

### Direct JUnit fallback: passed all 109 tests

```powershell
.\.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1 -All
```

**JUnit 4.13.2: OK (109 tests)**, exit 0. Independently counted 21 `*Test.kt` source files and 21 compiled `*Test.class` files. The runner discovers all those compiled test classes, not only Task 7/8 tests.

The existing runner uses real production/test classes, Android API 35 and cached Firebase/Compose/JUnit dependencies. Its machine-specific dependency list comes from `C:/Users/DELL/.gradle/.tmp/gradle-worker-classpath16396057681371034652txt`; this remains a local fallback, not a portable replacement for the Gradle test task. No stubs were introduced. Direct JUnit passing does not turn the failed wrapper/Gradle worker invocations into passes.

### APK evidence

```text
D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment\app\build\outputs\apk\debug\app-debug.apk
```

- Size: **22,895,703 bytes**.
- Last write UTC: **2026-09-17 11:45:12**.
- SHA-256: **`0604BA5BA4C080A130CD27A4A9ABDB73C2FDFE1BD5C2714E308516FD63C4EE0A`**.
- Checked with `Get-Item ... | Format-List` and `Get-FileHash ... -Algorithm SHA256 | Format-List`.
- Not installed, uploaded or committed. No Android device/emulator or Compose instrumented tests were run.

## Cloud Function unit tests: passed

From `firebase/functions`:

```powershell
npm test
```

`node --test`: **15 tests, 15 passed, 0 failed/cancelled/skipped/todo**, exit 0. Covers resolver windows including overnight/default/zero grace, mapping, entry/exit/session closure, duplicates, unscheduled/out-of-order scans and notification transition helper.

Available Node was **v24.21.0**, whereas the package declares Node **22**. Thus this verifies the available local runtime, not exact deployed Node 22 parity. The 15 tests are pure resolver/notification tests; they do not execute Firestore transactions, triggers, FCM delivery or authenticated rules cases.

## Firmware and Firestore emulator evidence

### Arduino CLI: unavailable

`Get-Command arduino-cli,firebase -ErrorAction SilentlyContinue` found only Firebase CLI. `arduino-cli version` failed with `CommandNotFoundException`: `The term 'arduino-cli' is not recognized ...`. Firmware was statically inspected but not compiled or flashed; no hardware scan/offline retry test is claimed.

### Firebase CLI startup attempts: failed, then direct fallback succeeded

Firebase CLI **15.30.0** was available. Used a demo-only project ID throughout, never the configured production project:

```powershell
$env:XDG_CONFIG_HOME=(Join-Path (Get-Location) '.superpowers/firebase-cli-config')
firebase emulators:exec --only firestore --project demo-attendance-task8 'node --version'
```

1. With default Java **17.0.20.1**, exit 1: `firebase-tools no longer supports Java version before 21`. CLI MOTD fetch also failed in the sandbox; it was explicitly nonfatal.
2. Located existing `C:/Users/DELL/.jdks/jbr-21.0.11`, set process-local Java/PATH, and retried:

```powershell
$env:XDG_CONFIG_HOME=(Join-Path (Get-Location) '.superpowers/firebase-cli-config')
$env:JAVA_HOME='C:/Users/DELL/.jdks/jbr-21.0.11'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
firebase emulators:exec --only firestore --project demo-attendance-task8 'node --version'
```

The sandboxed attempt passed the Java check but failed with `EPERM: operation not permitted, mkdir 'C:\Users\DELL\.cache\firebase\emulators'`, exit 1.

3. Approved cache/network-access retry downloaded `cloud-firestore-emulator-v1.22.0.jar` (HTTP 200). After the download, CLI launched Java with an absolute Unicode `--rules` path. Firestore exited 1 with `java.io.FileNotFoundException: Invalid file path` in `CloudFirestore.init`. The CLI shut down its emulators and removed its hub locator. The nested `node --version` smoke command did not run. No CLI emulator pass is claimed.
4. Direct fallback using the same cached emulator JAR and a **relative** rules path:

```powershell
& 'C:/Users/DELL/.jdks/jbr-21.0.11/bin/java.exe' '-Duser.language=en' -jar 'C:/Users/DELL/.cache/firebase/emulators/cloud-firestore-emulator-v1.22.0.jar' --host 127.0.0.1 --port 8080 --project_id demo-attendance-task8 --rules firebase/firestore.rules --single_project_mode true
```

An initial attempt left `-Duser.language=en` unquoted; PowerShell split it and Java reported `Could not find or load main class .language=en` (exit 1). Quoting that JVM argument as above fixed invocation. The corrected command started successfully: `API endpoint: http://127.0.0.1:8080`, `Dev App Server is now running.` It loaded the repository's existing rules without a rule-compilation error.

5. Against that empty local demo emulator only, issued:

```powershell
Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/v1/projects/demo-attendance-task8/databases/(default)/documents/attendance' -Method Get
```

A surrounding try/catch asserted the expected HTTP status and exited 0. Response: **403**, `{"error":{"code":403,"message":"\nfalse for 'list' @ L120","status":"PERMISSION_DENIED"}}`. Emulator output independently showed rule evaluation at L120. This establishes rule loading/evaluation and denial of one unauthenticated attendance-list operation. It does **not** validate authenticated Admin/device/employee permissions, paired adjustment/audit creates, update/delete denials or trigger transactions.

6. Stopped the direct emulator with Ctrl+C after the smoke check (process exit 1 on interruption, not a test assertion failure). No production writes, emulator seed writes, imports or exports were performed.

## Static and documentation checks

Requested exact command:

```powershell
rg -n "localHour|tm_hour.*12|< 12|CHECK_IN.*12|CHECK_OUT.*12" firmware firebase/functions app/src/main/java
```

Exit 0 because there were two matches, both manually inspected in surrounding source:

```text
firmware/esp8266_fingerprint/esp8266_fingerprint.ino:68:  if (codePoint < 128) return static_cast<char>(codePoint);
firmware/esp8266_fingerprint/esp8266_fingerprint.ino:124:      codePoint = ((first & 0x0F) << 12) |
```

- Line 68 is the ASCII fast path in `vietnameseLetter`, incidentally matching the `< 12` substring of `< 128`.
- Line 124 reconstructs a UTF-8 code point in `lcdSafeText` using a 12-bit left shift, incidentally matching the same regex.
- Neither chooses an attendance type; no noon-based attendance decision was found by the requested scan. Matches were not deleted or silently ignored.

`firebase/firestore.indexes.json` parsed successfully with `ConvertFrom-Json`; manually confirmed the employeeId ASC / scheduleDate ASC / createdAt DESC adjustment index. JSON parsing is not a deployed-index check.

`git diff --check` passed before the documentation commit. `git diff --cached --check` passed before committing README. Only Git LF-to-CRLF informational notices were emitted. Reviewed the README diff against resolver/session, Kotlin pairing/deadline, repository batch/audit, rules, notification helper and changed-file inventory.

The first staging attempt hit a sandbox `index.lock` permission denial under the parent repository's `.git/worktrees/attendance-resolution-adjustment`. Approved worktree-metadata access allowed staging and committing only the requested documentation file. No unrelated changes were staged.

## Intentionally omitted deployment and remaining validation

No Firebase deploy, rules/index publication, Functions deployment, secret update, Auth change, production-data mutation/backfill, firmware flash, APK installation, push or merge was performed. SDK/cache access and emulator download were solely for local verification.

Before production rollout, the operator still needs a reproducible wrapper/test-worker environment, exact Node 22 verification, Arduino compilation/hardware checks, authenticated Firestore adjustment/audit allow/deny tests, and application/Functions integration checks. Deployment of Functions, Rules/indexes and matching firmware is intentionally left to the user. Current passing evidence is limited to the cached Android build, 109 direct JVM tests, 15 Node tests, static/manual checks, and direct Firestore rule-loading/one-denial smoke check.
