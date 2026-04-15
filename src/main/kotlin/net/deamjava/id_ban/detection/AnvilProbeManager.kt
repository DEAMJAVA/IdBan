package net.deamjava.id_ban.detection

import net.deamjava.id_ban.IdBan
import net.deamjava.id_ban.config.IdBanConfig
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.MenuProvider
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


object AnvilProbeManager {
    class ProbeAnvilScreenHandler(
        syncId: Int,
        playerInventory: Inventory,
        ctx: ContainerLevelAccess
    ) : AnvilMenu(syncId, playerInventory, ctx) {

        override fun removed(player: Player) {}

        override fun quickMoveStack(player: Player, slot: Int): ItemStack {
            return ItemStack.EMPTY
        }

        override fun stillValid(player: Player): Boolean {
            return true
        }
    }

    private val pendingProbes: ConcurrentHashMap<UUID, ArrayDeque<Pair<String, String>>> =
        ConcurrentHashMap()

    val activeProbe: ConcurrentHashMap<UUID, Pair<String, String>> = ConcurrentHashMap()

    private val checkSessions: ConcurrentHashMap<UUID, CheckSession> = ConcurrentHashMap()

    data class CheckSession(
        val source: CommandSourceStack,
        val detected: MutableList<String> = mutableListOf(),
        val notDetected: MutableList<String> = mutableListOf()
    )

    fun scheduleProbes(player: ServerPlayer) {
        val cfg = IdBanConfig.config
        val probes = cfg.translationProbes

        if (probes.isEmpty()) return

        val queue = ArrayDeque<Pair<String, String>>()
        for ((modId, key) in probes) {
            queue.addLast(modId to key)
        }
        pendingProbes[player.uuid] = queue

        sendNextProbe(player)
    }

    fun scheduleCheckProbes(player: ServerPlayer, source: CommandSourceStack) {
        val cfg = IdBanConfig.config
        val probes = cfg.translationProbes

        if (probes.isEmpty()) {
            source.sendSuccess({ Component.literal("§e[IdBan] No translation probes configured.") }, false)
            return
        }

        if (pendingProbes.containsKey(player.uuid) || activeProbe.containsKey(player.uuid)) {
            source.sendSuccess({
                Component.literal("§e[IdBan] Probes already in progress for ${player.name.string}, try again shortly.")
            }, false)
            return
        }

        checkSessions[player.uuid] = CheckSession(source)

        val queue = ArrayDeque<Pair<String, String>>()
        for ((modId, key) in probes) {
            queue.addLast(modId to key)
        }
        pendingProbes[player.uuid] = queue

        source.sendSuccess({
            Component.literal("§7[IdBan] Running ${probes.size} probe(s) on ${player.name.string}...")
        }, false)

        sendNextProbe(player)
    }

    fun sendNextProbe(player: ServerPlayer) {
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

        val probeItem = ItemStack(Items.PAPER).also { stack ->
            stack.set(
                DataComponents.CUSTOM_NAME,
                Component.translatable(translationKey)
            )
        }

        player.openMenu(object : MenuProvider {
            override fun getDisplayName(): Component = Component.empty()

            override fun createMenu(
                syncId: Int,
                playerInventory: Inventory,
                playerEntity: Player
            ): net.minecraft.world.inventory.AbstractContainerMenu {
                val ctx = ContainerLevelAccess.create(player.level() , player.blockPosition())
                val handler = ProbeAnvilScreenHandler(syncId, playerInventory, ctx)
                handler.slots[0].setByPlayer(probeItem.copy())
                return handler
            }
        })
    }

    fun onProbeResponse(player: ServerPlayer, receivedString: String): Boolean {
        val probe = activeProbe.remove(player.uuid) ?: return false
        val (modId, rawKey) = probe

        player.closeContainer()

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

        val session = checkSessions[player.uuid]
        if (session != null) {
            if (modDetected) session.detected.add(modId) else session.notDetected.add(modId)
        } else {
            if (modDetected) {
                ModDetectionManager.onProbeDetected(player, modId)
            }
        }

        if (!player.hasDisconnected()) {
            sendNextProbe(player)
        }

        return true
    }

    private fun reportCheckResults(player: ServerPlayer, session: CheckSession) {
        val src = session.source
        src.sendSuccess({ Component.literal("§6[IdBan] Probe results for §e${player.name.string}§6:") }, false)

        if (session.detected.isEmpty() && session.notDetected.isEmpty()) {
            src.sendSuccess({ Component.literal("  §7(no probes ran)") }, false)
            return
        }

        if (session.detected.isNotEmpty()) {
            src.sendSuccess({ Component.literal("  §cDetected (${session.detected.size}): §f${session.detected.joinToString(", ")}") }, false)
        } else {
            src.sendSuccess({ Component.literal("  §aNo probed mods detected.") }, false)
        }

        if (session.notDetected.isNotEmpty()) {
            src.sendSuccess({ Component.literal("  §7Not detected (${session.notDetected.size}): §8${session.notDetected.joinToString(", ")}") }, false)
        }
    }


    fun cancelProbes(uuid: UUID) {
        pendingProbes.remove(uuid)
        activeProbe.remove(uuid)
        checkSessions.remove(uuid)
    }
}
