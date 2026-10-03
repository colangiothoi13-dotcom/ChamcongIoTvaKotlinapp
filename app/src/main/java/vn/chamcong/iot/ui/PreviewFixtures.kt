package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import java.time.LocalDate
import vn.chamcong.iot.model.Announcement
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Department
import vn.chamcong.iot.model.DeviceSnapshot
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.LeaveRequest
import vn.chamcong.iot.model.OvertimeRequest
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.model.WorkSchedule
import vn.chamcong.iot.model.WorkShift

/** Local sample data for Android Studio previews; no Firebase connection is opened. */
internal fun previewUiState(): MainUiState {
    val today = LocalDate.now()
    val employee = Employee(
        id = "nv-01", code = "NV001", fullName = "Nguyễn Văn An",
        email = "an@example.com", phone = "0901234567", departmentId = "ky-thuat",
        department = "Kỹ thuật", position = "Kỹ thuật viên", hireDate = "2024-01-15",
        fingerprintTemplateId = 12, baseSalary = 50_000
    )
    val colleague = Employee(
        id = "nv-02", code = "NV002", fullName = "Trần Thị Bình",
        departmentId = "nhan-su", department = "Nhân sự", position = "Chuyên viên",
        hireDate = "2024-03-01", baseSalary = 55_000
    )
    val shift = WorkShift(id = "morning", name = "Ca sáng", startTime = "08:00", endTime = "12:00")
    val schedule = WorkSchedule(
        id = "schedule-01", employeeId = employee.id, employeeName = employee.fullName,
        department = employee.department, shiftId = shift.id, shiftIds = listOf(shift.id),
        shiftName = shift.name, date = today.toString()
    )
    val scan = Attendance(
        id = "scan-01", employeeId = employee.id, employeeName = employee.fullName,
        deviceId = "GATE-01", type = "CHECK_IN", status = "ON_TIME",
        timestamp = Timestamp.now(), scheduleDate = today.toString(), shiftId = shift.id
    )
    val leave = LeaveRequest(
        id = "leave-01", employeeId = colleague.id, employeeName = colleague.fullName,
        department = colleague.department, startDate = today.plusDays(2).toString(),
        endDate = today.plusDays(2).toString(), reason = "Việc gia đình"
    )
    val overtime = OvertimeRequest(
        id = "overtime-01", employeeId = employee.id, employeeName = employee.fullName,
        department = employee.department, workDate = today.plusDays(1).toString(),
        reason = "Hoàn thành công việc"
    )
    val payroll = Payroll(
        employeeId = employee.id, employeeCode = employee.code, employeeName = employee.fullName,
        month = today.toString().take(7), baseSalary = 8_000_000,
        hourlyRate = employee.baseSalary, hoursWorked = 160.0, bonus = 500_000
    )
    return MainUiState(
        signedIn = true, profileResolved = true,
        employees = listOf(employee, colleague),
        currentEmployee = employee,
        userProfile = UserProfile(uid = "preview-admin", email = "admin@example.com", role = "ADMIN"),
        departments = listOf(
            Department(id = "ky-thuat", name = "Kỹ thuật"),
            Department(id = "nhan-su", name = "Nhân sự")
        ),
        shifts = listOf(shift), schedules = listOf(schedule), employeeSchedules = listOf(schedule),
        attendance = listOf(scan), employeeAttendance = listOf(scan),
        leaveRequests = listOf(leave), employeeRequests = listOf(leave),
        overtimeRequests = listOf(overtime), employeeOvertimeRequests = listOf(overtime),
        payroll = listOf(payroll), employeePayroll = listOf(payroll),
        devices = listOf(DeviceSnapshot(
            id = "GATE-01", name = "Cổng chính", location = "Sảnh tầng 1",
            status = "ONLINE", lastHeartbeat = Timestamp.now(), fingerprintCount = 24, capacity = 127,
            wifiStatus = "CONNECTED", firebaseSyncStatus = "ONLINE", sensorStatus = "READY"
        )),
        announcements = listOf(Announcement(
            id = "announcement-01", title = "Lịch làm việc tuần tới",
            body = "Vui lòng kiểm tra và xác nhận lịch làm việc.", sentAt = Timestamp.now()
        ))
    )
}

@Composable
internal fun rememberPreviewViewModel(state: MainUiState): MainViewModel =
    remember(state) { MainViewModel.forPreview(state) }

@Composable
internal fun PreviewScreen(content: @Composable () -> Unit) {
    ChamCongTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
        }
    }
}

@Composable
internal fun PreviewStateScreen(content: @Composable (MainUiState, MainViewModel) -> Unit) {
    val state = remember { previewUiState() }
    val vm = rememberPreviewViewModel(state)
    PreviewScreen { content(state, vm) }
}
