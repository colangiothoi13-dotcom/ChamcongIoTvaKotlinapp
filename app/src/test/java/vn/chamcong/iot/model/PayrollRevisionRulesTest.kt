package vn.chamcong.iot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayrollRevisionRulesTest {
    private val previous = Payroll(employeeId = "e1", employeeCode = "NV0001", employeeName = "An",
        month = "2026-10", deduction = 25_000L)

    @Test fun `explicit preview corrects zero-hour snapshot and preserves deduction and identity`() {
        val next = previewPayrollRecalculation(previous, 4.0, 26_000L)
        assertEquals(-25_000L, previous.netSalary)
        assertEquals(0L, previous.revision)
        assertEquals(79_000L, next.netSalary)
        assertEquals(104_000L, next.baseSalary)
        assertEquals(25_000L, next.deduction)
        assertEquals(previous.employeeId, next.employeeId)
        assertEquals(previous.employeeCode, next.employeeCode)
        assertEquals(previous.employeeName, next.employeeName)
        assertEquals(previous.month, next.month)
        assertEquals(1L, next.revision)
    }

    @Test fun `legacy starts at zero and later recalculations increment once with editable adjustments`() {
        val first = previewPayrollRecalculation(previous.copy(bonus = 5_000L), 4.0, 26_000L)
        assertEquals(5_000L, first.bonus)
        val next = previewPayrollRecalculation(first, 4.5, 26_000L, bonus = 8_000L, deduction = 10_000L)
        assertEquals(2L, next.revision)
        assertEquals(115_000L, next.netSalary)
    }

    @Test fun `stale content or rate cannot approve an outdated preview even at same revision`() {
        requirePayrollPreviewCurrent(previous, previous, 26_000L, 26_000L)
        for (changed in listOf(previous.copy(revision = 1), previous.copy(hoursWorked = 1.0),
            previous.copy(deduction = 10L), previous.copy(employeeName = "Other"))) {
            assertThrows(PayrollConflictException::class.java) {
                requirePayrollPreviewCurrent(previous, changed, 26_000L, 26_000L)
            }
        }
        assertThrows(PayrollConflictException::class.java) { requirePayrollPreviewCurrent(previous, previous, 26_000L, 27_000L) }
    }

    @Test fun `invalid amounts identities and unbounded revisions are rejected`() {
        for (hours in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 744.1)) {
            assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous, hours, 26_000L) }
        }
        for (rate in listOf(-1L, 1_000_000_000_001L)) {
            assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous, 4.0, rate) }
        }
        assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous, 4.0, 26_000L, bonus = -1) }
        assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous, 4.0, 26_000L, deduction = -1) }
        assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous.copy(month = "2026-13"), 4.0, 26_000L) }
        assertThrows(IllegalArgumentException::class.java) { previewPayrollRecalculation(previous.copy(employeeId = "bad/id"), 4.0, 26_000L) }
        assertThrows(ArithmeticException::class.java) { previewPayrollRecalculation(previous.copy(revision = Long.MAX_VALUE), 4.0, 26_000L) }
    }

    @Test fun `reason is explicit bounded and normalized`() {
        assertEquals("Bổ sung công", validatePayrollRecalculationReason("  Bổ sung công  "))
        for (reason in listOf(" ", "x".repeat(501))) {
            assertThrows(IllegalArgumentException::class.java) { validatePayrollRecalculationReason(reason) }
        }
    }
}
