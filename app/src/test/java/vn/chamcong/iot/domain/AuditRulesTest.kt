package vn.chamcong.iot.domain

import com.google.firebase.Timestamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.AuditAction
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.UserProfile

class AuditRulesTest {
    @Test
    fun acceptsOvertimeReviewAuditLog() {
        validateAuditLog(
            AuditLog(
                actorId = "admin-1",
                actorName = "Admin",
                action = AuditAction.OVERTIME_REVIEW.name,
                targetType = "overtimeRequest",
                targetId = "e1_2026-09-17",
                details = "Reviewed overtime request as APPROVED",
                createdAt = Timestamp.now()
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOvertimeReviewAuditWithoutTarget() {
        validateAuditLog(
            AuditLog(
                actorId = "admin-1",
                actorName = "Admin",
                action = AuditAction.OVERTIME_REVIEW.name,
                targetType = "overtimeRequest",
                targetId = ""
            )
        )
    }

    @Test
    fun acceptsACompleteAuditLog() {
        validateAuditLog(
            AuditLog(
                actorId = "admin-1",
                actorName = "Admin",
                action = AuditAction.EMPLOYEE_UPDATE.name,
                targetType = "employee",
                targetId = "e1",
                reason = "Quên chấm công",
                details = "Đã cập nhật phòng ban",
                createdAt = Timestamp.now()
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownAuditAction() {
        validateAuditLog(
            AuditLog(
                actorId = "admin-1",
                actorName = "Admin",
                action = "DELETE_EVERYTHING",
                targetType = "employee",
                targetId = "e1"
            )
        )
    }

    @Test
    fun adminAccessRequiresActiveAdminProfileWhenProfileExists() {
        assertFalse(canAccessAdmin("password", null))
        assertTrue(canAccessAdmin("password", UserProfile(role = "ADMIN", active = true)))
        assertFalse(canAccessAdmin("password", UserProfile(role = "EMPLOYEE", active = true)))
        assertFalse(canAccessAdmin("password", UserProfile(role = "ADMIN", active = false)))
        assertFalse(canAccessAdmin("anonymous", null))
    }
}
