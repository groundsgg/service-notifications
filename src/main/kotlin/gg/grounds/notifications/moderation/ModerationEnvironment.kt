package gg.grounds.notifications.moderation

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class ModerationEnvironment(@get:JsonValue val wireValue: String) {
    STAGE("stage"),
    PROD("prod");

    val readySubject: String
        get() = "moderation-v2.$wireValue.report.ready-for-review"

    companion object {
        fun parse(value: String?): ModerationEnvironment? =
            entries.firstOrNull { it.wireValue == value }

        @JvmStatic
        @JsonCreator
        fun fromWire(value: String): ModerationEnvironment =
            parse(value) ?: throw IllegalArgumentException("Moderation environment is invalid")
    }
}
