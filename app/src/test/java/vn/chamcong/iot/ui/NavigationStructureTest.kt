package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun adminPrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Tổng quan", "Tác vụ", "Đơn từ", "Phân ca", "Nhân viên"),
            adminPrimaryDestinations.map { it.title }
        )
    }

    @Test
    fun adminTaskHubKeepsEveryNonPrimaryAdminModuleReachable() {
        assertEquals(
            listOf("Chấm công", "Thiết bị", "Có mặt", "Ca làm", "Lịch", "Lương", "Hiệu suất", "Báo cáo", "Nhật ký", "Cài đặt"),
            adminTaskDestinations.map { it.title }
        )
    }

    @Test
    fun employeePrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Trang chủ", "Chấm công của tôi", "Đơn từ", "Lương", "Cá nhân"),
            employeePrimaryDestinations.map { it.title }
        )
    }
}
