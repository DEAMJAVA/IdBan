package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.AnvilScreenHandler
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandlerContext
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


object AnvilProbeManager {
    class ProbeAnvilScreenHandler(
        syncId: Int,
        playerInventory: PlayerInventory,
        ctx: ScreenHandlerContext
    ) : AnvilScreenHandler(syncId, playerInventory, ctx) {

        override fun onClosed(player: PlayerEntity) {
            // DO NOTHING
            // prevents vanilla from giving items back or dropping them
        }

        // Optional but recommended: block shift-click stealing
        override fun quickMove(player: PlayerEntity, slot: Int): ItemStack {
            return ItemStack.EMPTY
        }

        // Optional: prevent pickup
        override fun canUse(player: PlayerEntity): Boolean {
            return true
        }
    }

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
     * Tracks probe sessions triggered by /idban check (not by join).
     * These sessions report results back to the operator and never kick.
     * Maps player UUID → CheckSession.
     */
    private val checkSessions: ConcurrentHashMap<UUID, CheckSession> = ConcurrentHashMap()

    data class CheckSession(
        val source: ServerCommandSource,
        val detected: MutableList<String> = mutableListOf(),
        val notDetected: MutableList<String> = mutableListOf()
    )

    /**
     * Schedule probes for all translation keys configured in the config.
     * Called on join — results are used for ban enforcement.
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
     * Schedule probes triggered by /idban check.
     * Results are reported back to [source] — no kicks are issued.
     */
    fun scheduleCheckProbes(player: ServerPlayerEntity, source: ServerCommandSource) {
        val cfg = IdBanConfig.config
        val probes = cfg.translationProbes

        if (probes.isEmpty()) {
            source.sendFeedback({ Text.literal("§e[IdBan] No translation probes configured.") }, false)
            return
        }

        // If probes are already running for this player (e.g. still joining),
        // don't stack a second run — just inform the operator.
        if (pendingProbes.containsKey(player.uuid) || activeProbe.containsKey(player.uuid)) {
            source.sendFeedback({
                Text.literal("§e[IdBan] Probes already in progress for ${player.name.string}, try again shortly.")
            }, false)
            return
        }

        checkSessions[player.uuid] = CheckSession(source)

        val queue = ArrayDeque<Pair<String, String>>()
        for ((modId, key) in probes) {
            queue.addLast(modId to key)
        }
        pendingProbes[player.uuid] = queue

        source.sendFeedback({
            Text.literal("§7[IdBan] Running ${probes.size} probe(s) on ${player.name.string}...")
        }, false)

        sendNextProbe(player)
    }

    /**
     * Opens the anvil probe UI for the next pending probe for this player.
     */
    fun sendNextProbe(player: ServerPlayerEntity) {
        val queue = pendingProbes[player.uuid] ?: return
        if (queue.isEmpty()) {
            pendingProbes.remove(player.uuid)
            // If this was a check session, all probes are done — print the report
            checkSessions.remove(player.uuid)?.let { session ->
                reportCheckResults(player, session)
            }
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
                playerInventory: PlayerInventory,
                playerEntity: PlayerEntity
            ): net.minecraft.screen.ScreenHandler {
                val ctx = ScreenHandlerContext.create(player.entityWorld as net.minecraft.server.world.ServerWorld, player.blockPos)
                val handler = ProbeAnvilScreenHandler(syncId, playerInventory, ctx)
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
//        val handler = player.currentScreenHandler
//        if (handler is AnvilScreenHandler) {
//            for (i in 0 until handler.slots.size) {
//                handler.slots[i].stack = ItemStack.EMPTY
//            }
//        }

        // Close the screen before detection so the inventory state is fully
        // settled before any potential kick is issued.
        player.closeHandledScreen()

        val modDetected = receivedString != rawKey

        if (modDetected) {
            IdBan.LOGGER.info(
                "[IdBan] Translation probe detected '$modId' on ${player.name.string} " +
                        "(key='$rawKey' resolved to '$receivedString')"
            )
        } else {
            IdBan.LOGGER.debug(
                "[IdBan] Probe for '$modId' negative on ${player.name.string} " +
                        "(key was not resolved)"
            )
        }

        // If this is a check session, record the result but never kick
        val session = checkSessions[player.uuid]
        if (session != null) {
            if (modDetected) session.detected.add(modId) else session.notDetected.add(modId)
        } else {
            // Normal join probe — enforce bans
            if (modDetected) {
                ModDetectionManager.onProbeDetected(player, modId)
            }
        }

        // Continue with next probe unless player was kicked
        if (!player.isDisconnected) {
            sendNextProbe(player)
        }

        return true // packet was consumed
    }

    /**
     * Sends the full probe report to the operator who ran /idban check.
     */
    private fun reportCheckResults(player: ServerPlayerEntity, session: CheckSession) {
        val src = session.source
        src.sendFeedback({ Text.literal("§6[IdBan] Probe results for §e${player.name.string}§6:") }, false)

        if (session.detected.isEmpty() && session.notDetected.isEmpty()) {
            src.sendFeedback({ Text.literal("  §7(no probes ran)") }, false)
            return
        }

        if (session.detected.isNotEmpty()) {
            src.sendFeedback({ Text.literal("  §cDetected (${session.detected.size}): §f${session.detected.joinToString(", ")}") }, false)
        } else {
            src.sendFeedback({ Text.literal("  §aNo probed mods detected.") }, false)
        }

        if (session.notDetected.isNotEmpty()) {
            src.sendFeedback({ Text.literal("  §7Not detected (${session.notDetected.size}): §8${session.notDetected.joinToString(", ")}") }, false)
        }
    }

    /**
     * Clean up all state for a disconnected player.
     */
    fun cancelProbes(uuid: UUID) {
        pendingProbes.remove(uuid)
        activeProbe.remove(uuid)
        checkSessions.remove(uuid)
    }
}
