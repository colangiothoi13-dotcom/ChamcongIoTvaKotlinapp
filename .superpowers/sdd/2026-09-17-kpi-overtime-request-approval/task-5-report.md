# Task 5 report — automatic payroll KPI and Performance UI

## Scope completed

- Added payroll-aware `workedHoursForMonth` overload while preserving the existing regular-hours overload.
- Added `MainViewModel.kpiBonusBreakdowns(month)` using the existing `calculateMonthlyKpiBonuses` domain calculator and current attendance, schedule, shift, overtime-request, and adjustment state.
- Updated Payroll to calculate hours and bonus live from state. Hours and bonus breakdown are read-only; deduction remains an Admin input. Saved payroll records continue to store the existing snapshot fields.
- Replaced the Performance placeholder with `PerformanceScreen`, including month selection, Top 3 eligibility, overtime counts/hours, late count, and bonus components.
- Routed `AppDestination.PERFORMANCE` to `PerformanceScreen`.
- Added Task 5 payroll and Performance domain integration coverage for approved completed overtime, pending/rejected/incomplete/invalid overtime, Top 3 ranking, lateness penalty/floor, snapshot behavior, month filtering, adjustment refresh, and legacy regular-hours compatibility.

## Verification

Static checks only, as requested. Gradle and Gradle tests were not run because the worktree is missing `gradle/wrapper/gradle-wrapper.jar` and the request explicitly prohibited running Gradle.

The final checks cover the focused diff, whitespace, changed-path scope, route replacement, presence of both domain calculator calls, preservation of the legacy overload, removal of free-form Payroll hours/bonus inputs, and absence of the old Performance placeholder formula.

No Firebase repository, rules, resolver, scheduling, wrapper, plan/config, Task 1 arithmetic, or pre-existing `KpiBonusRulesTest.kt` changes were included in the Task 5 commit.
