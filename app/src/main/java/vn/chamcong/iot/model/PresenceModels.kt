package vn.chamcong.iot.model

enum class PresenceStatus {
    PRESENT,
    NOT_CHECKED_IN,
    ON_LEAVE,
    LEFT,
    MISSING_CHECK_OUT,
    ABNORMAL
}

data class PresenceRecord(
    val employee: Employee,
    val status: PresenceStatus,
    val latestAttendance: Attendance? = null
)
