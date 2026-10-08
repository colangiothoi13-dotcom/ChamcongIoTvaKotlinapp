package vn.chamcong.iot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import vn.chamcong.iot.model.EmployeeResource

class EmployeeResourceRulesTest {
    private val meeting = EmployeeResource(
        title = "Họp tuần", body = "Cập nhật kế hoạch", audience = "DEPARTMENT:d1",
        eventDate = "2026-10-12", startTime = "09:00", endTime = "10:00", location = "Phòng họp"
    )

    @Test fun meetingRequiresValidCalendarDateAndAnIncreasingTimeWindow() {
        validateEmployeeResource(meeting)
        listOf(
            meeting.copy(eventDate = "2026-02-29"),
            meeting.copy(eventDate = "2026-10-32"),
            meeting.copy(startTime = "9:00"),
            meeting.copy(endTime = "09:00"),
            meeting.copy(startTime = "22:00", endTime = "01:00"),
            meeting.copy(location = " ")
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { validateEmployeeResource(invalid) } }
        validateEmployeeResource(meeting.copy(eventDate = "2028-02-29"))
    }

    @Test fun documentsRequireHttpsWithoutEmbeddedCredentials() {
        val document = EmployeeResource(type = "DOCUMENT", title = "Quy định", body = "Nội quy công ty", url = "https://example.com/policy.pdf")
        validateEmployeeResource(document)
        listOf("", "http://example.com/file", "javascript:alert(1)", "https:///file", "https://user:secret@example.com/file").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { validateEmployeeResource(document.copy(url = url)) }
        }
    }

    @Test fun rewardsMustIdentifyAnEmployeeAndDate() {
        val reward = EmployeeResource(type = "REWARD", title = "Hoàn thành tốt", body = "Đạt mục tiêu", eventDate = "2026-10-08", audience = "EMPLOYEE:e1")
        validateEmployeeResource(reward)
        listOf(reward.copy(audience = "ALL"), reward.copy(audience = "DEPARTMENT:d1"), reward.copy(eventDate = "")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { validateEmployeeResource(invalid) }
        }
    }

    @Test fun employeeQueryAudiencesOnlyIncludeOwnIdentityAndCurrentDepartment() {
        assertEquals(listOf("ALL", "EMPLOYEE:e1", "DEPARTMENT:d1"), employeeResourceAudiences("e1", "d1"))
        assertEquals(listOf("ALL", "EMPLOYEE:e1"), employeeResourceAudiences("e1", ""))
        assertThrows(IllegalArgumentException::class.java) { employeeResourceAudiences("", "d1") }
        listOf("EMPLOYEE:", "DEPARTMENT:", "EMPLOYEE:e1/e2", "EMPLOYEE:e1:e2", "DEPARTMENT: d1").forEach { audience ->
            assertThrows(IllegalArgumentException::class.java) { validateEmployeeResource(meeting.copy(audience = audience)) }
        }
    }
}
