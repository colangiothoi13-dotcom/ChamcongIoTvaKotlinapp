package vn.chamcong.iot.domain

import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.UserRole

fun requireEmployeeAccountAdmin(profile: UserProfile?, uid: String, anonymous: Boolean) {
    require(uid.isNotBlank() && !anonymous && profile != null && profile.uid == uid &&
        profile.role == UserRole.ADMIN.name && profile.active) { "Chỉ Admin đang hoạt động được quản lý tài khoản nhân viên" }
}

fun nextEmployeeAccountVersion(current: Long): Long {
    require(current >= 0 && current < Long.MAX_VALUE) { "Phiên bản tài khoản không hợp lệ" }
    return current + 1
}

fun requireEmployeeAccountProvisioningCurrent(
    employeeExists: Boolean,
    active: Boolean,
    expectedVersion: Long,
    currentVersion: Long,
    linkedAccountExists: Boolean
) {
    require(employeeExists && active) { "Nhân viên đã nghỉ hoặc không tồn tại" }
    require(!linkedAccountExists && expectedVersion == currentVersion) {
        "Tài khoản liên kết vừa thay đổi. Tải lại trước khi tạo tài khoản"
    }
    nextEmployeeAccountVersion(currentVersion)
}

fun employeeAccountProvisioningAvailable(employee: Employee, accounts: List<UserProfile>, loading: Boolean, error: String?): Boolean =
    employee.active && accounts.isEmpty() && !loading && error == null
