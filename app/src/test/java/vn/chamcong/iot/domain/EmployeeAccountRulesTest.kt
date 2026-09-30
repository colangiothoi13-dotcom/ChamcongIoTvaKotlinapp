package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.UserRole

class EmployeeAccountRulesTest {
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
