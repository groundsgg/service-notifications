package gg.grounds.notifications.outbox

import gg.grounds.notifications.live.NotificationLiveEvent
import gg.grounds.notifications.live.NotificationLiveEventPublisher
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

@Alternative
@Priority(1)
@ApplicationScoped
class RecordingNotificationLiveEventPublisher : NotificationLiveEventPublisher {
    val events: MutableList<NotificationLiveEvent> = mutableListOf()
    var publishResult: Boolean = true
    var publishException: RuntimeException? = null
    val rejectedUserIds: MutableSet<String> = mutableSetOf()

    override fun publish(event: NotificationLiveEvent): Boolean {
        events.add(event)
        publishException?.let { throw it }
        if (event.userId in rejectedUserIds) {
            return false
        }
        return publishResult
    }

    fun reset() {
        events.clear()
        publishResult = true
        publishException = null
        rejectedUserIds.clear()
    }
}
