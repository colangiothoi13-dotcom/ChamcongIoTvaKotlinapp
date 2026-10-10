package vn.chamcong.iot.ui.workitems

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.google.firebase.Timestamp
import vn.chamcong.iot.model.WorkItem
import vn.chamcong.iot.ui.MainUiState
import vn.chamcong.iot.ui.PreviewScreen
import vn.chamcong.iot.ui.previewUiState
import vn.chamcong.iot.ui.rememberPreviewViewModel
import java.time.Instant
import java.util.Date

/** Fixtures cover long labels, overdue work and reviews without opening Firebase. */
private fun sampleWorkState(employee: Boolean = false): MainUiState {
    val state = previewUiState()
    val now = Instant.now()
    val item = WorkItem(id = "work-preview-1", title = "Kiểm tra máy A và lập biên bản an toàn vận hành",
        description = "Kiểm tra nguồn điện, cảm biến và tình trạng hoạt động của máy A trong ca làm.",
        requiredResult = "Gửi biên bản kiểm tra, ghi rõ các hạng mục đã kiểm tra và vấn đề cần khắc phục.",
        assigneeId = "nv-01", assigneeName = "Nguyễn Văn An", assignedByName = "Quản lý kỹ thuật",
        startAt = Timestamp(Date.from(now.minusSeconds(4 * 3600))), deadline = Timestamp(Date.from(now.plusSeconds(2 * 3600))))
    return state.copy(userProfile = state.userProfile?.copy(role = if (employee) "EMPLOYEE" else "ADMIN", employeeId = "nv-01"),
        workItems = listOf(item, item.copy(id = "work-preview-2", title = "Rà soát tồn kho linh kiện", status = "IN_PROGRESS",
            deadline = Timestamp(Date.from(now.minusSeconds(3600))), priority = "HIGH"),
            item.copy(id = "work-preview-3", title = "Kiểm tra nhật ký bảo trì", status = "PENDING_REVIEW", resultReport = "Đã rà soát đầy đủ nhật ký bảo trì và gửi biên bản.")))
}

@Preview(name = "Công việc · điện thoại", widthDp = 375, heightDp = 812)
@Preview(name = "Công việc · ngang", widthDp = 812, heightDp = 375)
@Preview(name = "Công việc · chữ lớn, tối", widthDp = 375, heightDp = 812, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WorkItemsPreview() {
    val state = remember { sampleWorkState() }
    val vm = rememberPreviewViewModel(state)
    PreviewScreen { WorkItemsScreen(state, vm) }
}

@Preview(name = "Việc của tôi", widthDp = 375, heightDp = 812)
@Composable
private fun MyWorkItemsPreview() {
    val state = remember { sampleWorkState(employee = true) }
    val vm = rememberPreviewViewModel(state)
    PreviewScreen { WorkItemsScreen(state, vm) }
}
