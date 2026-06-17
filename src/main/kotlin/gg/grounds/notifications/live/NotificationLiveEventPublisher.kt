package gg.grounds.notifications.live

interface NotificationLiveEventPublisher {
    fun publish(event: NotificationLiveEvent)
}
