package vn.chamcong.iot.model

import org.junit.Assert.*
import org.junit.Test

class PayrollHistoryModelsTest {
    @Test fun `history uses canonical document identity and never invents a missing base snapshot value`() {
        assertEquals("e_with_underscore_2026-10", payrollHistoryDocumentId(Payroll(employeeId = "e_with_underscore", month = "2026-10")))
        for (invalid in listOf(Payroll(employeeId = "bad/id", month = "2026-10"), Payroll(employeeId = "e1", month = "2026-13")))
            assertThrows(IllegalArgumentException::class.java) { payrollHistoryDocumentId(invalid) }
        val raw = mapOf<String, Any?>("baseSalary" to 100000L, "bonus" to 50000L, "deduction" to 20000L)
        assertEquals(130000L, payrollSnapshotNet(raw)); assertFalse(raw.containsKey("revision"))
        assertEquals(100000L, payrollSnapshotNet(mapOf("baseSalary" to 100000L)))
        assertNull(payrollSnapshotNet(mapOf("hoursWorked" to 4.0)))
        assertNull(payrollSnapshotNet(mapOf("baseSalary" to Long.MAX_VALUE, "bonus" to 1L)))
    }
}
