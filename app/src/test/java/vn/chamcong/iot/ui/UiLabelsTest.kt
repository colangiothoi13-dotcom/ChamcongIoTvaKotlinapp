package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLabelsTest {
    @Test
    fun translatesAttendanceStatusesForScreens() {
        assertEquals("Bình thường", attendanceStatusLabel("NORMAL"))
        assertEquals("Có mặt", attendanceStatusLabel("PRESENT"))
        assertEquals("Đúng giờ", attendanceStatusLabel("ON_TIME"))
        assertEquals("Đi trễ", attendanceStatusLabel("LATE"))
        assertEquals("Chưa chấm ra", attendanceStatusLabel("MISSING_CHECK_OUT"))
        assertEquals("Quét trùng", attendanceResolutionLabel("DUPLICATE"))
    }

    @Test
    fun translatesAuditActionsAndTargetTypes() {
        assertEquals("Cập nhật ca", auditActionLabel("SHIFT_UPDATE"))
        assertEquals("Ca làm", auditTargetTypeLabel("shift"))
        assertEquals("Điều chỉnh chấm công", auditTargetTypeLabel("attendanceAdjustment"))
    }

    @Test
    fun translatesRequestAndDeviceStatuses() {
        assertEquals("Chờ duyệt", requestStatusLabel("PENDING"))
        assertEquals("Đang hoạt động", deviceStatusLabel("ONLINE"))
        assertEquals("Mất kết nối", deviceStatusLabel("OFFLINE"))
    }

    @Test
    fun auditPresentationDoesNotExposeInternalIdentifiers() {
        assertEquals("Cập nhật ca • Ca làm", auditLogTitle("SHIFT_UPDATE", "shift"))
        assertEquals("colangio", auditActorLabel("colangio"))
        assertEquals("Người dùng không xác định", auditActorLabel(""))
    }

    @Test
    fun permissionErrorsAreTranslatedForTheAdminScreen() {
        assertEquals(
            "Tài khoản chưa có quyền đọc dữ liệu. Hãy kiểm tra vai trò ADMIN, trạng thái active và Firestore Rules.",
            userFacingErrorMessage(Throwable("PERMISSION_DENIED: Missing or insufficient permissions."))
        )
    }

    @Test
    fun buildingIndexErrorsExplainThatTheUserCanWaitAndReload() {
        val error = Throwable(
            "FAILED_PRECONDITION: The query requires an index. " +
                "That index is currently building and cannot be used yet. See its status here: " +
                "https://console.firebase.google.com/project/example/firestore/indexes"
        )
        val expected = "Firestore đang tạo chỉ mục dữ liệu. Hãy đợi vài phút rồi chọn Tải lại dữ liệu."

        assertEquals(expected, userFacingErrorMessage(error))
        assertEquals(expected, userFacingErrorMessage(IllegalStateException("Không tải được tổng quan", error)))
    }

    @Test
    fun missingIndexErrorsExplainThatTheIndexMustBeDeployed() {
        val error = Throwable(
            "FAILED_PRECONDITION: The query requires an index. You can create it here: " +
                "https://console.firebase.google.com/project/example/firestore/indexes"
        )
        val expected = "Truy vấn cần chỉ mục Firestore. Hãy triển khai chỉ mục rồi chọn Tải lại dữ liệu."

        assertEquals(expected, userFacingErrorMessage(error))
        assertEquals(expected, userFacingErrorMessage(IllegalStateException("Không tải được tổng quan", error)))
    }

    @Test
    fun unrelatedPreconditionAndGenericErrorsKeepTheirMessages() {
        val message = "FAILED_PRECONDITION: This operation requires an active transaction."

        assertEquals(message, userFacingErrorMessage(Throwable(message)))
        assertEquals("Không có kết nối mạng", userFacingErrorMessage(Throwable("Không có kết nối mạng")))
        assertEquals("Đã xảy ra lỗi, vui lòng thử lại.", userFacingErrorMessage(Throwable()))
    }
}
