package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.AnvilScreenHandler
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandlerContext
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements the "Anvil Translation Key Probe" exploit.
 *
 * HOW IT WORKS:
 *  1. When a player joins, we open a fake anvil UI for them.
 *  2. We place an item in the input slot whose custom name is set to a raw
 *     translation key from the target mod  (e.g. "sodium.option_impact.low").
 *  3. The client renders the item name by resolving the key through its
 *     local language files.  If Sodium is installed, the key resolves to
 *     "Low"; if not, the client displays the raw key unchanged.
 *  4. When the player "renames" (which happens automatically on the client
 *     side as it pre-populates the rename field with the resolved name), a
 *     RenameItemC2SPacket is sent to the server containing the resolved text.
 *  5. Our mixin ([net.deamjava.id_ban.mixin.AnvilRenamePacketMixin]) intercepts
 *     that packet BEFORE the vanilla handler processes it.  We compare the
 *     received string against the expected raw key.
 *     ─ If they differ → the client resolved it → mod is present.
 *     ─ If they are equal → mod is absent (or the client blocks the probe).
 *
 * IMPLEMENTATION NOTES:
 *  • We only probe for mods that are configured in [IdBanConfig.config.translationProbes].
 *  • We send probes one at a time with a short delay so we don't flood the player.
 *  • The anvil is opened server-side; the client will auto-close it once it
 *    receives the rename packet callback (vanilla behaviour).
 *  • Pending probe state is stored in [pendingProbes] keyed by player UUID.
 */
object AnvilProbeManager {

    /**
     * Maps player UUID → queue of (modId, translationKey) pairs yet to probe.
     */
    private val pendingProbes: ConcurrentHashMap<UUID, ArrayDeque<Pair<String, String>>> =
        ConcurrentHashMap()

    /**
     * Maps player UUID → the probe currently waiting for a client response.
     * Value is (modId, rawTranslationKey).
     */
    val activeProbe: ConcurrentHashMap<UUID, Pair<String, String>> = ConcurrentHashMap()

    /**
     * Schedule probes for all translation keys configured in the config.
     * Only probes that correspond to banned OR whitelist-relevant mods are sent.
     */
    fun scheduleProbes(player: ServerPlayerEntity) {
        val cfg = IdBanConfig.config
        val probes = cfg.translationProbes

        if (probes.isEmpty()) return

        val queue = ArrayDeque<Pair<String, String>>()
        for ((modId, key) in probes) {
            queue.addLast(modId to key)
        }
        pendingProbes[player.uuid] = queue

        // Send first probe immediately (subsequent ones are triggered by
        // the response handler in the mixin via [onProbeResponse]).
        sendNextProbe(player)
    }

    /**
     * Opens the anvil probe UI for the next pending probe for this player.
     */
    fun sendNextProbe(player: ServerPlayerEntity) {
        val queue = pendingProbes[player.uuid] ?: return
        if (queue.isEmpty()) {
            pendingProbes.remove(player.uuid)
            return
        }

        val (modId, translationKey) = queue.removeFirst()
        activeProbe[player.uuid] = modId to translationKey

        IdBan.LOGGER.debug("[IdBan] Sending probe for '$modId' (key='$translationKey') to ${player.name.string}")

        // Build the item — a paper with a TRANSLATABLE custom name
        val probeItem = ItemStack(Items.PAPER).also { stack ->
            // Set the custom name as a translatable text component.
            // The client will resolve this translation key and display the result.
            // We use the raw translatable text so the client sees it as a key to resolve.
            stack.set(
                DataComponentTypes.CUSTOM_NAME,
                Text.translatable(translationKey)
            )
        }

        // Open anvil screen with the probe item in slot 0
        player.openHandledScreen(object : NamedScreenHandlerFactory {
            override fun getDisplayName(): Text = Text.empty()

            override fun createMenu(
                syncId: Int,
                playerInventory: net.minecraft.entity.player.PlayerInventory,
                playerEntity: net.minecraft.entity.player.PlayerEntity
            ): net.minecraft.screen.ScreenHandler {
                val ctx = ScreenHandlerContext.create(player.entityWorld as net.minecraft.server.world.ServerWorld, player.blockPos)
                val handler = AnvilScreenHandler(syncId, playerInventory, ctx)
                // Insert the probe item into the first input slot
                handler.slots[0].stack = probeItem.copy()
                return handler
            }
        })
    }

    /**
     * Called from [net.deamjava.id_ban.mixin.AnvilRenamePacketMixin] when
     * the client sends a rename packet while a probe is active.
     *
     * @param player         The player who sent the rename.
     * @param receivedString The string the client sent (possibly resolved key).
     * @return true if this packet was a probe response and should be swallowed.
     */
    fun onProbeResponse(player: ServerPlayerEntity, receivedString: String): Boolean {
        val probe = activeProbe.remove(player.uuid) ?: return false
        val (modId, rawKey) = probe

        // ── Clear slots FIRST, before any detection or kick logic ────────────
        // If we clear after onProbeDetected(), a kick can fire mid-way and
        // disconnect the player while the item is still sitting in the handler,
        // causing vanilla's onClosed() to drop it into their inventory.
        val handler = player.currentScreenHandler
        if (handler is AnvilScreenHandler) {
            for (i in 0 until handler.slots.size) {
                handler.slots[i].stack = ItemStack.EMPTY
            }
        }

        // Close the screen before detection so the inventory state is fully
        // settled before any potential kick is issued.
        player.closeHandledScreen()

        val modDetected = receivedString != rawKey

        if (modDetected) {
            IdBan.LOGGER.info(
                "[IdBan] Translation probe detected '$modId' on ${player.name.string} " +
                        "(key='$rawKey' resolved to '$receivedString')"
            )
            ModDetectionManager.onProbeDetected(player, modId)
        } else {
            IdBan.LOGGER.debug(
                "[IdBan] Probe for '$modId' negative on ${player.name.string} " +
                        "(key was not resolved)"
            )
        }

        // Continue with next probe unless player was kicked
        if (!player.isDisconnected) {
            sendNextProbe(player)
        }

        return true // packet was consumed
    }

    /**
     * Clean up all state for a disconnected player.
     */
    fun cancelProbes(uuid: UUID) {
        pendingProbes.remove(uuid)
        activeProbe.remove(uuid)
    }
}