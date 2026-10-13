package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.UserProfile

class EmployeeAccountProvisioningRulesTest {
    @Test fun onlyActiveEmailAdminMayManageAccounts() {
        val admin = UserProfile(uid = "admin", role = "ADMIN", active = true)
        requireEmployeeAccountAdmin(admin, "admin", false)
        listOf(admin.copy(role = "EMPLOYEE"), admin.copy(active = false), admin.copy(uid = "other"), null).forEach {
            assertTrue(runCatching { requireEmployeeAccountAdmin(it, "admin", false) }.isFailure)
        }
        assertTrue(runCatching { requireEmployeeAccountAdmin(admin, "admin", true) }.isFailure)
    }

    @Test fun retiredEmployeeAndConcurrentLinkChangesAbortProvisioning() {
        requireEmployeeAccountProvisioningCurrent(true, true, 3, 3, false)
        assertTrue(runCatching { requireEmployeeAccountProvisioningCurrent(false, true, 3, 3, false) }.isFailure)
        assertTrue(runCatching { requireEmployeeAccountProvisioningCurrent(true, false, 3, 3, false) }.isFailure)
        assertTrue(runCatching { requireEmployeeAccountProvisioningCurrent(true, true, 3, 4, false) }.isFailure)
        assertTrue(runCatching { requireEmployeeAccountProvisioningCurrent(true, true, 3, 3, true) }.isFailure)
    }

    @Test fun accountVersionAdvancesWithoutOverflow() {
        assertEquals(1L, nextEmployeeAccountVersion(0))
        assertEquals(12L, nextEmployeeAccountVersion(11))
        assertTrue(runCatching { nextEmployeeAccountVersion(-1) }.isFailure)
        assertTrue(runCatching { nextEmployeeAccountVersion(Long.MAX_VALUE) }.isFailure)
    }

    @Test fun unknownOrExistingAccountsNeverExposeCreation() {
        val employee = Employee(id = "employee", active = true)
        assertTrue(employeeAccountProvisioningAvailable(employee, emptyList(), false, null))
        assertFalse(employeeAccountProvisioningAvailable(employee.copy(active = false), emptyList(), false, null))
        assertFalse(employeeAccountProvisioningAvailable(employee, listOf(UserProfile(active = false)), false, null))
        assertFalse(employeeAccountProvisioningAvailable(employee, emptyList(), true, null))
        assertFalse(employeeAccountProvisioningAvailable(employee, emptyList(), false, "Chưa tải được"))
    }
}
