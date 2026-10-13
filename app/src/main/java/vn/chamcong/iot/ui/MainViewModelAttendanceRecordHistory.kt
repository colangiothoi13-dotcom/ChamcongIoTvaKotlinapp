package vn.chamcong.iot.ui

import vn.chamcong.iot.data.fetchAttendanceRecordHistory
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceRecordHistory
import vn.chamcong.iot.ui.attendance.attendanceAdjustmentDate

suspend fun MainViewModel.loadAttendanceRecordHistory(row: Attendance): AttendanceRecordHistory {
    check(hasAdminAccess()) { "Chỉ Admin được xem lịch sử chấm công." }
    val actorId = repository.currentUserId
    val date = attendanceAdjustmentDate(row) ?: error("Ngày ca không hợp lệ.")
    val history = repository.fetchAttendanceRecordHistory(row.id, row.employeeId, date)
    check(hasAdminAccess() && repository.currentUserId == actorId) { "Phiên đăng nhập đã thay đổi." }
    return history
}
