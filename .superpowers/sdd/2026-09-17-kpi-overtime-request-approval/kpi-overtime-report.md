# KPI overtime report — Task 6

## Final scope

Task 6 documents and verifies the completed overtime-request, attendance-resolution,
audit, KPI, and payroll flow. No production Kotlin/JavaScript/rules behavior was
changed in this task.

- Request ID: `overtimeRequests/{employeeId}_{workDate}`.
- Fixed window: `17:30–20:30`, `Asia/Ho_Chi_Minh`, exactly 3 hours.
- `PENDING` scans remain raw and scannable as `OVERTIME_PENDING`.
- `APPROVED` requests replay valid scans into the independent supplementary
  attendance session; `REJECTED` requests retain raw scans as
  `OVERTIME_REJECTED` and produce no payable overtime.
- Review writes the request transition and deterministic
  `OVERTIME_REVIEW` audit record atomically.
- KPI: 50,000 VND per completed overtime shift, eligible Top 3 at 500,000 VND
  each, 100,000 VND per main-shift late, total bonus floor 0, payroll deduction
  remains separate and manually administered.

## Commits and files

Implementation was already present at the Task 6 base commit `5e4a71f`:

`14de301`, `d42cf6b`, `8987ef3`, `be10508`, `3c6147a`, `1d7e454`,
`3ef481f`, `ec9f05d`, and `5e4a71f`.

The Task 6 local documentation commit contains only `README.md`, this report,
and `task-6-report.md`; its hash is reported in the final handoff.

Relevant implemented areas are the overtime domain/model rules, Cloud Functions
resolver and replay trigger, Firestore repository/rules/audit pairing, overtime
request UI, and payroll/Performance integration. No production file is part of
the Task 6 commit.

## Schema, rules, and runtime notes

`OvertimeRequest` stores `employeeId`, canonical employee snapshot fields,
`workDate`, fixed `startTime`/`endTime`, `status`, server `createdAt`, nullable
reviewer fields, server `reviewedAt`, and a rejection reason when rejected.
Employees can create/read their own pending deterministic request. Admin review
is restricted to `PENDING → APPROVED/REJECTED`, requires the authenticated
reviewer, requires a nonblank rejection reason for rejection, and denies delete.
The matching `audit_logs/{requestId}_OVERTIME_REVIEW` write is required in the
same transaction by Firestore rules.

Admin and employee request lists sort locally, while attendance replay filters by
`overtimeRequestId`. No new composite Firestore index is required by the current
queries; the checked-in index file remains unchanged for this feature.

## Verification evidence

- `npm test --prefix firebase/functions`: **25 tests, 25 pass, 0 fail**, exit 0.
- Gradle focused/all tests and debug build: not run. The existing wrapper cannot
  start because `gradle/wrapper/gradle-wrapper.jar` is missing.
- Firestore emulator rules suite: not run. Firebase CLI requires Java 21, while
  this environment provides Java 17.
- No production deploy, Firebase seed/write, merge, pull request, or push was
  performed.

## Local-only handoff

Keep the feature branch and worktree as-is. The Task 6 documentation commit is
local and focused; do not push or deploy from this task. The pre-existing changes
to `KpiBonusRulesTest.kt`, `gradlew`, `.superpowers/firebase-cli-config/`, and
the plan file remain untouched and outside the commit.
