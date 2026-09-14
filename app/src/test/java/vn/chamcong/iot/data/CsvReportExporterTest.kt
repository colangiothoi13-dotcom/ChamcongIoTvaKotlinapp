package vn.chamcong.iot.data

import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.AttendanceReportRow

class CsvReportExporterTest {
    @Test
    fun csvIncludesBomAndEscapesCommaQuotesAndNewlines() {
        val csv = attendanceRowsToCsv(
            listOf(
                AttendanceReportRow(
                    date = "2026-09-14",
                    employeeId = "e1",
                    employeeName = "An, \"A\"",
                    department = "Kinh doanh",
                    checkIn = "08:00",
                    checkOut = "17:00",
                    status = "LATE\nNEEDS REVIEW",
                    workedHours = 8.0,
                    overtimeHours = 0.0
                )
            )
        )

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"An, \"\"A\"\"\""))
        assertTrue(csv.contains("\"LATE\nNEEDS REVIEW\""))
    }
}
