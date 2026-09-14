package gg.grounds.notifications.core

import com.fasterxml.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

data class NotificationEventRequest(
    val idempotencyKey: String,
    val type: String,
    val category: String,
    val priority: String,
    val scope: NotificationScope,
    val actor: NotificationActor,
    val entity: NotificationEntity? = null,
    val title: String,
    val body: String,
    val data: JsonNode? = null,
    val recipients: List<NotificationRecipientRequest>,
    val actions: List<NotificationActionRequest> = emptyList(),
)

data class NotificationScope(val type: String, val id: String? = null)

data class NotificationActor(val type: String, val id: String? = null)

data class NotificationEntity(val type: String, val id: String? = null)

data class NotificationRecipientRequest(val userId: String)

enum class NotificationActionKind {
    COMMAND,
    OPEN_PORTAL_CASE,
}

data class NotificationActionRequest(
    val actionKey: String,
    val label: String,
    val style: String,
    val command: String? = null,
    val payload: JsonNode? = null,
    val requiresFreshCheck: Boolean = true,
    val kind: NotificationActionKind = NotificationActionKind.COMMAND,
    val entityType: String? = null,
    val entityId: String? = null,
) {
    fun validated(): NotificationActionRequest {
        when (kind) {
            NotificationActionKind.COMMAND -> {
                require(!command.isNullOrBlank()) { "Command action requires a command" }
                require(entityType == null && entityId == null) {
                    "Command action cannot carry a navigation entity"
                }
            }
            NotificationActionKind.OPEN_PORTAL_CASE -> {
                require(command == null) { "Portal navigation action cannot carry a command" }
                require(entityType == "CASE") { "Portal case action requires entity type CASE" }
                require(runCatching { UUID.fromString(entityId) }.isSuccess) {
                    "Portal case action requires a UUID entity ID"
                }
                require(payload == null || !payload.has("url")) {
                    "Portal navigation action cannot carry a URL"
                }
            }
        }
        return this
    }
}

data class NotificationEventResponse(val id: UUID, val created: Boolean)

data class NotificationInboxResponse(val items: List<NotificationInboxItem>)

data class NotificationInboxItem(
    val id: UUID,
    val recipientId: UUID,
    val recipientUserId: String,
    val type: String,
    val category: String,
    val priority: String,
    val title: String,
    val body: String,
    val data: JsonNode,
    val createdAt: OffsetDateTime,
    val readAt: OffsetDateTime?,
    val actions: List<NotificationActionItem>,
)

data class NotificationActionItem(
    val actionKey: String,
    val label: String,
    val style: String,
    val kind: NotificationActionKind,
    val entityType: String?,
    val entityId: String?,
)

data class StoredNotificationAction(
    val id: UUID,
    val notificationId: UUID,
    val actionKey: String,
    val command: String?,
    val payload: JsonNode,
    val kind: NotificationActionKind,
    val entityType: String?,
    val entityId: String?,
)

data class ActionExecutionResponse(val status: String, val reason: String? = null)
