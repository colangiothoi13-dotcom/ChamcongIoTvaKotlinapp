package vn.chamcong.iot.model

/**
 * Returns the template slot to clean up when an employee was created by an
 * older app version and the slot only exists in fingerprintMappings.
 */
fun resolveFingerprintTemplateId(employee: Employee, mappingTemplateIds: List<Int>): Int? =
    employee.fingerprintTemplateId
        ?: employee.pendingTemplateId
        ?: mappingTemplateIds.firstOrNull()
