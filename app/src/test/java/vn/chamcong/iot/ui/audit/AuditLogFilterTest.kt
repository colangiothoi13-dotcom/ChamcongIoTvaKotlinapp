package vn.chamcong.iot.ui.audit

import com.google.firebase.Timestamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.AuditLog

class AuditLogFilterTest {
    private val log = AuditLog(
        action = "SHIFT_UPDATE", targetType = "shift", targetId = "shift-01",
        actorName = "Admin", details = "Đổi giờ làm", reason = "Điều chỉnh theo yêu cầu",
        createdAt = Timestamp(0, 0)
    )

    @Test fun searchesTheVietnameseActionAndTargetShownOnScreen() {
        assertTrue(auditLogMatchesQuery(log, "  CẬP NHẬT CA  "))
        assertTrue(auditLogMatchesQuery(log, "Ca làm"))
    }

    @Test fun retainsSearchByRawActionIdAndReason() {
        assertTrue(auditLogMatchesQuery(log, "SHIFT_UPDATE"))
        assertTrue(auditLogMatchesQuery(log, "shift-01"))
        assertTrue(auditLogMatchesQuery(log, "theo yêu cầu"))
    }

    @Test fun blankMatchesAllAndUnknownTextMatchesNone() {
        assertTrue(auditLogMatchesQuery(log, "  "))
        assertFalse(auditLogMatchesQuery(log, "không tồn tại"))
    }
}
