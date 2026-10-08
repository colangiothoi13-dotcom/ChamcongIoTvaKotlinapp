package vn.chamcong.iot.ui.employee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EmployeeFormRulesTest {
    @Test fun requestRangesRejectInvalidAndReversedDatesAcrossAllRequestTypes() {
        assertNull(employeeRequestDateRange("2026-10-12", "2026-10-11"))
        assertNull(employeeRequestDateRange("2026-02-30", "2026-03-01"))
        assertNull(employeeRequestDateRange("12/10/2026", "13/10/2026"))
        assertEquals(LocalDate.parse("2026-10-31") to LocalDate.parse("2026-11-01"),
            employeeRequestDateRange(" 2026-10-31 ", "2026-11-01"))
    }

    @Test fun contactInputSupportsClearingAndPhonePunctuationButBlocksServerRejectedInput() {
        assertNull(employeePhoneError(""))
        assertNull(employeeAddressError(""))
        assertNull(employeePhoneError(" +84 (0) 90-123-4567 "))
        assertNotNull(employeePhoneError("call me"))
        assertNull(employeePhoneError("1".repeat(25)))
        assertNotNull(employeePhoneError("1".repeat(26)))
        assertNull(employeeAddressError("a".repeat(200)))
        assertNotNull(employeeAddressError("a".repeat(201)))
    }

    @Test fun supportFormInProfileUsesTheSameBoundAsTheSupportUtility() {
        assertNotNull(employeeSupportReasonError("   "))
        assertNull(employeeSupportReasonError("a".repeat(4000)))
        assertNotNull(employeeSupportReasonError("a".repeat(4001)))
    }
}
