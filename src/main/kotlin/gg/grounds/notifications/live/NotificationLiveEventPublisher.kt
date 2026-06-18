package gg.grounds.notifications.live

interface NotificationLiveEventPublisher {
    /**
     * Attempts to publish a live notification change event.
     *
     * Returns true when the event was accepted by the live event transport. Returns false when the
     * transport is disabled or unavailable. A true result does not mean an SSE client has consumed
     * the event; clients treat live events as invalidation signals and refetch notification state.
     */
    fun publish(event: NotificationLiveEvent): Boolean
}
