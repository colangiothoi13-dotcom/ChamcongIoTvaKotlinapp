package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun adminPrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Tổng quan", "Chấm công", "Nhân viên", "Đơn từ", "Tác vụ"),
            adminPrimaryDestinations.map { it.title }
        )
    }

    @Test
    fun adminTaskHubKeepsEveryNonPrimaryAdminModuleReachable() {
        assertEquals(
            listOf("Có mặt", "Thiết bị", "Phân ca", "Ca làm", "Lịch", "Lương", "Hiệu suất", "Báo cáo", "Bảng công tháng", "Nhật ký", "Phòng ban", "Thông báo", "Cài đặt"),
            adminTaskDestinations.map { it.title }
        )
    }

    @Test
    fun employeePrimaryNavigationHasExactlyFourItemsInApprovedOrder() {
        assertEquals(
            listOf("Trang chủ", "Chấm công của tôi", "Đơn từ", "Cá nhân"),
            employeePrimaryDestinations.map { it.title }
        )
    }
}
