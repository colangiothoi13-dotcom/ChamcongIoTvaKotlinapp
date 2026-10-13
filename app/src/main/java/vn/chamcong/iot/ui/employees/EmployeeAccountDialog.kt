package vn.chamcong.iot.ui.employees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import vn.chamcong.iot.domain.employeeAccountProvisioningAvailable
import vn.chamcong.iot.domain.validateEmployeeAccountInput
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.UserProfile
import vn.chamcong.iot.ui.AppSpacing

/** Existing employee profile and login credentials are managed independently. */
@Composable
fun EmployeeAccountDialog(
    employee: Employee,
    accounts: List<UserProfile>,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCreate: (EmployeeAccountInput, () -> Unit) -> Unit,
    operationError: String? = null
) {
    var creating by remember(employee.id) { mutableStateOf(false) }
    var email by remember(employee.id) { mutableStateOf(employee.email) }
    var name by remember(employee.id) { mutableStateOf(employee.fullName) }
    var password by remember(employee.id) { mutableStateOf("") }
    var confirmation by remember(employee.id) { mutableStateOf("") }
    var validationError by remember(employee.id) { mutableStateOf<String?>(null) }
    val available = employeeAccountProvisioningAvailable(employee, accounts, loading, error)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Tài khoản · ${employee.fullName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                Text("${employee.code} · ${if (employee.active) "Đang làm việc" else "Đã nghỉ"}")
                Text("Email liên hệ trong hồ sơ và email đăng nhập có thể khác nhau.", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    CircularProgressIndicator()
                    Text("Đang tải tài khoản liên kết…")
                }
                accounts.forEach { account ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            Text(account.displayName.ifBlank { employee.fullName }, style = MaterialTheme.typography.titleSmall)
                            Text(account.email)
                            Text(if (account.active) "Đang hoạt động" else "Đã khóa")
                            Text("Quyền: ${if (account.role == "EMPLOYEE") "Nhân viên" else account.role}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!loading && error == null && accounts.isEmpty()) Text("Chưa liên kết tài khoản đăng nhập.")
                if (!employee.active) Text("Nhân viên đã nghỉ; hồ sơ tài khoản được giữ để tra cứu.", style = MaterialTheme.typography.bodySmall)
                if (creating && available) {
                    OutlinedTextField(name, { name = it; validationError = null }, label = { Text("Tên hiển thị") },
                        singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it; validationError = null }, label = { Text("Email đăng nhập") },
                        singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it; validationError = null }, label = { Text("Mật khẩu ban đầu (ít nhất 6 ký tự)") },
                        singleLine = true, enabled = !saving, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirmation, { confirmation = it; validationError = null }, label = { Text("Nhập lại mật khẩu") },
                        singleLine = true, enabled = !saving, visualTransformation = PasswordVisualTransformation(),
                        isError = confirmation.isNotEmpty() && confirmation != password, modifier = Modifier.fillMaxWidth())
                    Text("Tài khoản sẽ có quyền Nhân viên và liên kết với hồ sơ này.", style = MaterialTheme.typography.bodySmall)
                }
                (validationError ?: operationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null) TextButton(onClick = onRetry, enabled = !saving) { Text("Tải lại tài khoản") }
            }
        },
        confirmButton = {
            if (available) Button(enabled = !saving, onClick = {
                if (!creating) creating = true
                else {
                    val input = EmployeeAccountInput(email.trim(), password, name.trim())
                    validationError = if (password != confirmation) "Mật khẩu nhập lại chưa khớp"
                        else runCatching { validateEmployeeAccountInput(input) }.exceptionOrNull()?.message
                    if (validationError == null) onCreate(input) {
                        creating = false
                        password = ""
                        confirmation = ""
                        validationError = null
                        onRetry()
                    }
                }
            }) { Text(if (saving) "Đang tạo…" else if (creating) "Xác nhận tạo tài khoản" else "Tạo tài khoản") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Đóng") } }
    )
}
