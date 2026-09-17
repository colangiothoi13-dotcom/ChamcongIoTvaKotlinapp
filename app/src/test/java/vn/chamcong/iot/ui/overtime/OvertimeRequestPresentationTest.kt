package vn.chamcong.iot.ui.overtime

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.OvertimeRequestStatus

class OvertimeRequestPresentationTest {
    @Test
    fun statusLabelsAreVietnameseAndUnknownValuesDoNotLeakTechnicalCodes() {
        assertEquals("Chờ duyệt", overtimeStatusLabel(OvertimeRequestStatus.PENDING.name))
        assertEquals("Đã duyệt", overtimeStatusLabel(OvertimeRequestStatus.APPROVED.name))
        assertEquals("Từ chối", overtimeStatusLabel(OvertimeRequestStatus.REJECTED.name))
        assertEquals("Không xác định", overtimeStatusLabel("LEGACY_BROKEN_STATUS"))
    }

    @Test
    fun fixedWindowIsShownAndStaleStoredTimesAreVisiblyFlagged() {
        assertEquals("17:30–20:30", overtimeWindowLabel(OvertimeRequest()))

        val staleLabel = overtimeWindowLabel(
            OvertimeRequest(startTime = "18:00", endTime = "22:00")
        )

        assertTrue(staleLabel.startsWith("17:30–20:30"))
        assertTrue(staleLabel.contains("Dữ liệu giờ đã lưu không hợp lệ"))
        assertFalse(staleLabel.contains("18:00"))
        assertFalse(staleLabel.contains("22:00"))
    }

    @Test
    fun employeeDateAllowsTodayAndFutureButRejectsPastOrMalformedValues() {
        val today = LocalDate.parse("2026-09-18")

        assertTrue(isValidOvertimeWorkDate("2026-09-18", today))
        assertTrue(isValidOvertimeWorkDate("2026-09-19", today))
        assertFalse(isValidOvertimeWorkDate("2026-09-17", today))
        assertFalse(isValidOvertimeWorkDate("18/09/2026", today))
        assertFalse(isValidOvertimeWorkDate("", today))
    }
}
