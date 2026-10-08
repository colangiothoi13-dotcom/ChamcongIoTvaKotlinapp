package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class EmployeeResourceType { MEETING, REWARD, DOCUMENT }

/** Admin-published content with one explicit employee audience. */
data class EmployeeResource(
    val id: String = "",
    val type: String = EmployeeResourceType.MEETING.name,
    val title: String = "",
    val body: String = "",
    val audience: String = "ALL",
    val eventDate: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val location: String = "",
    val url: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val createdBy: String = ""
)
