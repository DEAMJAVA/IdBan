package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ModDetectionManager {

    val detectedChannels: ConcurrentHashMap<UUID, Set<String>> = ConcurrentHashMap()

    private val probeDelay: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()
    private val receivedPackResponseBeforeSetup: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap()

    private val awaitingResourcePackResponse: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap()

    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player

            val channels: Set<Identifier> = ServerPlayNetworking.getSendable(handler)
            val namespaces: Set<String> = channels.map { it.namespace }.toSet()
            detectedChannels[player.uuid] = namespaces

            IdBan.LOGGER.info("[IdBan] Player ${player.name.string} channels: $namespaces")

            if (isPlayerWhitelisted(player)) {
                IdBan.LOGGER.info("[IdBan] ${player.name.string} is whitelisted — skipping checks.")
                return@register
            }

            val banReason = checkChannels(player, namespaces)
            if (banReason != null) {
                IdBan.kickPlayer(player, banReason)
                return@register
            }
            if (receivedPackResponseBeforeSetup.remove(player.uuid) == true) {
                AnvilProbeManager.scheduleProbes(player)
                return@register
            }


            awaitingResourcePackResponse[player.uuid] = true
            probeDelay[player.uuid] = IdBanConfig.config.probeDelayTicks
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickProbeDelays(server)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val uuid = handler.player.uuid
            detectedChannels.remove(uuid)
            probeDelay.remove(uuid)
            awaitingResourcePackResponse.remove(uuid)
            AnvilProbeManager.cancelProbes(uuid)
        }
    }

    fun onResourcePackResponseReceived(player: ServerPlayer) {
        if (!awaitingResourcePackResponse.containsKey(player.uuid)) {
            receivedPackResponseBeforeSetup[player.uuid] = true
            return
        }
        val wasWaiting = awaitingResourcePackResponse.remove(player.uuid) ?: return
        if (!wasWaiting) return

        probeDelay.remove(player.uuid)

        if (!player.hasDisconnected()) {
            IdBan.LOGGER.debug("[IdBan] Resource pack response received from ${player.name.string}, starting probes.")
            AnvilProbeManager.scheduleProbes(player)
        }
    }

    private fun tickProbeDelays(server: MinecraftServer) {
        val iterator = probeDelay.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - 1
            if (remaining <= 0) {
                iterator.remove()
                val stillWaiting = awaitingResourcePackResponse.remove(entry.key) ?: false
                if (stillWaiting) {
                    val player = server.playerList.getPlayer(entry.key)
                    if (player != null && !player.hasDisconnected()) {
                        IdBan.LOGGER.debug("[IdBan] No resource pack response from ${player.name.string}, probing via fallback.")
                        AnvilProbeManager.scheduleProbes(player)
                    }
                }
            } else {
                entry.setValue(remaining)
            }
        }
    }


    fun isPlayerWhitelisted(player: ServerPlayer): Boolean {
        val whitelist = IdBanConfig.config.playerWhitelist
        if (whitelist.isEmpty()) return false
        val uuidStr = player.uuid.toString()
        val name = player.name.string
        return whitelist.any { it.equals(uuidStr, ignoreCase = true) || it.equals(name, ignoreCase = true) }
    }

    fun checkChannels(player: ServerPlayer, namespaces: Set<String>): String? {
        val cfg = IdBanConfig.config

        if (cfg.modWhitelist.isNotEmpty()) {
            val unauthorized = namespaces.filter { ns ->
                ns !in VANILLA_NAMESPACES && ns !in cfg.modWhitelist
            }
            if (unauthorized.isNotEmpty()) {
                return "Unauthorized mod(s): ${unauthorized.joinToString()}"
            }
        }

        for (banned in cfg.bannedModIds) {
            if (namespaces.any { it.equals(banned, ignoreCase = true) }) {
                return "Banned mod: $banned"
            }
        }

        val allChannels = ServerPlayNetworking.getSendable(player.connection)
        for (keyword in cfg.bannedKeywords) {
            val matchingNs = namespaces.firstOrNull { it.contains(keyword, ignoreCase = true) }
            if (matchingNs != null) {
                return "Banned keyword '$keyword' matched mod channel: $matchingNs"
            }
            val matchingFull = allChannels.firstOrNull { id ->
                id.namespace.contains(keyword, ignoreCase = true) ||
                        id.path.contains(keyword, ignoreCase = true)
            }
            if (matchingFull != null) {
                return "Banned keyword '$keyword' matched channel: $matchingFull"
            }
        }

        return null
    }

    fun onProbeDetected(player: ServerPlayer, detectedModId: String) {
        if (isPlayerWhitelisted(player)) return

        val cfg = IdBanConfig.config

        if (cfg.modWhitelist.isNotEmpty() && detectedModId !in cfg.modWhitelist) {
            IdBan.kickPlayer(player, "Unauthorized mod (probe): $detectedModId")
            return
        }

        if (cfg.bannedModIds.any { it.equals(detectedModId, ignoreCase = true) }) {
            IdBan.kickPlayer(player, "Banned mod (probe): $detectedModId")
            return
        }

        for (kw in cfg.bannedKeywords) {
            if (detectedModId.contains(kw, ignoreCase = true)) {
                IdBan.kickPlayer(player, "Banned keyword '$kw' matched mod: $detectedModId")
                return
            }
        }
    }



    private val VANILLA_NAMESPACES = setOf(
        "minecraft", "fabric", "fabricloader", "fabric-api",
        "realms", "brigadier"
    )
}