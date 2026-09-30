# Task 3 report — Firestore model, repository, audit and ViewModel state

## Scope and base

- Worktree: `D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment`
- Base HEAD: `8987ef3`
- Implementation commit: `be10508` (`feat: persist and review overtime requests`)
- No push was performed.

## Implemented files

- `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`
  - Added admin and employee overtime-request flows with local `createdAt` descending sorting.
  - Added deterministic `${employeeId}_${workDate}` submission, fixed-window/domain validation, employee ownership verification, and pending-only fields.
  - Added transactional review using the current authenticated identity, domain transition validation, server timestamps, and an `OVERTIME_REVIEW` audit log in the same transaction.
  - Added the Firestore `Timestamp`/`Instant` boundary mapper.
- `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`
  - Added admin/employee overtime state, mode subscriptions, reset behavior, and submit/review actions.
- `app/src/main/java/vn/chamcong/iot/model/AuditModels.kt`
  - Added `OVERTIME_REVIEW`.
- `firebase/firestore.rules`
  - Added deterministic employee create rules, fixed time/status and null review-field checks, admin pending-to-approved/rejected transition checks, immutable fields, server review timestamp, rejection-reason enforcement, and deny-delete.
- `app/src/test/java/vn/chamcong/iot/domain/AuditRulesTest.kt`
  - Preserved the RED test and added valid/invalid overtime-review audit coverage.
- `firebase/test/overtimeRequestRules.test.js`
  - Preserved the RED test suite with 9 REST cases: own create, other-employee denial, fixed-time tampering, status tampering, employee review denial, admin approve, admin reject with reason, reject without reason, and delete denial.

`AuditRules.kt` did not require a change because its existing enum-based validation accepts the new action automatically. Task 1/Task 2 domain/resolver files were not modified.

## TDD and verification evidence

RED was established before production implementation:

- The focused Kotlin audit test could not execute because `gradlew.bat` cannot load the missing `gradle-wrapper.jar`.
- The cached Gradle 8.9 binary was also unavailable for execution because Gradle failed to load `native-platform.dll`.
- The Firestore emulator suite was authored before rules implementation. It declares 9 cases, but runtime execution was blocked: Firebase CLI 15.30.0 requires Java 21 or newer while this environment provides Java 17. An initial CLI attempt also hit a config-store `EPERM`; a retry with temporary CLI config reached and reported the Java 17 limitation.

Static checks after implementation:

- `node --check firebase/test/overtimeRequestRules.test.js` — PASS, exit 0.
- `git diff --check` — PASS, exit 0.

No Gradle or emulator process was retried after the implementation because the environment limitations were already conclusive.

## Concerns / follow-up

Kotlin compilation and live Firestore rules behavior remain unverified in this environment due the wrapper/native Gradle failures and Java 17 emulator limitation. Run the focused Gradle test with a complete wrapper/native runtime and run the 9-case emulator suite under Java 21+ before merging.

Pre-existing worktree changes were preserved and not staged: `KpiBonusRulesTest.kt`, `gradlew`, the plan file, and `.superpowers/firebase-cli-config/`.

## Fix round 1 — Important findings

Status: IMPLEMENTED; runtime suites intentionally not run per checkpoint instruction.

### Finding 1 — canonical employee identity

- `FirebaseRepository.submitOvertimeRequest` now reads `employees/{employeeId}` from `Source.SERVER`, requires the employee to exist, be active, and have a nonblank canonical name, then serializes `employeeName` and `department` from that record instead of trusting the request snapshot.
- `firebase/firestore.rules` now requires the create payload's name and department to match the active employee document at `employees/{employeeId}`. The existing deterministic request ID, owner/profile check, fixed window, pending status, and null review fields remain enforced.
- The rules suite now seeds employee records and covers name/department tampering plus inactive employee denial.
- No employee identity lookup was added to historical review; review validates the immutable snapshot already stored on the request.

### Finding 2 — atomic deterministic overtime review audit

- `FirebaseRepository.reviewOvertimeRequest` now writes `audit_logs/{requestId}_OVERTIME_REVIEW` in the same transaction as the request transition and includes the reviewed status in the audit payload.
- Rules require the deterministic audit path to be absent in pre-state, require `getAfter` pairing in both directions, and match action, target, actor, status, rejection reason, `reviewedAt`, and audit `createdAt` to the same `request.time`.
- Approve/reject tests now use paired writes, assert the deterministic audit document, and include an unpaired admin review denial. Existing attendance-adjustment audit pairing rules were left intact.

### Fix-round verification

- `node --check firebase/test/overtimeRequestRules.test.js` — PASS, exit 0.
- `git diff --check` — PASS, exit 0.
- Rules test cases authored: 12, including canonical seed records, identity tampering, inactive employee, paired approval/rejection, and unpaired review denial.
- Gradle and Firestore emulator were not run, per the explicit fix-round instruction and the already recorded environment limitations.
- Files changed by this fix round: `FirebaseRepository.kt`, `firestore.rules`, `overtimeRequestRules.test.js`, and this report/fix report. Pre-existing changes outside scope remain unstaged.
