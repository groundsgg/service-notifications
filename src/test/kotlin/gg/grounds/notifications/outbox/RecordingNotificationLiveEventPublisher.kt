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

    override fun publish(event: NotificationLiveEvent) {
        events.add(event)
    }

    fun reset() {
        events.clear()
    }
}
