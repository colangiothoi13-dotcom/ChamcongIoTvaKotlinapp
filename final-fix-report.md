# Final fix report

Date: 2026-09-18

Worktree: `D:\Thế giới minecraft\ChamcongIoTvaKotlinapp-main\.worktrees\attendance-resolution-adjustment`

Base: `dfcc2b12f07ebe3a492b6ce5bdd859fdcc24ccf6`.

Scope: the four findings in `.superpowers/sdd/2026-09-17-kpi-overtime-request-approval/final-fix-brief.md`.

## Changes

1. **Prevent supplementary schedule assignment.** Weekly templates expose morning and afternoon only. Shared schedule-write validation rejects any `SUPPLEMENTARY` category and the reserved `SUPPLEMENTARY_1730_2030` ID, including a caller disguising that ID as a main shift. Weekly payloads and repository weekly, individual, department, and copy paths apply validation. Individual/department/copy operations read stored shifts from the server; copy validates every candidate before writing its batch and rejects a copy containing supplementary shifts. Firestore create/update rules validate the resulting shift document with `existsAfter` and `getAfter`, permitting batch-created main templates while denying supplementary, reserved-ID, and missing shifts. Existing schedule read rules are unchanged. Assignment UI offers main shifts and directs employees to the fixed-window request flow; it no longer offers preassigned overtime hours.
2. **Keep summaries independent by shift.** The shared `belongsToScheduleDate` predicate rejects explicit foreign shift IDs before the explicit-date shortcut. Employee day/month summaries, report rows, and weekly summaries inherit this filtering. Untagged legacy date/window fallback, overnight handling, adjustments, and legacy schedule reads remain available. Fixed overtime constants, virtual request shifts, request review, and server resolver behavior are unchanged.
3. **Repair Kotlin fixtures.** Added the missing main-shift name and adjustment employee name in `PerformanceRulesTest`. Included the pre-existing `KpiBonusRulesTest` name/employeeName corrections without altering them further.
4. **Read rules-test response bodies once.** The approval case parses request/audit GET bodies once and uses the parsed objects for status diagnostics and field assertions. All 12 overtime rules cases remain present.

## Test-first evidence

- Updated weekly scheduling tests, added `ShiftSummaryIsolationTest`, corrected the Performance fixtures, and added the Node response-body regression before production changes. Added schedule rules regressions before changing Firestore rules.
- `node --test firebase/functions/test/overtimeRulesResponse.test.js` failed before the response fix with `TypeError: Body is unusable: Body has already been read`; after the fix it passed 1/1. This executes the real approval test callback with native single-consumption `Response` bodies and a fake HTTP boundary; it does not validate Firestore authorization.
- Kotlin regressions cover an 08:00 main check-in plus a 17:30/20:30 overtime pair, the reverse orphan-overtime-checkout case, an explicit foreign shift within the main window, pending overtime status isolation, legacy untagged and overnight pairs, and day/month/report/weekly consumers.
- Rules regressions cover create/update for main, supplementary, reserved virtual, and missing shift IDs; batch-created morning/afternoon/supplementary templates; same-batch category changes; and admin/employee reads of a legacy supplementary schedule.

## Verification

- `node --check` on all 10 JavaScript files under `firebase/functions` and `firebase/test`: PASS.
- `npm test --prefix firebase/functions`: PASS, 26 tests, 0 failures, including existing overtime/resolver tests.
- `git diff --check`: PASS.
- Gradle was not invoked, as explicitly requested. Kotlin tests, including overtime domain regressions, and Android compilation remain unverified at runtime.
- Firestore emulator was unavailable and was not invoked. Rules tests were syntax-checked only; authorization behavior still requires an emulator run.
- Reviewed all four repository schedule-write sites, shared summary consumers, and the focused diff. No Firebase application data writes, push, or deployment were performed.

## Commit scope

The local fix commit contains only the related production/rules/test changes and this report. Existing `gradlew`, `.superpowers/firebase-cli-config/`, and `docs/superpowers/plans/2026-09-17-kpi-overtime-request-approval.md` changes are excluded and preserved.

The test-driven-development skill guided test-first changes and the executable response-body regression; verification-before-completion required the recorded checks before committing. Kotlin and emulator verification limitations are explicit above.

Reference: [Firebase rules namespace](https://firebase.google.cn/docs/reference/rules/rules.firestore) documents `existsAfter`/`getAfter` for checking the resulting state of a batched write.
