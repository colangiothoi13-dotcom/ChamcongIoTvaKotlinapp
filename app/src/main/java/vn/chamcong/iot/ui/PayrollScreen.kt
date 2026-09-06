package vn.chamcong.iot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.chamcong.iot.model.Employee
import java.text.NumberFormat
import java.util.Locale
import java.time.YearMonth

internal fun money(value: Long): String = NumberFormat.getNumberInstance(Locale("vi","VN")).format(value) + " đ"

@Composable internal fun SalaryDialog(e: Employee, state: MainUiState, dismiss: ()->Unit, save: (Long)->Unit) {
    var value by remember(e.id) { mutableStateOf(e.baseSalary.toString()) }
    val amount=value.toLongOrNull()
    AlertDialog(onDismissRequest={if(!state.saving) dismiss()},title={Text("Thiết lập lương")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("${e.code} • ${e.fullName}")
        MoneyField("Lương cơ bản / tháng (đ)",value,{value=it})
        Text("Mức mới dùng cho phiếu lương tạo sau này. Phiếu đã lưu giữ nguyên.")
        state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    } },confirmButton={Button({save(amount!!)},enabled=!state.saving && amount!=null && amount in 0..1000000000000L){Text("Lưu mức lương")}},dismissButton={TextButton(dismiss,enabled=!state.saving){Text("Hủy")}})
}

@Composable private fun MoneyField(label: String, value: String, changed: (String)->Unit) {
    OutlinedTextField(value,{if(it.all { c -> c in '0'..'9' } && it.length<=13) changed(it)},label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
}

@Composable internal fun PayrollScreen(state: MainUiState, vm: MainViewModel) {
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    var selected by remember { mutableStateOf<Employee?>(null) }
    var settings by remember { mutableStateOf<Employee?>(null) }
    var picker by remember { mutableStateOf(false) }
    val validMonth=Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(month,{month=it},label={Text("Tháng lương (yyyy-MM)")},singleLine=true,isError=!validMonth)
        Text("Thực lĩnh = lương cơ bản + thưởng − khấu trừ. Không tự quy đổi theo ngày công.",style=MaterialTheme.typography.bodySmall)
        Button({vm.clearError();picker=true},enabled=validMonth && !state.saving){Text("Lập phiếu lương / thiết lập lương")}
        val rows=state.payroll.filter{it.month==month}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(rows.isEmpty()) item{Text("Chưa có phiếu lương đã lưu trong tháng này")}
            items(rows,key={it.employeeId+it.month}){p ->
                val e=state.employees.firstOrNull{it.id==p.employeeId}
                Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("${p.employeeCode.ifBlank{e?.code.orEmpty()}} • ${p.employeeName.ifBlank{e?.fullName ?: p.employeeId}}${if(e?.active==false) " • Đã nghỉ" else ""}")
                    Text("Cơ bản: ${money(p.baseSalary)}")
                    Text("Thưởng: ${money(p.bonus)} • Khấu trừ: ${money(p.deduction)}")
                    Text("Thực lĩnh: ${money(p.netSalary)}",style=MaterialTheme.typography.titleMedium)
                }}
            }
        }
    }
    if(picker) AlertDialog(onDismissRequest={picker=false},title={Text("Chọn nhân viên")},text={LazyColumn {
        items(state.employees,key={it.id}){e -> Column {
            Text("${e.code} • ${e.fullName}${if(!e.active) " • Đã nghỉ" else ""}")
            Text("Lương cơ bản: ${money(e.baseSalary)}")
            Row {
                if(e.active) TextButton({picker=false;settings=e}){Text("Đặt lương")}
                TextButton({picker=false;selected=e}){Text("Lập phiếu")}
            }
        }}
    }},confirmButton={TextButton({picker=false}){Text("Đóng")}})
    settings?.let{e -> SalaryDialog(e,state,{settings=null}){amount -> vm.setSalary(e.id,amount){settings=null}}}
    selected?.let{e ->
        var bonus by remember(e.id){mutableStateOf("0")}
        var deduction by remember(e.id){mutableStateOf("0")}
        val b=bonus.toLongOrNull(); val d=deduction.toLongOrNull()
        val current=state.employees.firstOrNull{it.id==e.id} ?: e
        AlertDialog(onDismissRequest={if(!state.saving)selected=null},title={Text("Phiếu lương $month")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("${current.code} • ${current.fullName}")
            Text("Lương cơ bản: ${money(current.baseSalary)}")
            MoneyField("Thưởng (đ)",bonus,{bonus=it}); MoneyField("Khấu trừ (đ)",deduction,{deduction=it})
            if(b!=null && d!=null) Text("Thực lĩnh: ${money(current.baseSalary+b-d)}")
            Text("Phiếu được lưu cố định để giữ lịch sử. Mỗi nhân viên có một phiếu cho mỗi tháng.")
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={Button({vm.savePayroll(e.id,month,b!!,d!!){selected=null}},enabled=!state.saving && b!=null && d!=null && b in 0..1000000000000L && d in 0..1000000000000L){Text("Lưu phiếu")}},dismissButton={TextButton({selected=null},enabled=!state.saving){Text("Hủy")}})
    }
}
