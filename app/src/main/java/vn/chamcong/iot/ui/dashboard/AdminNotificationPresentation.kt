package vn.chamcong.iot.ui.dashboard

import vn.chamcong.iot.model.AppNotification

internal data class AdminNotificationGroup(
    val key: String,
    val notifications: List<AppNotification>
) {
    val latest: AppNotification get() = notifications.first()
    val unreadCount: Int get() = notifications.count { !it.read }
    val unreadIds: List<String>
        get() = notifications.filter { !it.read }.map { it.id }.filter(String::isNotBlank).distinct()
    val recipientCount: Int
        get() = notifications.mapNotNull { it.recipientEmployeeId?.takeIf(String::isNotBlank) }.distinct().size
}

/** A broadcast creates one delivery per employee. Keep its content together in Admin's overview. */
internal fun groupAdminNotifications(notifications: List<AppNotification>): List<AdminNotificationGroup> {
    val newestFirst = notifications.sortedWith(
        compareByDescending<AppNotification> { it.createdAt.seconds }
            .thenByDescending { it.createdAt.nanoseconds }
    )
    val duplicateIds = newestFirst.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    return newestFirst.withIndex().groupBy { (index, notification) ->
        val reference = notification.referenceId?.takeIf(String::isNotBlank)
        if (notification.type == "ANNOUNCEMENT" && reference != null) {
            // Include the content so legacy rows with a reused reference never hide different messages.
            "announcement:" + listOf(reference, notification.title, notification.body)
                .joinToString("") { "${it.length}:$it" }
        } else {
            // Requests and legacy announcements must remain individual even when their text matches.
            val rowId = notification.id.takeIf { it.isNotBlank() && it !in duplicateIds } ?: "row-$index"
            "notification:$rowId"
        }
    }.map { (key, rows) -> AdminNotificationGroup(key, rows.map { it.value }) }
}
