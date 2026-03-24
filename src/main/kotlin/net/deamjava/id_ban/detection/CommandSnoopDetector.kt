package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Detects client-side mods by passively watching tab-complete (RequestCommandCompletions)
 * packets sent from the client.
 *
 * HOW IT WORKS:
 *  Many cheat/utility mods (Wurst, Meteor, etc.) register their own commands entirely
 *  client-side using Fabric's ClientCommandManager or their own input handler.
 *  These commands never exist in the server's Brigadier tree, but the client still
 *  sends a RequestCommandCompletionsC2SPacket when the player presses Tab while
 *  typing one of those commands.
 *
 *  By intercepting every such packet in [TabCompletePacketMixin] and forwarding
 *  the partialCommand string here, we can match it against a configurable map of
 *  known mod-specific command prefixes.
 *
 *  EXAMPLES:
 *   - Wurst:  all commands start with "."  (e.g. ".fly", ".killaura")
 *   - Meteor: all commands start with "."  (e.g. ".help", ".friend")
 *   - If the player types ".f" and tabs, we see partialCommand = ".f"
 *     → starts with "." → Wurst or Meteor detected.
 *
 *  NOTE: This is passive — it only fires when the player actually presses Tab
 *  while typing a client-side command. It does NOT require any packet injection
 *  or opening of fake screens. It is therefore zero-latency and zero-visual-impact,
 *  but only triggers if the player attempts to use a client command.
 *
 *  For active detection at join time, combine with [AnvilProbeManager].
 */
object CommandSnoopDetector {

    /**
     * Called from [net.deamjava.id_ban.mixin.TabCompletePacketMixin] every time
     * the client sends a RequestCommandCompletionsC2SPacket.
     *
     * @param player         The player who sent the packet.
     * @param partialCommand The partial command string the client is completing.
     */
    fun onTabComplete(player: ServerPlayerEntity, partialCommand: String) {
        if (ModDetectionManager.isPlayerWhitelisted(player)) return

        val cfg = IdBanConfig.config
        val prefixMap = cfg.clientCommandPrefixes

        for ((modId, prefixes) in prefixMap) {
            for (prefix in prefixes) {
                if (partialCommand.startsWith(prefix, ignoreCase = true)) {
                    IdBan.LOGGER.info(
                        "[IdBan] Command snoop detected '$modId' on ${player.name.string} " +
                                "(partialCommand='$partialCommand' matched prefix='$prefix')"
                    )
                    ModDetectionManager.onProbeDetected(player, modId)
                    return // one match is enough — don't double-kick
                }
            }
        }
    }
}