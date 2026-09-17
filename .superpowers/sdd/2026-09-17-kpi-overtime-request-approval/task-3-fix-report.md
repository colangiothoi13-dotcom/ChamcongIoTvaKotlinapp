# Task 3 fix-round report

## Scope

Fix only the two Important findings from the Task 3 review. Task 1, Task 2, historical review snapshots, and pre-existing worktree changes were kept out of scope.

## Finding 1: canonical employee identity

`FirebaseRepository.submitOvertimeRequest` reads `employees/{employeeId}` from the server, checks existence/active state, and writes the employee document's canonical `fullName` and `department`. The Firestore create rule independently requires those two fields to match the active employee record, so a tampered client snapshot cannot be persisted.

The rules tests seed canonical employee records and cover name tampering, department tampering, and inactive employee denial. Historical review does not perform an employee-document identity check, preserving stored snapshots after employee-profile changes.

## Finding 2: atomic deterministic review audit

`FirebaseRepository.reviewOvertimeRequest` now writes `audit_logs/{requestId}_OVERTIME_REVIEW` in the same transaction as the request status update. The audit carries the reviewed status, actor, target, reason, and server timestamp.

The rules enforce the pair in both directions with `getAfter`: the deterministic audit document must be absent in pre-state, the request must transition from `PENDING`, and audit/request action, target, actor, status, reason, and timestamps must match. Existing attendance-adjustment audit rules were not changed.

## Verification

- `node --check firebase/test/overtimeRequestRules.test.js` — PASS, exit 0.
- `git diff --check` — PASS, exit 0.
- 12 rules test cases are authored, including paired approve/reject and unpaired review denial.
- Gradle and Firestore emulator were intentionally not run, as requested; the original report records the missing Gradle runtime/native component and Java 17 versus Firebase CLI Java 21 requirement.

## Scope preservation

The pre-existing changes in `KpiBonusRulesTest.kt`, `gradlew`, the plan file, and `.superpowers/firebase-cli-config/` remain untouched and unstaged.
