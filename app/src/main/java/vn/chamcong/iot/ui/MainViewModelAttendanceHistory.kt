// Chức năng: Tải từng trang lịch sử chấm công và hỗ trợ tải lại khi gặp lỗi.
package vn.chamcong.iot.ui

import com.google.firebase.firestore.DocumentSnapshot
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import vn.chamcong.iot.data.*
import vn.chamcong.iot.model.Attendance
import java.time.LocalDate

private const val ATTENDANCE_PAGE_SIZE = 500L
private const val MAX_REPORT_ATTENDANCE_ROWS = 5_000

internal data class AttendanceHistoryRequest(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val employeeId: String?,
    val key: String
)

/** Loads historical attendance on demand for a report instead of keeping a
 * realtime listener over the whole Firestore collection. */
fun MainViewModel.loadAttendanceRange(
    startDate: LocalDate,
    endDate: LocalDate,
    employeeId: String? = null,
    force: Boolean = false
) {
    require(!endDate.isBefore(startDate)) { "Khoảng ngày chấm công không hợp lệ" }
    val cleanEmployeeId = employeeId?.trim()?.takeIf(String::isNotBlank)
    val key = "$startDate|$endDate|${cleanEmployeeId.orEmpty()}"
    val request = AttendanceHistoryRequest(startDate, endDate, cleanEmployeeId, key)
    lastAttendanceHistoryRequest = request
    if (!force && (
        attendanceHistoryRequestedKey == key ||
            _state.value.attendanceHistoryQueryKey == key
        )
    ) return
    attendanceHistoryJob?.cancel()
    attendanceHistoryRequestedKey = key
    _state.update {
        it.copy(
            historicalAttendance = emptyList(),
            attendanceHistoryLoading = true,
            attendanceHistoryQueryKey = null,
            attendanceHistoryError = null,
            attendanceHistoryTruncated = false,
            attendanceHistoryLoadedCount = 0
        )
    }
    attendanceHistoryJob = viewModelScope.launch {
        try {
            val loaded = mutableListOf<Attendance>()
            var cursor: DocumentSnapshot? = null
            var truncated = false
            while (loaded.size < MAX_REPORT_ATTENDANCE_ROWS) {
                val remaining = MAX_REPORT_ATTENDANCE_ROWS - loaded.size
                val pageSize = minOf(ATTENDANCE_PAGE_SIZE, remaining.toLong())
                val page = repository.fetchAttendancePage(
                    startDate = startDate,
                    endDate = endDate,
                    employeeId = cleanEmployeeId,
                    pageSize = pageSize,
                    after = cursor
                )
                loaded += page.rows
                val next = page.nextCursor
                if (next == null || page.rows.isEmpty() || next.id == cursor?.id) break
                cursor = next
                if (page.rows.size < pageSize) break
            }
            // Reaching the cap alone does not mean rows were omitted:
            // check one row after the final cursor before marking truncated.
            if (loaded.size == MAX_REPORT_ATTENDANCE_ROWS && cursor != null) {
                val overflowPage = repository.fetchAttendancePage(
                    startDate = startDate,
                    endDate = endDate,
                    employeeId = cleanEmployeeId,
                    pageSize = 1,
                    after = cursor
                )
                truncated = overflowPage.rows.isNotEmpty()
            }
            if (attendanceHistoryRequestedKey != key) return@launch
            val result = loaded.take(MAX_REPORT_ATTENDANCE_ROWS)
            attendanceHistoryRequestedKey = null
            _state.update {
                it.copy(
                    historicalAttendance = result,
                    attendanceHistoryLoading = false,
                    attendanceHistoryQueryKey = key,
                    attendanceHistoryError = null,
                    attendanceHistoryTruncated = truncated,
                    attendanceHistoryLoadedCount = result.size
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (attendanceHistoryRequestedKey != key) return@launch
            attendanceHistoryRequestedKey = null
            _state.update {
                it.copy(
                    historicalAttendance = emptyList(),
                    attendanceHistoryLoading = false,
                    attendanceHistoryQueryKey = null,
                    attendanceHistoryError = userFacingErrorMessage(error),
                    attendanceHistoryTruncated = false,
                    attendanceHistoryLoadedCount = 0
                )
            }
        } finally {
            if (isActive) attendanceHistoryJob = null
        }
    }
}

fun MainViewModel.retryAttendanceRange() {
    lastAttendanceHistoryRequest?.let { request ->
        loadAttendanceRange(request.startDate, request.endDate, request.employeeId, force = true)
    }
}

/** Loads one or two ordered pages so the first tap reveals rows older than
 * the realtime 500-row window; later taps append one more page. */
fun MainViewModel.loadMoreEmployeeAttendance() {
    val employeeId = employeeAttendanceHistoryEmployeeId
        ?: _state.value.currentEmployee?.id?.takeIf(String::isNotBlank)
        ?: return
    if (employeeAttendanceHistoryJob?.isActive == true || !employeeAttendanceHistoryHasMore) return
    val after = employeeAttendanceHistoryCursor
    val pagesToLoad = if (after == null) 2 else 1
    employeeAttendanceHistoryJob = viewModelScope.launch {
        _state.update { it.copy(employeeAttendanceHistoryLoading = true, error = null) }
        try {
            val loaded = mutableListOf<Attendance>()
            var cursor = after
            var hasMore = true
            var pageCount = 0
            while (pageCount < pagesToLoad && hasMore) {
                val page = repository.fetchEmployeeAttendancePage(
                    employeeId = employeeId,
                    pageSize = ATTENDANCE_PAGE_SIZE,
                    after = cursor
                )
                loaded += page.rows
                cursor = page.nextCursor
                hasMore = page.rows.size >= ATTENDANCE_PAGE_SIZE && page.nextCursor != null
                pageCount++
            }
            if (employeeAttendanceHistoryEmployeeId != employeeId) return@launch
            employeeAttendanceHistoryCursor = cursor
            employeeAttendanceHistoryHasMore = hasMore
            _state.update {
                it.copy(
                    employeeAttendanceHistory = (it.employeeAttendanceHistory + loaded)
                        .distinctBy { row ->
                            row.id.ifBlank {
                                "${row.employeeId}|${row.timestamp.seconds}|${row.timestamp.nanoseconds}|${row.type}"
                            }
                        },
                    employeeAttendanceHistoryLoading = false,
                    employeeAttendanceHistoryHasMore = hasMore
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update { it.copy(employeeAttendanceHistoryLoading = false, error = userFacingErrorMessage(error)) }
        } finally {
            if (isActive) employeeAttendanceHistoryJob = null
        }
    }
}
