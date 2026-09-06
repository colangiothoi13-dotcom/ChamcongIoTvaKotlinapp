package vn.chamcong.iot.model

import com.google.firebase.Timestamp

data class Employee(
    val id: String = "",
    val code: String = "",
    val fullName: String = "",
    val email: String = "",
    val department: String = "",
    val position: String = "",
    val fingerprintTemplateId: Int? = null,
    val baseSalary: Long = 0,
    val fingerprintDeviceId: String = "GATE-01",
    val pendingTemplateId: Int? = null,
    val active: Boolean = true
)

enum class AttendanceType { CHECK_IN, CHECK_OUT }
enum class AttendanceStatus { ON_TIME, LATE, EARLY_LEAVE, NORMAL }

data class Attendance(
    val id: String = "",
    val employeeId: String = "",
    val employeeName: String = "",
    val deviceId: String = "",
    val type: String = AttendanceType.CHECK_IN.name,
    val status: String = AttendanceStatus.NORMAL.name,
    val timestamp: Timestamp = Timestamp.now(),
    val verified: Boolean = true
)

data class Payroll(
    val employeeCode: String = "",
    val employeeName: String = "",
    val employeeId: String = "",
    val month: String = "",
    val baseSalary: Long = 0,
    val bonus: Long = 0,
    val deduction: Long = 0
) { @get:com.google.firebase.firestore.Exclude
    val netSalary: Long get() = baseSalary + bonus - deduction }

data class PerformanceReview(
    val employeeId: String = "",
    val period: String = "",
    val score: Double = 0.0,
    val note: String = ""
)
