package vn.chamcong.iot.ui.attendance

import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.AttendanceMethod
import vn.chamcong.iot.model.attendanceMethod

internal fun attendanceMethodLabel(row: Attendance): String = when (row.attendanceMethod()) {
    AttendanceMethod.FINGERPRINT -> "Vân tay"
    AttendanceMethod.FACE -> "Khuôn mặt"
    AttendanceMethod.UNKNOWN -> "Phương thức không xác định"
}
