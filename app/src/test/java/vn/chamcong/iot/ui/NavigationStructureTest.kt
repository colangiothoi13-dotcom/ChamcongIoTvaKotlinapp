package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun adminPrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Tổng quan", "Công việc", "Tiện ích", "Phân ca", "Nhân viên"),
            adminPrimaryDestinations.map { it.title }
        )
    }

    @Test
    fun adminTaskHubKeepsEveryNonPrimaryAdminModuleReachable() {
        assertEquals(
            listOf("Đơn từ", "Chấm công", "Có mặt", "Thiết bị", "Phân ca", "Ca làm", "Lịch", "Lương", "Hiệu suất", "Báo cáo", "Bảng công tháng", "Nhật ký", "Phòng ban", "Thông báo", "Tiện ích nhân viên"),
            adminTaskDestinations.map { it.title }
        )
    }

    @Test
    fun employeePrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Trang chủ", "Việc của tôi", "Lịch làm việc", "Tiện ích", "Cá nhân"),
            employeePrimaryDestinations.map { it.title }
        )
    }
}
