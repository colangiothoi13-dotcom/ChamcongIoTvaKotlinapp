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
import vn.chamcong.iot.model.Employee

private val Brand = Color(0xFF147D64)
private val Background = Color(0xFFF5F8F6)

@Composable
fun ChamCongApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, background = Background)) {
        if (!state.signedIn) LoginScreen(state.loading, state.error, vm::signIn)
        else HomeScreen(state, vm)
    }
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Background) {
        Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.widthIn(max = 440.dp), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Fingerprint, null, tint = Brand, modifier = Modifier.size(48.dp))
                    Text("Chấm công IoT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Đăng nhập tài khoản quản trị")
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mật khẩu") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button({ onLogin(email, password) }, Modifier.fillMaxWidth(), enabled = !loading && email.isNotBlank() && password.isNotBlank()) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Đăng nhập")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeScreen(state: MainUiState, vm: MainViewModel) {
    var selected by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var employeeToEnroll by remember { mutableStateOf<Employee?>(null) }
    var employeeToRemove by remember { mutableStateOf<Employee?>(null) }
    var retire by remember { mutableStateOf(false) }
    var salaryEmployee by remember { mutableStateOf<Employee?>(null) }
    var showRetired by remember { mutableStateOf(false) }
    val titles = listOf("Tổng quan", "Nhân viên", "Chấm công", "Lương", "Hiệu suất")
    Scaffold(
        topBar = { TopAppBar(title = { Text(titles[selected]) }, actions = { IconButton({ vm.signOut() }, enabled=!state.saving) { Icon(Icons.Default.Logout, "Đăng xuất") } }) },
        bottomBar = { NavigationBar {
            listOf(Icons.Default.Dashboard, Icons.Default.Groups, Icons.Default.FactCheck, Icons.Default.Payments, Icons.Default.Insights)
                .forEachIndexed { index, icon -> NavigationBarItem(selected == index, { selected = index }, { Icon(icon, titles[index]) }, label = { Text(titles[index], maxLines = 1) }) }
        } },
        floatingActionButton = { if (selected == 1) FloatingActionButton({ vm.clearError(); showAdd = true }) { Icon(Icons.Default.PersonAdd, "Thêm nhân viên") } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            state.error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color=Brand, modifier=Modifier.padding(bottom=8.dp)) }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (selected) {
                0 -> Dashboard(state)
                1 -> {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Hiện nhân viên đã nghỉ", Modifier.weight(1f))
                        Switch(showRetired, { showRetired=it })
                    }
                    EmployeeList(state.employees.filter { if(showRetired) !it.active else it.active }, state.commands,
                        onEnroll={ vm.clearError(); employeeToEnroll=it },
                        onSalary={ vm.clearError(); salaryEmployee=it },
                        onRemove={ e, all -> vm.clearError(); employeeToRemove=e; retire=all })
                }
                2 -> AttendanceList(state.attendance)
                3 -> PayrollScreen(state, vm)
                else -> Placeholder("Điểm chuyên cần", "Chưa tính KPI tự động. Công thức đề xuất: 70 × (ngày đi làm / ngày phải làm) + 30 × (ngày đúng giờ / ngày đi làm), tối đa 100 điểm. Cần thiết lập lịch làm và lấy đủ dữ liệu theo tháng; 50 lượt gần nhất chưa đủ để đánh giá.", Icons.Default.Insights)
            }
        }
    }
    if(showAdd) EmployeeDialog(state,
        onDismiss={ if(!state.saving) showAdd=false },
        onSave={ vm.saveEmployee(it) { showAdd=false } },
        onEnroll={ e, d -> vm.requestFingerprint(e,d) { showAdd=false } })
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

@Composable private fun EmployeeList(employees: List<Employee>, commands: List<Map<String,Any>>, onEnroll: (Employee)->Unit, onSalary: (Employee)->Unit, onRemove: (Employee,Boolean)->Unit) {
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(employees.isEmpty()) item { Text("Chưa có nhân viên trong danh sách này") }
        items(employees,key={it.id}) { e ->
            val command=commands.firstOrNull { it["employeeId"] == e.id && it["commandId"] == e.fingerprintDeviceId.ifBlank { "GATE-01" } }
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
@Composable private fun AttendanceList(attendance: List<Attendance>) = LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(attendance, key = { it.id }) { AttendanceRow(it) } }

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

@Composable private fun EmployeeDialog(state: MainUiState, onDismiss: ()->Unit, onSave: (Employee)->Unit, onEnroll: (Employee,String)->Unit) {
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }; var deviceId by remember { mutableStateOf("GATE-01") }
    val employee=Employee(fullName=name.trim(),email=email.trim(),department=department.trim())
    AlertDialog(onDismissRequest=onDismiss,title={Text("Thêm nhân viên")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Mã nhân viên được cấp tự động khi lưu (NV0001, NV0002…).")
        OutlinedTextField(name,{name=it},label={Text("Họ tên")},singleLine=true)
        OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true)
        OutlinedTextField(department,{department=it},label={Text("Phòng ban")},singleLine=true)
        OutlinedTextField(deviceId,{deviceId=it},label={Text("Mã thiết bị đăng ký")},singleLine=true)
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ Column(horizontalAlignment=Alignment.End) {
        Button({onEnroll(employee,deviceId)},enabled=!state.saving && name.isNotBlank() && deviceId.isNotBlank()) { Text("Lưu & đăng ký vân tay") }
        TextButton({onSave(employee)},enabled=!state.saving && name.isNotBlank()) { Text("Chỉ lưu nhân viên") }
    } },dismissButton={TextButton(onDismiss,enabled=!state.saving){Text("Hủy")}})
}