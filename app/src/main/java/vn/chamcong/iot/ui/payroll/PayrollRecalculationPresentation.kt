package vn.chamcong.iot.ui.payroll

import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.ui.MainUiState
import java.time.YearMonth

/** Revalidate the displayed revision, hours and rate against one current UI-state snapshot. */
internal fun validatePayrollRecalculationSnapshot(
    state: MainUiState,
    previous: Payroll,
    hoursWorked: Double,
    hourlyRate: Long
) {
    require(previous.employeeId.isNotBlank()) { "Phiếu lương chưa có mã nhân viên" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(previous.month)) {
        "Tháng lương phải có dạng yyyy-MM"
    }
    val period = YearMonth.parse(previous.month)
    val current = state.payroll.firstOrNull {
        it.employeeId == previous.employeeId && it.month == previous.month
    }
    require(current != null && current == previous) {
        "Phiếu lương đã thay đổi. Vui lòng mở lại trước khi cập nhật."
    }
    val employee = state.historicalEmployees(period).firstOrNull { it.id == previous.employeeId }
    require(employee != null) { "Nhân viên không thuộc danh sách tính lương của tháng này" }
    val previews = payrollHoursPreviews(state, period)
    require(previews != null) { "Cần tải đầy đủ dữ liệu tháng trước khi cập nhật phiếu lương" }
    val preview = previews[previous.employeeId]
    require(preview != null) { "Chưa tính được giờ công của nhân viên trong tháng này" }
    require(hoursWorked.isFinite() && hoursWorked == preview.totalHours) {
        "Giờ công đã thay đổi. Vui lòng xem lại số giờ trước khi cập nhật."
    }
    require(hourlyRate == employee.baseSalary) {
        "Đơn giá lương đã thay đổi. Vui lòng xem lại đơn giá trước khi cập nhật."
    }
}
