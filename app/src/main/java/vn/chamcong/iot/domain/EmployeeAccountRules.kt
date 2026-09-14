package vn.chamcong.iot.domain

import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.UserRole

private val employeeEmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun validateEmployeeAccountInput(input: EmployeeAccountInput) {
    require(employeeEmailPattern.matches(input.email.trim())) { "Email tài khoản nhân viên không hợp lệ" }
    require(input.password.length >= 6) { "Mật khẩu tài khoản phải có ít nhất 6 ký tự" }
    require(input.displayName.isNotBlank()) { "Tên hiển thị tài khoản không được để trống" }
}

fun employeeAccountProfile(
    uid: String,
    employeeId: String,
    input: EmployeeAccountInput
): UserProfile {
    validateEmployeeAccountInput(input)
    require(uid.isNotBlank()) { "Tài khoản chưa có UID" }
    require(employeeId.isNotBlank()) { "Tài khoản chưa được liên kết với nhân viên" }
    return UserProfile(
        uid = uid.trim(),
        email = input.email.trim(),
        displayName = input.displayName.trim(),
        role = UserRole.EMPLOYEE.name,
        active = true,
        employeeId = employeeId.trim()
    )
}
