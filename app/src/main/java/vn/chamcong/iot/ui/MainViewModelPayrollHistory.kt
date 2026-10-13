package vn.chamcong.iot.ui

import vn.chamcong.iot.data.fetchPayrollRevisionHistory
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.PayrollRevisionSnapshot

suspend fun MainViewModel.loadPayrollRevisionHistory(payroll: Payroll): List<PayrollRevisionSnapshot> {
    check(hasAdminAccess()) { "Chỉ quản lý được xem lịch sử phiếu lương" }
    val actorId = repository.currentUserId
    val history = repository.fetchPayrollRevisionHistory(payroll)
    check(hasAdminAccess() && repository.currentUserId == actorId) { "Phiên đăng nhập đã thay đổi" }
    return history
}
