package vn.chamcong.iot.ui.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.MainViewModel

@Composable
fun EmployeeProfileScreen(state: MainUiState, vm: MainViewModel, onChangePassword: () -> Unit) {
    val employee = state.currentEmployee
    if (employee == null) {
        Text("Chưa tải được hồ sơ cá nhân")
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(employee.fullName, style = MaterialTheme.typography.headlineSmall)
            Text("Mã nhân viên: ${employee.code}")
            Text("Chức vụ: ${employee.position.ifBlank { "Chưa cập nhật" }}")
            Text("Phòng ban: ${employee.department.ifBlank { "Chưa cập nhật" }}")
            Text("Email: ${employee.email.ifBlank { state.userProfile?.email.orEmpty() }}")
            Text("Vân tay: ${if (employee.fingerprintTemplateId != null) "Đã đăng ký" else "Chưa đăng ký"}")
            Text("Mã nhân viên, chức vụ, lương, ca chính và mã vân tay do Admin quản lý.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onChangePassword) { Text("Đổi mật khẩu") }
        }
    }
}
