package vn.chamcong.iot.ui.dashboard

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.AppNotification

class AdminNotificationPresentationTest {
    @Test
    fun broadcastDeliveriesShareOnePreviewAndPreserveUnreadIds() {
        val groups = groupAdminNotifications(listOf(
            notification("first", reference = "meeting", recipient = "employee-a", read = true),
            notification("second", reference = "meeting", recipient = "employee-b"),
            notification("third", reference = "meeting", recipient = "employee-c")
        ))

        assertEquals(1, groups.size)
        assertEquals(3, groups.single().recipientCount)
        assertEquals(2, groups.single().unreadCount)
        assertEquals(listOf("second", "third"), groups.single().unreadIds)
    }

    @Test
    fun separateBroadcastsAndDifferentContentNeverDisappear() {
        val groups = groupAdminNotifications(listOf(
            notification("first", reference = "meeting-a"),
            notification("second", reference = "meeting-b"),
            notification("edited", reference = "meeting-a").copy(body = "Changed message")
        ))

        assertEquals(3, groups.size)
    }

    @Test
    fun requestsAndLegacyAnnouncementsWithoutReferenceRemainSeparate() {
        val groups = groupAdminNotifications(listOf(
            notification("request-a", reference = "request").copy(type = "REQUEST_RESULT"),
            notification("request-b", reference = "request").copy(type = "REQUEST_RESULT"),
            notification("legacy-a", reference = null),
            notification("legacy-b", reference = "")
        ))

        assertEquals(4, groups.size)
    }

    @Test
    fun newestMessagesComeFirstIncludingTimestampsWithinTheSameSecond() {
        val groups = groupAdminNotifications(listOf(
            notification("older", reference = "older").copy(createdAt = Timestamp(100, 0)),
            notification("latest", reference = "latest").copy(createdAt = Timestamp(101, 2)),
            notification("recent", reference = "recent").copy(createdAt = Timestamp(101, 1))
        ))

        assertEquals(listOf("latest", "recent", "older"), groups.map { it.latest.id })
    }

    @Test
    fun addingNewNotificationsKeepsTheExpandedRequestKeyStable() {
        val request = notification("request", reference = "request").copy(type = "REQUEST_RESULT")
        val originalKey = groupAdminNotifications(listOf(request)).single().key
        val withNewerMessage = groupAdminNotifications(listOf(
            notification("new", reference = "new").copy(createdAt = Timestamp(101, 0)),
            request
        ))

        assertEquals(originalKey, withNewerMessage.single { it.latest.id == request.id }.key)
    }

    private fun notification(
        id: String,
        reference: String?,
        recipient: String? = null,
        read: Boolean = false
    ) = AppNotification(
        id = id,
        type = "ANNOUNCEMENT",
        title = "Meeting",
        body = "Join this afternoon",
        referenceId = reference,
        recipientEmployeeId = recipient,
        createdAt = Timestamp(100, 0),
        read = read
    )
}
