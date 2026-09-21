package vn.chamcong.iot.domain

import vn.chamcong.iot.model.AuditAction
import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.UserRole

fun validateAuditLog(log: AuditLog) {
    require(log.actorId.isNotBlank()) { "Audit log thiếu người thực hiện" }
    require(log.actorName.isNotBlank()) { "Audit log thiếu tên người thực hiện" }
    require(log.action in AuditAction.entries.map { it.name }) { "Hành động audit không hợp lệ" }
    require(log.targetType.isNotBlank()) { "Audit log thiếu loại đối tượng" }
    require(log.targetId.isNotBlank()) { "Audit log thiếu mã đối tượng" }
}

fun canAccessAdmin(provider: String, profile: UserProfile?): Boolean {
    if (provider.equals("anonymous", ignoreCase = true)) return false
    return profile?.active == true && profile.role == UserRole.ADMIN.name
}
