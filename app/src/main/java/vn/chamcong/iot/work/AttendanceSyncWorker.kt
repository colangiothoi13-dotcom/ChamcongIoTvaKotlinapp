package vn.chamcong.iot.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AttendanceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        val payload = mapOf(
            "eventId" to eventId,
            "syncedAt" to Timestamp.now(),
            "source" to "android-workmanager"
        )
        FirebaseFirestore.getInstance().collection("syncReceipts").document(eventId).set(payload).await()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
