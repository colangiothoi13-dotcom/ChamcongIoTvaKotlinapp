package vn.chamcong.iot.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintRulesTest {
    @Test
    fun resolvesLegacyMappingWhenEmployeeDocumentHasNoTemplateId() {
        val employee = Employee(id = "tri", fullName = "Tri")

        assertEquals(5, resolveFingerprintTemplateId(employee, listOf(5)))
    }

    @Test
    fun prefersStoredTemplateIdBeforeLegacyMapping() {
        val employee = Employee(id = "tri", fullName = "Tri", fingerprintTemplateId = 7)

        assertEquals(7, resolveFingerprintTemplateId(employee, listOf(5)))
    }
}
