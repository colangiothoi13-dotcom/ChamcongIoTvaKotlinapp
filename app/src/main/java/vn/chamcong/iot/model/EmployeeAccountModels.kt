package vn.chamcong.iot.model

/** Credentials entered by an admin when provisioning an employee account. */
data class EmployeeAccountInput(
    val email: String,
    val password: String,
    val displayName: String
)
