package vn.chamcong.iot.domain

import java.time.LocalDate
import java.time.YearMonth

/** Read separate small windows instead of spanning years between historical and current dates. */
fun scheduleReadRanges(
    selectedWeek: LocalDate,
    presenceDate: LocalDate,
    today: LocalDate
): List<AttendanceDateRange> {
    val month = YearMonth.from(selectedWeek)
    val gridStart = mondayOfWeek(month.atDay(1))
    return listOf(
        AttendanceDateRange(gridStart.minusDays(1), gridStart.plusDays(42)),
        AttendanceDateRange(mondayOfWeek(today).minusDays(1), mondayOfWeek(today).plusDays(7)),
        AttendanceDateRange(presenceDate.minusDays(1), presenceDate.plusDays(1))
    ).distinct()
}
