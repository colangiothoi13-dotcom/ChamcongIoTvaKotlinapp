package vn.chamcong.iot.domain

import vn.chamcong.iot.model.EmployeeResource
import vn.chamcong.iot.model.EmployeeResourceType
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

private val resourceAudiencePattern = Regex("(?:DEPARTMENT|EMPLOYEE):[^/\\s:]+")
private val resourceDatePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val resourceTimePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

fun validateEmployeeResource(resource: EmployeeResource) {
    require(resource.id.isEmpty() || (resource.id.length <= 1500 && '/' !in resource.id)) { "Mã nội dung không hợp lệ" }
    require(resource.type in EmployeeResourceType.entries.map { it.name }) { "Loại tiện ích không hợp lệ" }
    require(resource.title.isNotBlank() && resource.title.length <= 120) { "Tiêu đề phải từ 1–120 ký tự" }
    require(resource.body.isNotBlank() && resource.body.length <= 4000) { "Nội dung phải từ 1–4.000 ký tự" }
    require(resource.audience == "ALL" || resourceAudiencePattern.matches(resource.audience)) { "Chọn đối tượng nhận nội dung" }
    require(resource.location.length <= 200) { "Địa điểm tối đa 200 ký tự" }
    require(resource.url.length <= 2048) { "Đường dẫn tối đa 2.048 ký tự" }
    if (resource.url.isNotBlank()) {
        val uri = runCatching { URI(resource.url) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Đường dẫn phải dùng HTTPS và có tên miền hợp lệ"
        }
    }
    if (resource.eventDate.isNotBlank()) {
        require(resourceDatePattern.matches(resource.eventDate) && runCatching { LocalDate.parse(resource.eventDate) }.isSuccess) {
            "Ngày phải hợp lệ theo dạng yyyy-MM-dd"
        }
    }
    when (resource.type) {
        EmployeeResourceType.MEETING.name -> {
            require(resource.eventDate.isNotBlank()) { "Nhập ngày họp" }
            require(resourceTimePattern.matches(resource.startTime) && resourceTimePattern.matches(resource.endTime)) {
                "Giờ họp phải có dạng HH:mm"
            }
            require(LocalTime.parse(resource.endTime).isAfter(LocalTime.parse(resource.startTime))) {
                "Giờ kết thúc phải sau giờ bắt đầu"
            }
            require(resource.location.isNotBlank()) { "Nhập địa điểm hoặc hình thức họp" }
        }
        EmployeeResourceType.REWARD.name -> {
            require(resource.eventDate.isNotBlank()) { "Nhập ngày khen thưởng" }
            require(resource.audience.startsWith("EMPLOYEE:")) { "Khen thưởng phải chọn một nhân viên cụ thể" }
        }
        EmployeeResourceType.DOCUMENT.name -> require(resource.url.isNotBlank()) { "Nhập đường dẫn tài liệu HTTPS" }
    }
}

fun employeeResourceAudiences(employeeId: String, departmentId: String): List<String> {
    require(employeeId.isNotBlank() && '/' !in employeeId) { "Chưa liên kết nhân viên" }
    return buildList {
        add("ALL")
        add("EMPLOYEE:$employeeId")
        if (departmentId.isNotBlank()) add("DEPARTMENT:$departmentId")
    }
}
