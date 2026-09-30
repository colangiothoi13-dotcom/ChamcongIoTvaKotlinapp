package vn.chamcong.iot.model

import com.google.firebase.Timestamp

/** A sent announcement and its delivery summary. A null target means all employees. */
data class Announcement(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val targetDepartment: String? = null,
    val recipientCount: Int = 0,
    val sentAt: Timestamp? = null
)
