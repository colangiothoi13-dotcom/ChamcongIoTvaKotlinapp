package vn.chamcong.iot.model

import com.google.firebase.Timestamp

/** Raw snapshots retain omitted legacy fields; reading history never recalculates a payslip. */
data class PayrollRevisionSnapshot(
    val id: String,
    val payrollId: String,
    val revision: Long,
    val previous: Map<String, Any?>,
    val next: Map<String, Any?>,
    val actorId: String,
    val reason: String,
    val createdAt: Timestamp?
)

fun payrollHistoryDocumentId(payroll: Payroll): String {
    require(payroll.employeeId.isNotBlank() && '/' !in payroll.employeeId) { "Mã nhân viên không hợp lệ" }
    require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(payroll.month)) { "Tháng phiếu lương không hợp lệ" }
    return "${payroll.employeeId}_${payroll.month}"
}

fun payrollSnapshotNet(snapshot: Map<String, Any?>): Long? =
    (snapshot["baseSalary"] as? Number)?.toLong()?.let { base ->
        runCatching { Math.subtractExact(Math.addExact(base, (snapshot["bonus"] as? Number)?.toLong() ?: 0L),
            (snapshot["deduction"] as? Number)?.toLong() ?: 0L) }.getOrNull()
    }
