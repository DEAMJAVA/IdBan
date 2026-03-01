package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central detection manager.
 *
 * Detection methods used (in order):
 *
 *  1. CHANNEL SNIFFING  ─ Fabric clients send a `minecraft:register` packet
 *     listing all plugin channels they can receive. Many mods register a
 *     channel in the pattern `<modid>:<something>`. We extract the namespace
 *     of every registered channel and compare it against the ban lists.
 *
 *  2. ANVIL TRANSLATION PROBE  ─ We open an invisible anvil container for the
 *     player (via [AnvilProbeManager]) with an item whose name is a raw
 *     translation key from the suspected mod.  If the client resolves the key
 *     (i.e. the returned rename string != the raw key) the mod is present.
 *     This is the "sign / anvil exploit" described in the original request.
 */
object ModDetectionManager {

    /**
     * Stores the detected channel namespaces per player UUID.
     * Available after JOIN.
     */
    val detectedChannels: ConcurrentHashMap<UUID, Set<String>> = ConcurrentHashMap()

    fun register() {
        // ── Phase 1: channel sniffing ─────────────────────────────────────────
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player

            // Collect all channel namespaces the client advertised
            val channels: Set<Identifier> = ServerPlayNetworking.getSendable(handler)
            val namespaces: Set<String> = channels.map { it.namespace }.toSet()
            detectedChannels[player.uuid] = namespaces

            IdBan.LOGGER.info(
                "[IdBan] Player ${player.name.string} channels: $namespaces"
            )

            // Skip if the player is on the player whitelist
            if (isPlayerWhitelisted(player)) {
                IdBan.LOGGER.info("[IdBan] ${player.name.string} is whitelisted — skipping checks.")
                return@register
            }

            // Check channels against ban lists
            val banReason = checkChannels(player, namespaces)
            if (banReason != null) {
                // Schedule the kick on the server thread (we are already there via JOIN event)
                IdBan.kickPlayer(player, banReason)
                return@register
            }

            // ── Phase 2: schedule translation key probes ─────────────────────
            AnvilProbeManager.scheduleProbes(player)
        }

        // Clean up when player leaves
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            detectedChannels.remove(handler.player.uuid)
            AnvilProbeManager.cancelProbes(handler.player.uuid)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun isPlayerWhitelisted(player: ServerPlayerEntity): Boolean {
        val whitelist = IdBanConfig.config.playerWhitelist
        if (whitelist.isEmpty()) return false
        val uuidStr = player.uuid.toString()
        val name = player.name.string
        return whitelist.any { it.equals(uuidStr, ignoreCase = true) || it.equals(name, ignoreCase = true) }
    }

    /**
     * Checks a set of channel namespaces against the ban rules.
     * Returns a human-readable ban reason, or null if the player passes.
     */
    fun checkChannels(player: ServerPlayerEntity, namespaces: Set<String>): String? {
        val cfg = IdBanConfig.config

        // Mod-ID whitelist: if enabled, any namespace NOT in the whitelist triggers a kick
        if (cfg.modWhitelist.isNotEmpty()) {
            val unauthorized = namespaces.filter { ns ->
                // vanilla / system namespaces always pass
                ns !in VANILLA_NAMESPACES && ns !in cfg.modWhitelist
            }
            if (unauthorized.isNotEmpty()) {
                return "Unauthorized mod(s): ${unauthorized.joinToString()}"
            }
        }

        // Banned mod IDs (exact namespace match)
        for (banned in cfg.bannedModIds) {
            if (namespaces.any { it.equals(banned, ignoreCase = true) }) {
                return "Banned mod: $banned"
            }
        }

        // Banned keywords (substring in any channel namespace or full channel name)
        val allChannels = ServerPlayNetworking.getSendable(player.networkHandler)
        for (keyword in cfg.bannedKeywords) {
            val matchingNs = namespaces.firstOrNull { it.contains(keyword, ignoreCase = true) }
            if (matchingNs != null) {
                return "Banned keyword '$keyword' matched mod channel: $matchingNs"
            }
            // Also check full channel path
            val matchingFull = allChannels.firstOrNull {
                it.toString().contains(keyword, ignoreCase = true)
            }
            if (matchingFull != null) {
                return "Banned keyword '$keyword' matched channel: $matchingFull"
            }
        }

        return null
    }

    /**
     * Called by [AnvilProbeManager] when a probe resolves a translation key,
     * confirming a mod is installed.
     */
    fun onProbeDetected(player: ServerPlayerEntity, detectedModId: String) {
        if (isPlayerWhitelisted(player)) return

        val cfg = IdBanConfig.config

        // Whitelist check: if whitelist enabled and this modId is NOT in it → kick
        if (cfg.modWhitelist.isNotEmpty() && detectedModId !in cfg.modWhitelist) {
            IdBan.kickPlayer(player, "Unauthorized mod (probe): $detectedModId")
            return
        }

        // Banned mod-ID exact match
        if (cfg.bannedModIds.any { it.equals(detectedModId, ignoreCase = true) }) {
            IdBan.kickPlayer(player, "Banned mod (probe): $detectedModId")
            return
        }

        // Keyword match on the modId
        for (kw in cfg.bannedKeywords) {
            if (detectedModId.contains(kw, ignoreCase = true)) {
                IdBan.kickPlayer(player, "Banned keyword '$kw' matched mod: $detectedModId")
                return
            }
        }
    }

    // Namespaces that are part of vanilla / Fabric and should never be flagged
    private val VANILLA_NAMESPACES = setOf(
        "minecraft", "fabric", "fabricloader", "fabric-api",
        "realms", "brigadier"
    )
}