package vn.chamcong.iot.model

enum class AttendanceMethod { FINGERPRINT, FACE, UNKNOWN }

/** Only omitted historical metadata falls back to fingerprint attendance. */
fun Attendance.attendanceMethod(): AttendanceMethod = when (method) {
    null, AttendanceMethod.FINGERPRINT.name -> AttendanceMethod.FINGERPRINT
    AttendanceMethod.FACE.name -> AttendanceMethod.FACE
    else -> AttendanceMethod.UNKNOWN
}
