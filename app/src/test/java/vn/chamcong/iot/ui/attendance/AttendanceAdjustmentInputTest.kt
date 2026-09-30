package vn.chamcong.iot.ui.attendance

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AttendanceAdjustmentInputTest {
    @Test fun parsesVietnamTimeAndOvernightDates() {
        val value = parseAttendanceAdjustmentInput("2026-09-17 22:00", "2026-09-18 06:00", "", "  Missing scan  ")
        assertEquals(Instant.parse("2026-09-17T15:00:00Z"), value.checkInAt)
        assertEquals(Instant.parse("2026-09-17T23:00:00Z"), value.checkOutAt)
        assertNull(value.workedHoursOverride)
        assertEquals("Missing scan", value.reason)
    }

    @Test fun blankOptionalTimesAndZeroHoursAreSupported() {
        val value = parseAttendanceAdjustmentInput(" ", "", "0", "Correction")
        assertNull(value.checkInAt)
        assertNull(value.checkOutAt)
        assertEquals(0.0, value.workedHoursOverride!!, 0.0)
    }

    @Test fun rejectsBlankReasonAndNoValues() {
        rejects { parseAttendanceAdjustmentInput("", "", "8", " \n ") }
        rejects { parseAttendanceAdjustmentInput("", " ", "", "Correction") }
    }

    @Test fun rejectsInvalidDatesFormatsAndHours() {
        listOf("2026-02-30 08:00", "2026-09-17 24:00", "17/09/2026 08:00", "2026-9-17 08:00", "2026-09-17T08:00").forEach {
            rejects { parseAttendanceAdjustmentInput(it, "", "", "Correction") }
        }
        listOf("-1", "NaN", "Infinity", "25", "abc").forEach {
            rejects { parseAttendanceAdjustmentInput("", "", it, "Correction") }
        }
    }

    @Test fun rejectsReversedOrEqualTimesIncludingAnUnchangedEndpoint() {
        listOf("2026-09-17 07:00", "2026-09-17 08:00").forEach {
            rejects { parseAttendanceAdjustmentInput("2026-09-17 08:00", it, "", "Correction") }
        }
        rejects {
            parseAttendanceAdjustmentInput("", "2026-09-17 07:00", "", "Correction",
                currentCheckIn = Instant.parse("2026-09-17T01:00:00Z"))
        }
    }

    @Test fun rejectsValuesIdenticalToCurrentValues() {
        rejects {
            parseAttendanceAdjustmentInput("2026-09-17 08:00", "", "", "Correction",
                currentCheckIn = Instant.parse("2026-09-17T01:00:00Z"))
        }
        rejects { parseAttendanceAdjustmentInput("", "", "8", "Correction", currentWorkedHours = 8.0) }
    }

    private fun rejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }
}
