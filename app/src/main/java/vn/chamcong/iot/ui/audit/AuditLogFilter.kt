package vn.chamcong.iot.ui.audit

import vn.chamcong.iot.model.AuditLog
import vn.chamcong.iot.ui.auditActorLabel
import vn.chamcong.iot.ui.auditLogTitle

internal fun auditLogMatchesQuery(log: AuditLog, query: String): Boolean {
    val normalized = query.trim()
    return normalized.isBlank() || listOf(
        log.action, log.targetType, log.targetId, log.actorName, log.details, log.reason,
        auditLogTitle(log.action, log.targetType), auditActorLabel(log.actorName)
    ).any { it.contains(normalized, ignoreCase = true) }
}
