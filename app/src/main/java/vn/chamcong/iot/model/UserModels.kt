package vn.chamcong.iot.model

import com.google.firebase.Timestamp

enum class UserRole { ADMIN, EMPLOYEE }

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = UserRole.EMPLOYEE.name,
    val active: Boolean = true,
    val updatedAt: Timestamp = Timestamp.now()
)
