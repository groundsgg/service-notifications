package gg.grounds.notifications.channel

import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

interface MinecraftPlayerResolver {
    fun resolveUserId(playerUuid: String): String?
}

@ApplicationScoped
class InMemoryMinecraftPlayerResolver : MinecraftPlayerResolver {
    private val mappings = ConcurrentHashMap<String, String>()

    override fun resolveUserId(playerUuid: String): String? = mappings[playerUuid]

    fun mapPlayer(playerUuid: String, userId: String) {
        mappings[playerUuid] = userId
    }

    fun clear() {
        mappings.clear()
    }
}
