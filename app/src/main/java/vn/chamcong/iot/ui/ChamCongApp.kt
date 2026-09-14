package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Locale
import vn.chamcong.iot.model.Attendance
import vn.chamcong.iot.model.EmployeeAccountInput
import vn.chamcong.iot.model.Employee
import vn.chamcong.iot.ui.attendance.AttendanceScreen
import vn.chamcong.iot.ui.dashboard.DashboardScreen
import vn.chamcong.iot.ui.devices.DevicesScreen
import vn.chamcong.iot.ui.employees.EmployeesScreen
import vn.chamcong.iot.ui.schedule.ScheduleScreen
import vn.chamcong.iot.ui.shifts.ShiftsScreen
import vn.chamcong.iot.ui.presence.PresenceScreen
import vn.chamcong.iot.ui.requests.RequestsScreen
import vn.chamcong.iot.ui.audit.AuditScreen
import vn.chamcong.iot.ui.reports.ReportsScreen
import vn.chamcong.iot.ui.admin.AdminTasksScreen
import vn.chamcong.iot.ui.admin.ShiftManagementScreen
import vn.chamcong.iot.ui.employee.EmployeeHomeScreen
import vn.chamcong.iot.ui.employee.EmployeeAttendanceScreen
import vn.chamcong.iot.ui.employee.EmployeeRequestsScreen
import vn.chamcong.iot.ui.employee.EmployeeProfileScreen

private val Brand = Color(0xFF147D64)
private val Background = Color(0xFFF5F8F6)

enum class AppDestination(val title: String) {
    DASHBOARD("Tổng quan"),
    TASKS("Tác vụ"),
    REQUESTS("Đơn từ"),
    SHIFT_MANAGEMENT("Phân ca"),
    EMPLOYEES("Nhân viên"),
    ATTENDANCE("Chấm công"),
    DEVICES("Thiết bị"),
    PAYROLL("Lương"),
    PERFORMANCE("Hiệu suất"),
    SHIFTS("Ca làm"),
    SCHEDULE("Lịch"),
    PRESENCE("Có mặt"),
    REPORTS("Báo cáo"),
    AUDIT("Nhật ký"),
    SETTINGS("Cài đặt")
}

enum class EmployeeDestination(val title: String) {
    HOME("Trang chủ"),
    ATTENDANCE("Chấm công của tôi"),
    REQUESTS("Đơn từ"),
    PROFILE("Cá nhân")
}

val adminPrimaryDestinations = listOf(
    AppDestination.DASHBOARD,
    AppDestination.TASKS,
    AppDestination.REQUESTS,
    AppDestination.SHIFT_MANAGEMENT,
    AppDestination.EMPLOYEES
)

val adminTaskDestinations = listOf(
    AppDestination.ATTENDANCE,
    AppDestination.DEVICES,
    AppDestination.PRESENCE,
    AppDestination.SHIFTS,
    AppDestination.SCHEDULE,
    AppDestination.PAYROLL,
    AppDestination.PERFORMANCE,
    AppDestination.REPORTS,
    AppDestination.AUDIT,
    AppDestination.SETTINGS
)

val employeePrimaryDestinations = listOf(
    EmployeeDestination.HOME,
    EmployeeDestination.ATTENDANCE,
    EmployeeDestination.REQUESTS,
    EmployeeDestination.PROFILE
)

@Composable
fun ChamCongApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, background = Background)) {
        if (!state.signedIn) LoginScreen(state.loading, state.error, state.message, vm::signIn, vm::sendPasswordReset)
        else if (!state.profileResolved) RoleLoading()
        else if (vm.hasEmployeeAccess()) EmployeeHomeShell(state, vm)
        else if (!vm.hasAdminAccess()) AccessBlocked(vm::signOut)
        else AdminHomeScreen(state, vm)
    }
}

@Composable
private fun RoleLoading() {
    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Đang kiểm tra quyền truy cập…")
        }
    }
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, message: String?, onLogin: (String, String) -> Unit, onReset: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Background) {
        Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.widthIn(max = 440.dp), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Fingerprint, null, tint = Brand, modifier = Modifier.size(48.dp))
                    Text("Chấm công IoT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Đăng nhập tài khoản của bạn")
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mật khẩu") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    message?.let { Text(it, color = Brand) }
                    Button({ onLogin(email, password) }, Modifier.fillMaxWidth(), enabled = !loading && email.isNotBlank() && password.isNotBlank()) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Đăng nhập")
                    }
                    TextButton({ onReset(email) }, enabled = !loading) { Text("Quên mật khẩu") }
                }
            }
        }
    }
}

@Composable
private fun AccessBlocked(onSignOut: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tài khoản chưa được cấp quyền", style = MaterialTheme.typography.titleLarge)
            Text("Admin cần kiểm tra role, active và employeeId trong users/{uid} trên Firebase.")
            Button(onSignOut) { Text("Đăng xuất") }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AdminHomeScreen(state: MainUiState, vm: MainViewModel) {
    var selected by remember { mutableStateOf(AppDestination.DASHBOARD) }
    var showAdd by remember { mutableStateOf(false) }
    var employeeToEnroll by remember { mutableStateOf<Employee?>(null) }
    var employeeToRemove by remember { mutableStateOf<Employee?>(null) }
    var retire by remember { mutableStateOf(false) }
    var salaryEmployee by remember { mutableStateOf<Employee?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showAccountMenu by remember { mutableStateOf(false) }
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.FactCheck, Icons.Default.Description, Icons.Default.Event, Icons.Default.Groups)
    Scaffold(
        topBar = { TopAppBar(title = { Text(selected.title) }, actions = {
            IconButton({ showAccountMenu = true }, enabled = !state.saving) { Icon(Icons.Default.AccountCircle, "Menu cá nhân") }
            DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Đổi mật khẩu") },
                    onClick = { showChangePassword = true; showAccountMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Đăng xuất") },
                    onClick = { showAccountMenu = false; vm.signOut() }
                )
            }
        }) },
        bottomBar = { NavigationBar {
            adminPrimaryDestinations.forEachIndexed { index, destination ->
                NavigationBarItem(
                    selected == destination,
                    { selected = destination },
                    { Icon(icons[index], destination.title) },
                    label = { Text(destination.title, maxLines = 1) }
                )
            }
        } },
        floatingActionButton = { if (selected == AppDestination.EMPLOYEES) FloatingActionButton({ vm.clearError(); showAdd = true }) { Icon(Icons.Default.PersonAdd, "Thêm nhân viên") } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            state.error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color=Brand, modifier=Modifier.padding(bottom=8.dp)) }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (selected) {
                AppDestination.DASHBOARD -> DashboardScreen(state, vm) { selected = AppDestination.ATTENDANCE }
                AppDestination.TASKS -> AdminTasksScreen { destination -> selected = destination }
                AppDestination.SHIFT_MANAGEMENT -> ShiftManagementScreen { destination -> selected = destination }
                AppDestination.EMPLOYEES -> EmployeesScreen(
                    state = state,
                    vm = vm,
                    onAdd = { vm.clearError(); showAdd = true },
                    onEnroll = { vm.clearError(); employeeToEnroll = it },
                    onSalary = { vm.clearError(); salaryEmployee = it },
                    onRemove = { employee, removeAll ->
                        vm.clearError()
                        employeeToRemove = employee
                        retire = removeAll
                    }
                )
                AppDestination.ATTENDANCE -> AttendanceScreen(state, vm)
                AppDestination.PAYROLL -> PayrollScreen(state, vm)
                AppDestination.PERFORMANCE -> Placeholder("Điểm chuyên cần", "Chưa tính KPI tự động. Công thức đề xuất: 70 × (ngày đi làm / ngày phải làm) + 30 × (ngày đúng giờ / ngày đi làm), tối đa 100 điểm. Cần thiết lập lịch làm và lấy đủ dữ liệu theo tháng; 50 lượt gần nhất chưa đủ để đánh giá.", Icons.Default.Insights)
                AppDestination.DEVICES -> DevicesScreen(state, vm)
                AppDestination.SHIFTS -> ShiftsScreen(state, vm)
                AppDestination.SCHEDULE -> ScheduleScreen(state, vm)
                AppDestination.PRESENCE -> PresenceScreen(state, vm)
                AppDestination.REQUESTS -> RequestsScreen(state, vm)
                AppDestination.REPORTS -> ReportsScreen(state, vm)
                AppDestination.AUDIT -> AuditScreen(state)
                AppDestination.SETTINGS -> Placeholder("Cài đặt", "Cấu hình doanh nghiệp và thiết bị sẽ được nối vào Firebase Settings khi có yêu cầu nghiệp vụ cụ thể. Các chức năng đổi mật khẩu và xem nhật ký đã có trong menu cá nhân.", Icons.Default.Settings)
            }
        }
    }
    if(showAdd) EmployeeDialog(state,
        onDismiss={ if(!state.saving) showAdd=false },
        onSave={ employee, account -> vm.saveEmployee(employee, account) { showAdd=false } },
        onEnroll={ employee, deviceId, account -> vm.requestFingerprint(employee, deviceId, account) { showAdd=false } })
    employeeToEnroll?.let { e -> EnrollFingerprintDialog(e, state,
        onDismiss={ if(!state.saving) employeeToEnroll=null },
        onConfirm={ vm.requestFingerprint(e,it) { employeeToEnroll=null } }) }
    salaryEmployee?.let { e -> SalaryDialog(e, state, { salaryEmployee=null }) { amount -> vm.setSalary(e.id,amount) { salaryEmployee=null } } }
    employeeToRemove?.let { e -> AlertDialog(
        onDismissRequest={ if(!state.saving) employeeToRemove=null },
        title={ Text(if(retire) "Xóa nhân viên khỏi danh sách đang làm?" else "Xóa vân tay?") },
        text={ Column {
            Text("${e.code} • ${e.fullName}")
            Text(if(retire) "Hồ sơ chuyển sang Đã nghỉ. Lịch sử chấm công và phiếu lương vẫn giữ nguyên. Mẫu vân tay sẽ được yêu cầu xóa trên thiết bị." else "Xóa mẫu trên thiết bị ${e.fingerprintDeviceId}. Giữ hồ sơ và lương. Có thể đăng ký lại sau khi xóa thành công.")
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        } },
        confirmButton={ Button({ vm.remove(e.id,retire) { employeeToRemove=null } },enabled=!state.saving) { Text("Xác nhận xóa") } },
        dismissButton={ TextButton({ employeeToRemove=null },enabled=!state.saving) { Text("Hủy") } }
    ) }
    if (showChangePassword) ChangePasswordDialog(state, vm) { showChangePassword = false }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EmployeeHomeShell(state: MainUiState, vm: MainViewModel) {
    var selected by remember { mutableStateOf(EmployeeDestination.HOME) }
    var showAccountMenu by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    val icons = listOf(Icons.Default.Home, Icons.Default.FactCheck, Icons.Default.Description, Icons.Default.Person)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected.title) },
                actions = {
                    IconButton({ showAccountMenu = true }, enabled = !state.saving) {
                        Icon(Icons.Default.AccountCircle, "Menu cá nhân")
                    }
                    DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Đổi mật khẩu") },
                            onClick = { showChangePassword = true; showAccountMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Đăng xuất") },
                            onClick = { showAccountMenu = false; vm.signOut() }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                employeePrimaryDestinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = { Icon(icons[index], destination.title) },
                        label = { Text(destination.title, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = Brand, modifier = Modifier.padding(bottom = 8.dp)) }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (selected) {
                EmployeeDestination.HOME -> EmployeeHomeScreen(state, vm)
                EmployeeDestination.ATTENDANCE -> EmployeeAttendanceScreen(state, vm)
                EmployeeDestination.REQUESTS -> EmployeeRequestsScreen(state, vm)
                EmployeeDestination.PROFILE -> EmployeeProfileScreen(state, vm) { showChangePassword = true }
            }
        }
    }
    if (showChangePassword) ChangePasswordDialog(state, vm) { showChangePassword = false }
}

@Composable
private fun ChangePasswordDialog(state: MainUiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Đổi mật khẩu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu mới") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(confirm, { confirm = it }, label = { Text("Nhập lại mật khẩu") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (confirm.isNotBlank() && confirm != password) Text("Mật khẩu nhập lại chưa khớp", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.changePassword(password) { onDismiss() } },
                enabled = !state.saving && password.length >= 6 && password == confirm
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.saving) { Text("Hủy") } }
    )
}
@Composable
private fun Dashboard(state: MainUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Hôm nay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Nhân viên", state.employees.count { it.active }.toString(), Icons.Default.Groups, Modifier.weight(1f))
                Metric("Lượt gần đây", state.attendance.size.toString(), Icons.Default.Fingerprint, Modifier.weight(1f))
            }
        }
        item { Text("Chấm công mới nhất", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(state.attendance.take(8), key = { it.id }) { AttendanceRow(it) }
    }
}

@Composable private fun Metric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = Brand); Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label) } }
}

@Composable internal fun EmployeeList(employees: List<Employee>, commands: List<Map<String,Any>>, onEnroll: (Employee)->Unit, onSalary: (Employee)->Unit, onRemove: (Employee,Boolean)->Unit) {
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(employees.isEmpty()) item { Text("Chưa có nhân viên trong danh sách này") }
        items(employees,key={it.id}) { e ->
            val command=commands.firstOrNull { it["employeeId"] == e.id }
            val busy=command?.get("status") in listOf("REQUESTED","PROCESSING") || (command?.get("status")=="COMPLETED" && command["applied"]!=true)
            val hasTemplate=e.fingerprintTemplateId!=null || e.pendingTemplateId!=null || (command?.get("type")=="ENROLL_FINGERPRINT" && command["status"]=="FAILED")
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(e.fullName,fontWeight=FontWeight.Bold)
                Text("${e.code} • ${e.department}${if(!e.active) " • Đã nghỉ" else ""}")
                Text("Lương cơ bản: ${money(e.baseSalary)}")
                e.fingerprintTemplateId?.let { Text("Mẫu vân tay #$it",color=Brand) }
                command?.let { c ->
                    val label=when(c["status"]) {
                        "REQUESTED" -> "Đang chờ thiết bị — hãy bật máy và kết nối Wi-Fi"
                        "PROCESSING" -> "Thiết bị đang xử lý"
                        "COMPLETED" -> if(c["applied"]==true) "Hoàn tất" else "Thiết bị đã xong, đang đồng bộ"
                        "FAILED" -> "Thất bại — kiểm tra thiết bị; có thể xóa mẫu để làm lại"
                        else -> c["status"].toString()
                    }
                    Text("${if(c["type"]=="DELETE_FINGERPRINT") "Xóa vân tay" else "Đăng ký"}: $label",style=MaterialTheme.typography.bodySmall)
                }
                if(e.active) {
                    TextButton({onSalary(e)}) { Text("Thiết lập lương") }
                    if(!hasTemplate) FilledTonalButton({onEnroll(e)},enabled=!busy) { Text("Đăng ký vân tay") }
                }
                if(hasTemplate) TextButton({onRemove(e,false)},enabled=!busy) { Text("Xóa vân tay") }
                if(e.active) TextButton({onRemove(e,true)},enabled=!busy) { Text("Xóa nhân viên",color=MaterialTheme.colorScheme.error) }
            } }
        }
    }
}

@Composable private fun EnrollFingerprintDialog(employee: Employee, state: MainUiState, onDismiss: ()->Unit, onConfirm: (String)->Unit) {
    var deviceId by remember { mutableStateOf(employee.fingerprintDeviceId) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("Đăng ký vân tay") },text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("${employee.code} • ${employee.fullName}")
        OutlinedTextField(deviceId,{deviceId=it},label={Text("Mã thiết bị")},singleLine=true)
        Text("Sau khi gửi, đặt cùng một ngón tay lên cảm biến 2 lần.")
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ Button({onConfirm(deviceId)},enabled=!state.saving && deviceId.isNotBlank()) { Text("Gửi lệnh") } },dismissButton={ TextButton(onDismiss,enabled=!state.saving){Text("Hủy")} })
}
@Composable internal fun AttendanceList(attendance: List<Attendance>, modifier: Modifier = Modifier) = LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (attendance.isEmpty()) item { Text("Chưa có lượt chấm phù hợp") }
    items(attendance, key = { it.id }) { AttendanceRow(it) }
}

@Composable private fun AttendanceRow(item: Attendance) {
    val time = remember(item.timestamp) { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN")).format(item.timestamp.toDate()) }
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF16835F)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(item.employeeName.ifBlank { item.employeeId }, fontWeight = FontWeight.Bold); Text("$time • ${item.deviceId}", style = MaterialTheme.typography.bodySmall) }
        AssistChip({}, { Text(item.status) })
    } }
}

@Composable private fun Placeholder(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, tint = Brand, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, modifier = Modifier.padding(12.dp)) }
}

@Composable private fun EmployeeDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
    onSave: (Employee, EmployeeAccountInput?) -> Unit,
    onEnroll: (Employee, String, EmployeeAccountInput?) -> Unit
) {
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }; var deviceId by remember { mutableStateOf("GATE-01") }
    var createAccount by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }; var passwordConfirmation by remember { mutableStateOf("") }
    val employee=Employee(fullName=name.trim(),email=email.trim(),department=department.trim())
    val account = if (createAccount) EmployeeAccountInput(email.trim(), password, name.trim()) else null
    val accountReady = !createAccount || (
        email.trim().contains("@") && password.length >= 6 && password == passwordConfirmation
    )
    AlertDialog(onDismissRequest=onDismiss,title={Text("Thêm nhân viên")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Mã nhân viên được cấp tự động khi lưu (NV0001, NV0002…).")
        OutlinedTextField(name,{name=it},label={Text("Họ tên")},singleLine=true)
        OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true)
        OutlinedTextField(department,{department=it},label={Text("Phòng ban")},singleLine=true)
        OutlinedTextField(deviceId,{deviceId=it},label={Text("Mã thiết bị đăng ký")},singleLine=true)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=createAccount, onCheckedChange={ createAccount = it })
            Text("Tạo tài khoản đăng nhập cho nhân viên")
        }
        if (createAccount) {
            Text("Nhân viên sẽ đăng nhập bằng email và mật khẩu này.", style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(password,{password=it},label={Text("Mật khẩu (ít nhất 6 ký tự)")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(
                passwordConfirmation,
                {passwordConfirmation=it},
                label={Text("Nhập lại mật khẩu")},
                singleLine=true,
                isError=passwordConfirmation.isNotBlank() && passwordConfirmation != password,
                visualTransformation=PasswordVisualTransformation()
            )
            if (passwordConfirmation.isNotBlank() && passwordConfirmation != password) {
                Text("Mật khẩu nhập lại chưa khớp", color=MaterialTheme.colorScheme.error)
            }
        }
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ Column(horizontalAlignment=Alignment.End) {
        Button({onEnroll(employee,deviceId,account)},enabled=!state.saving && name.isNotBlank() && deviceId.isNotBlank() && accountReady) { Text("Lưu & đăng ký vân tay") }
        TextButton({onSave(employee,account)},enabled=!state.saving && name.isNotBlank() && accountReady) { Text("Chỉ lưu nhân viên") }
    } },dismissButton={TextButton(onDismiss,enabled=!state.saving){Text("Hủy")}})
}
