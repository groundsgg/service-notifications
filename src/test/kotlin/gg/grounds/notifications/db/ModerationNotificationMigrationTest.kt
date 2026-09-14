package gg.grounds.notifications.db

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModerationNotificationMigrationTest {
    @Test
    fun `cleans invalid historical commands before adding typed shape constraint`() {
        val migration =
            checkNotNull(
                    javaClass.getResource(
                        "/db/migration/V5__moderation_notification_projection.sql"
                    )
                )
                .readText()
        val cleanup = migration.indexOf("DELETE FROM notification_actions")
        val constraint = migration.indexOf("ADD CONSTRAINT notification_action_typed_shape_check")

        assertTrue(cleanup >= 0, "migration must clean historical blank commands")
        assertTrue(cleanup < constraint, "cleanup must run before the validated constraint")
    }
}
