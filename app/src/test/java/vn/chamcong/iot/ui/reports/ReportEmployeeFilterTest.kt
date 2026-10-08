package vn.chamcong.iot.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.chamcong.iot.model.Employee

class ReportEmployeeFilterTest {
    private val employee = Employee(id = "firestore-person-id", code = "NV0004")

    @Test fun displayedCodeResolvesToDocumentId() {
        assertEquals(employee.id, resolveReportEmployeeId("NV0004", listOf(employee)))
    }

    @Test fun codeIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(employee.id, resolveReportEmployeeId("  nv0004  ", listOf(employee)))
    }

    @Test fun exactDocumentIdTakesPrecedenceOverAnotherEmployeesCode() {
        val collision = Employee(id = "other-id", code = employee.id)
        assertEquals(employee.id, resolveReportEmployeeId(employee.id, listOf(collision, employee)))
    }

    @Test fun blankInputKeepsAllEmployees() {
        assertNull(resolveReportEmployeeId("  ", listOf(employee)))
    }

    @Test fun unknownDocumentIdRemainsAvailableForHistoricalQueries() {
        assertEquals("historical-id", resolveReportEmployeeId(" historical-id ", listOf(employee)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownDisplayedCodeCannotSilentlyBecomeAnUnrelatedDocumentId() {
        resolveReportEmployeeId("NV9999", listOf(employee))
    }

    @Test fun knownLegacyDocumentIdThatLooksLikeADisplayedCodeStillWorks() {
        val legacy = Employee(id = "NV9999", code = "NV0005")
        assertEquals(legacy.id, resolveReportEmployeeId(legacy.id, listOf(employee, legacy)))
    }
}
