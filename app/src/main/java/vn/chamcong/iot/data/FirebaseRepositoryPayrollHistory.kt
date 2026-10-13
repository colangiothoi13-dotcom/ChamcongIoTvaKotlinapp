package vn.chamcong.iot.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import vn.chamcong.iot.model.Payroll
import vn.chamcong.iot.model.PayrollRevisionSnapshot
import vn.chamcong.iot.model.payrollHistoryDocumentId

/** Admin exports become ready only after an authoritative server snapshot. */
fun FirebaseRepository.observeServerPayroll(month: String): Flow<List<Payroll>> = callbackFlow {
    val listener = db.collection("payroll").whereEqualTo("month", month)
        .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) close(error)
            else if (snapshot != null && !snapshot.metadata.isFromCache) {
                try { trySend(snapshot.documents.mapNotNull { it.toObject(Payroll::class.java) }) }
                catch (invalid: Exception) { close(invalid) }
            }
        }
    awaitClose { listener.remove() }
}

suspend fun FirebaseRepository.fetchPayrollRevisionHistory(payroll: Payroll): List<PayrollRevisionSnapshot> {
    val payrollId = payrollHistoryDocumentId(payroll)
    val ref = db.collection("payroll").document(payrollId).collection("revisions")
    var query: Query = ref.orderBy("revision", Query.Direction.DESCENDING).limit(200)
    val rows = mutableListOf<PayrollRevisionSnapshot>()
    while (true) {
        val page = query.get(Source.SERVER).await()
        for (document in page.documents) {
            @Suppress("UNCHECKED_CAST")
            val previous = document.data?.get("previous") as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val next = document.data?.get("next") as? Map<String, Any?>
            val revision = (document.get("revision") as? Number)?.toLong()
            require(previous != null && next != null && revision != null && revision > 0 && document.getString("payrollId") == payrollId) {
                "Lịch sử phiếu lương có dữ liệu không hợp lệ; không thể hiển thị đầy đủ."
            }
            rows += PayrollRevisionSnapshot(document.id, payrollId, revision, previous.toMap(), next.toMap(),
                document.getString("actorId").orEmpty(), document.getString("reason").orEmpty(), document.get("createdAt") as? Timestamp)
        }
        if (page.size() < 200) return rows
        require(rows.size < 5000) { "Lịch sử có quá nhiều phiên bản; chưa tải đầy đủ." }
        query = ref.orderBy("revision", Query.Direction.DESCENDING).startAfter(page.documents.last()).limit(200)
    }
}
