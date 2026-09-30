# SDD ledger — plan: docs/superpowers/plans/2026-09-17-attendance-resolution-adjustment.md

## Setup

- Worktree: `.worktrees/attendance-resolution-adjustment`
- Branch: `feat/attendance-resolution-adjustment`
- Merge-base/start commit: `2c5c2f6dfe8a15c1f5f91ab85322b52de1ee84b7`
- Spec: `docs/superpowers/specs/2026-09-17-attendance-resolution-adjustment-design.md`
- Plan: `docs/superpowers/plans/2026-09-17-attendance-resolution-adjustment.md`

## Preflight plan/interface scan

| Scope | Files or interface shared | Check | Ruling/status |
|---|---|---|---|
| Task 1 ↔ Task 2 | `AttendanceResolutionModels.kt`, `AttendanceResolutionRulesTest.kt`, `SchedulingModels.kt` | Task 1 produces `AttendanceAdjustment`, `AttendancePair`, resolution statuses and shift grace; Task 2 consumes them in `shiftWindow`/pair functions. | Consistent; Task 1 owns model defaults and validation, Task 2 owns pair behavior. |
| Task 1 ↔ Task 5 | `AttendanceAdjustment`, `AttendanceResolutionStatus` | Repository mapping depends on nullable timestamps, reason and actor fields defined by Task 1. | Consistent; Task 5 must not introduce a second adjustment model. |
| Task 2 ↔ Task 5 | `resolveAttendancePair`, `latestAdjustment` | Repository state feeds the pure domain calls; no Firebase calls may enter Task 2. | Consistent; Task 5 passes lists into domain only. |
| Task 2 ↔ Task 6 | `AttendanceResolutionRules.kt`, `shiftWindow`, `isMissingCheckOut` | Presence/report/payroll consume the resolver and schedule date. | Consistent; Task 6 must preserve legacy default arguments. |
| Task 3 ↔ Task 4 | Raw payload contract `type=SCAN`, `resolutionStatus=PENDING`, `status=PENDING`, NTP `timestamp` | Firmware creates the document that the Cloud trigger resolves. | Consistent; Task 4 must not add local type heuristics. |
| Task 3 ↔ Task 5 | `attendance` rules and `attendanceSessions` collection | Cloud Function uses Admin SDK; client/device only creates pending raw scans. | Consistent; Task 5 rules must permit only the raw create shape. |
| Task 3 ↔ Task 8 | `node --test`, notification behavior | Final verification must run the functions test script and confirm no 12-hour heuristic remains. | Consistent. |
| Task 4 ↔ Task 8 | `README.md` attendance/firmware contract | Task 4 records the raw payload; Task 8 completes the full operational documentation. | Sequential shared-file edits are intentional; later Task 8 preserves Task 4 content. |
| Task 5 ↔ Task 6 | `MainUiState.attendanceAdjustments`, report/payroll/presence inputs | Task 5 loads state; Task 6 adds consumers. | Consistent; Task 6 must compile against the exact state property from Task 5. |
| Task 5 ↔ Task 7 | `MainViewModel.adjustAttendance`, `MainUiState`, repository method | Task 7 supplies UI input; Task 5 owns persistence/action wiring. | Consistent. |
| Task 6 ↔ Task 7 | `AttendanceScreen` visible resolution and summary status | Task 6 defines status semantics; Task 7 only presents them and opens adjustment. | Consistent; UI must not recalculate type. |
| Task 1 self-check | Model files/tests | Tests intentionally fail before new symbols; implementation adds exact fields and validation named in the task. | Consistent. |
| Task 2 self-check | Resolver/SchedulingRules/tests | The task's approved-leave bullet names `PresenceRulesTest.kt`, which is outside its file list and overlaps Task 6 ownership. | **Ruling:** keep Task 2 tests focused on `isMissingCheckOut`; move approved-leave priority regression to Task 6, where `PresenceRules.kt` is changed. Cost if wrong: leave-priority coverage arrives one task later, but no behavior is lost. |
| Task 3 self-check | Node helper/index/tests/package | Pure helper is testable without Firebase imports; index owns Firestore trigger and notification guard. | Consistent. |
| Task 4 self-check | Firmware/README | Existing outbox/eventId/timestamp are preserved; only classification/status payload and display change. | Consistent. |
| Task 5 self-check | Repository/ViewModel/rules/indexes/tests | Batch write needs server timestamps and latest-adjustment lookup; state subscription precedes downstream consumers. | Consistent. |
| Task 6 self-check | Domain/payroll/screens/tests | Existing public signatures remain usable with defaults; new state is passed by UI only after Task 5. | Consistent. |
| Task 7 self-check | Dialog/AttendanceScreen/MainViewModel | Pure input parser prevents Compose-only validation gaps; submit goes through the repository action. | Consistent. |
| Task 8 self-check | README/full test/static checks | Final task documents all contracts and runs Android, Node, build and static checks. | Consistent. |

## Review log

- Task 1: fix round 1/5 (2 addressed, 0 open; commits `8acf982`..`64cf894`)
- Task 1: complete (commits `2c5c2f6`..`64cf894`, review clean)
- Task 2: fix round 1/5 (1 addressed, 0 open; commits `f025e95`..`f0888db`)
- Task 2: complete (commits `64cf894`..`f0888db`, review clean; focused tests remain environment-blocked)
- Task 3: fix round 1/5 (3 addressed, 0 open; commits `f1c8462`..`59b0777`)
- Task 3: fix round 2/5 (1 addressed, 0 open; commits `59b0777`..`f6d6942`)
- Task 3: minor (deferred): no Firestore emulator integration test; pure helper and syntax coverage are present.
- Task 3: minor (deferred): mapped employee object canonical-id spread observation from review; revisit in final whole-branch review.
- Task 3: complete (commits `f0888db`..`f6d6942`, review clean)
- Task 4: fix round 1/5 (1 addressed, 0 open; commits `f54bd96`..`aa88683`)
- Task 4: minor (deferred): Arduino CLI unavailable; source/static verification completed.
- Task 4: complete (commits `f6d6942`..`aa88683`, review clean)

## Execution log

- Task 1: complete
- Task 2: complete
- Task 3: complete
- Task 4: complete
- Task 5: pending
- Task 6: pending
- Task 7: pending
- Task 8: pending
