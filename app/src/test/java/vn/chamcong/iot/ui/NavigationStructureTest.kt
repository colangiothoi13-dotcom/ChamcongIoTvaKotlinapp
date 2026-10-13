package vn.chamcong.iot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStructureTest {
    @Test
    fun adminPrimaryNavigationHasExactlyFiveItemsInApprovedOrder() {
        assertEquals(
            listOf("Tổng quan", "Công việc", "Danh mục", "Phân ca", "Nhân viên"),
            adminPrimaryDestinations.map { it.title }
        )
    }

    @Test
    fun adminTaskHubKeepsEveryNonPrimaryAdminModuleReachable() {
        assertEquals(
            listOf("Tổng quan", "Công việc", "Chấm công", "Có mặt", "Nhân viên", "Lịch làm việc", "Ca làm", "Đơn từ", "Phân ca", "Bảng lương", "Báo cáo & bảng công", "Bảng công tháng", "Hiệu suất", "Camera & khuôn mặt", "Thiết bị IoT", "Phòng ban", "Thông báo", "Tiện ích nhân viên", "Nhật ký"),
            adminTaskDestinations.map { it.title }
        )
        assertEquals(adminTaskDestinations.size, adminTaskDestinations.distinct().size)
        assertEquals(
            AppDestination.entries.toSet() - setOf(AppDestination.TASKS, AppDestination.SETTINGS),
            (adminPrimaryDestinations + adminTaskDestinations).toSet() - AppDestination.TASKS
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
