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
}
