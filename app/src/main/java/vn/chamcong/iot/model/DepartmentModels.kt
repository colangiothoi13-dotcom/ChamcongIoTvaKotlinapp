package vn.chamcong.iot.model

/** A department record supplied to the department management screen. */
data class Department(
    val id: String = "",
    val name: String = "",
    val active: Boolean = true
)
