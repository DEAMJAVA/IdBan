package net.deamjava.id_ban
// ID
import net.deamjava.id_ban.commands.IdBanCommands
import net.deamjava.id_ban.config.IdBanConfig
import net.deamjava.id_ban.detection.ModDetectionManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity
import org.slf4j.LoggerFactory

object IdBan : ModInitializer {

    const val MOD_ID = "id-ban"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LOGGER.info("IdBan initializing...")

        // Load configuration
        IdBanConfig.load()

        // Register detection events
        ModDetectionManager.register()

        // Register commands
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            IdBanCommands.register(dispatcher)
        }

        LOGGER.info("IdBan initialized. Banned IDs: ${IdBanConfig.config.bannedModIds}, " +
                "Banned Keywords: ${IdBanConfig.config.bannedKeywords}")
    }

    /**
     * Kicks a player with a formatted disconnect message.
     */
    fun kickPlayer(player: ServerPlayerEntity, reason: String) {
        val msg = net.minecraft.text.Text.literal(
            IdBanConfig.config.kickMessage.replace("{reason}", reason)
        )
        player.networkHandler.disconnect(msg)
        LOGGER.info("Kicked player ${player.name.string} — Reason: $reason")
    }
}