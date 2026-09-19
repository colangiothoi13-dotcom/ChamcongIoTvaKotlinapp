package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.ui.AppSpacing
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Locale

private val profileTabs = listOf("Cá nhân", "Công việc", "Lịch sử hoạt động")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EmployeeProfileScreen(state: MainUiState, vm: MainViewModel, onChangePassword: () -> Unit) {
    val employee = state.currentEmployee
    if (employee == null) {
        Text("Chưa tải được hồ sơ cá nhân")
        return
    }
    var selectedTab by remember { mutableStateOf(1) }
    val activity = remember(state.employeeAttendance) {
        state.employeeAttendance.sortedByDescending { it.timestamp.seconds }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        item { ProfileHeader(employee) }
        item {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                profileTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }
        }
        when (selectedTab) {
            0 -> item { PersonalInformationCard(employee, state.userProfile?.email.orEmpty()) }
            1 -> {
                item { WorkInformationCard(employee) }
                item { FingerprintCard(employee) }
            }
            else -> {
                if (activity.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Chưa có hoạt động chấm công",
                                modifier = Modifier.padding(AppSpacing.large)
                            )
                        }
                    }
                } else {
                    items(
                        items = activity,
                        key = { attendance -> attendance.id.ifBlank { "${attendance.timestamp.seconds}-${attendance.type}" } }
                    ) { attendance -> AttendanceActivityCard(attendance) }
                }
            }
        }
        item {
            Button(onClick = onChangePassword, modifier = Modifier.fillMaxWidth()) {
                Text("Đổi mật khẩu")
            }
        }
    }
}

@Composable
private fun ProfileHeader(employee: Employee) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = AppSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Ảnh đại diện mặc định",
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.large))
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)) {
            Text(employee.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                employee.position.ifBlank { "Nhân viên" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Mã nhân viên: ${employee.code}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PersonalInformationCard(employee: Employee, fallbackEmail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            Text("Thông tin cá nhân", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ProfileInformationRow("Mã nhân viên", employee.code.ifBlank { "—" })
            ProfileInformationRow("Email", employee.email.ifBlank { fallbackEmail.ifBlank { "Chưa cập nhật" } })
        }
    }
}

@Composable
private fun WorkInformationCard(employee: Employee) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            Text("Thông tin công việc", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ProfileInformationRow("Chức vụ", employee.position.ifBlank { "Chưa cập nhật" })
            ProfileInformationRow("Bộ phận", employee.department.ifBlank { "Chưa cập nhật" })
            ProfileInformationRow("Lương cơ bản / giờ", "${profileMoney(employee.baseSalary)} / giờ")
            ProfileInformationRow("Trạng thái", if (employee.active) "Đang làm việc" else "Đã nghỉ")
        }
    }
}

@Composable
private fun FingerprintCard(employee: Employee) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Text("Vân tay chấm công", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (employee.fingerprintTemplateId != null) "Đã đăng ký" else "Chưa đăng ký",
                color = if (employee.fingerprintTemplateId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileInformationRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.large),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1.25f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AttendanceActivityCard(attendance: Attendance) {
    val occurredAt = remember(attendance.timestamp) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(attendance.timestamp.toDate())
    }
    val activityLabel = when (attendance.type) {
        "CHECK_IN" -> "Chấm công vào"
        "CHECK_OUT" -> "Chấm công ra"
        else -> attendance.type.ifBlank { "Hoạt động chấm công" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xSmall)
        ) {
            Text(activityLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(occurredAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Thiết bị: ${attendance.deviceId.ifBlank { "—" }}")
        }
    }
}

private fun profileMoney(amount: Long): String = java.text.NumberFormat
    .getNumberInstance(Locale("vi", "VN"))
    .format(amount) + " đ"
