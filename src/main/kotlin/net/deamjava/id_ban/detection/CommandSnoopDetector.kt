package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.minecraft.server.level.ServerPlayer


object CommandSnoopDetector {

    fun onTabComplete(player: ServerPlayer, partialCommand: String) {
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
                    return
                }
            }
        }
    }
}