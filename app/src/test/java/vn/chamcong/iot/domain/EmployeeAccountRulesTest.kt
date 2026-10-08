package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.UserRole

class EmployeeAccountRulesTest {
    @Test
    fun unstartedEnrollmentCanBeRolledBack() {
        val employee = Employee(id = "employee-1", pendingTemplateId = 1)
        assertTrue(canRollbackNewEmployeeProvisioning(employee, "employee-1", "REQUESTED"))
        assertTrue(canRollbackNewEmployeeProvisioning(employee, null, null))
    }

    @Test
    fun deviceAcceptedEnrollmentRetainsEmployeeAndMapping() {
        val employee = Employee(id = "employee-1", pendingTemplateId = 1)
        listOf("PROCESSING", "COMPLETED", "FAILED").forEach { status ->
            assertFalse(canRollbackNewEmployeeProvisioning(employee, "employee-1", status))
        }
    }

    @Test
    fun storedFingerprintCannotBeRemovedByAccountRollback() {
        val employee = Employee(id = "employee-1", fingerprintTemplateId = 1)
        assertFalse(canRollbackNewEmployeeProvisioning(employee, "employee-1", "REQUESTED"))
        assertFalse(canRollbackNewEmployeeProvisioning(employee, "different-employee", "COMPLETED"))
    }

    @Test
    fun validInputCreatesActiveEmployeeProfileLinkedToEmployeeDocument() {
        val input = EmployeeAccountInput(
            email = " employee@example.com ",
            password = "secret123",
            displayName = "Nguyễn Văn A"
        )

        validateEmployeeAccountInput(input)
        val profile = employeeAccountProfile("uid-1", "employee-doc-1", input)

        assertEquals("uid-1", profile.uid)
        assertEquals("employee@example.com", profile.email)
        assertEquals("Nguyễn Văn A", profile.displayName)
        assertEquals(UserRole.EMPLOYEE.name, profile.role)
        assertTrue(profile.active)
        assertEquals("employee-doc-1", profile.employeeId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankEmailIsRejected() {
        validateEmployeeAccountInput(
            EmployeeAccountInput(email = " ", password = "secret123", displayName = "Nguyễn Văn A")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun passwordShorterThanSixCharactersIsRejected() {
        validateEmployeeAccountInput(
            EmployeeAccountInput(email = "employee@example.com", password = "12345", displayName = "Nguyễn Văn A")
        )
    }
}
