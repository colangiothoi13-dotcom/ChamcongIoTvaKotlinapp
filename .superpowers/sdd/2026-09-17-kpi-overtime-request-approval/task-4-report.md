# Task 4 report — overtime request presentation and Compose UI

## Implemented files

- `app/src/main/java/vn/chamcong/iot/ui/overtime/OvertimeRequestPresentation.kt`
- `app/src/main/java/vn/chamcong/iot/ui/overtime/OvertimeRequestScreen.kt`
- `app/src/main/java/vn/chamcong/iot/ui/requests/RequestsScreen.kt`
- `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeRequestsScreen.kt`
- `app/src/test/java/vn/chamcong/iot/ui/overtime/OvertimeRequestPresentationTest.kt`

The employee flow accepts today/future `yyyy-MM-dd` dates using `Asia/Ho_Chi_Minh`, presents the fixed `17:30–20:30` window, disables invalid or saving submissions, and shows pending/approved/rejected history with rejection reasons. The Admin section has an independent status filter, keeps pending requests visible regardless of date, approves without a reason, and requires a nonblank rejection reason.

Existing leave-request flows remain in place. No payroll, scheduling, repository, resolver, rules, `gradlew`, plan/config, or pre-existing `KpiBonusRulesTest.kt` changes were included.

## Test and compile evidence

- TDD presentation tests were written before the production presentation code.
- Focused command attempted:

  `.\gradlew.bat test --tests "vn.chamcong.iot.ui.overtime.OvertimeRequestPresentationTest" --offline --no-daemon`

- Result: blocked before compilation because the existing wrapper is missing `gradle-wrapper.jar`; Java reported `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`.
- Per instruction, Gradle was not run again and `gradlew` was not modified.
- `node --check firebase/functions/attendanceResolver.js`: passed.
- `node --check firebase/functions/index.js`: passed.
- `git diff --check`: passed with only existing CRLF/LF normalization warnings.

Kotlin/Compose compilation and the JUnit test result remain unverified because the wrapper cannot start in this environment.

## Concerns

The fixed-window presentation explicitly flags stale stored start/end times and never renders them as editable controls. Full UI compilation should be run in an environment with a usable Gradle wrapper before merge.

## Local commit

Implementation commit: `1d7e454` (`feat: add employee overtime requests UI`)
