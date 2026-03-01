package net.deamjava.id_ban.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.deamjava.id_ban.config.IdBanConfig
import net.deamjava.id_ban.detection.ModDetectionManager
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.command.DefaultPermissions

/**
 * Registers the /idban command tree.
 *
 * Usage overview:
 *
 *   /idban reload
 *   /idban save
 *
 *   /idban ban id <modId>
 *   /idban unban id <modId>
 *   /idban list ids
 *
 *   /idban ban keyword <word>
 *   /idban unban keyword <word>
 *   /idban list keywords
 *
 *   /idban whitelist mod add <modId>
 *   /idban whitelist mod remove <modId>
 *   /idban whitelist mod list
 *
 *   /idban whitelist player add <name|uuid>
 *   /idban whitelist player remove <name|uuid>
 *   /idban whitelist player list
 *
 *   /idban probe add <modId> <translationKey>
 *   /idban probe remove <modId>
 *   /idban probe list
 *
 *   /idban check <playerName>   (shows detected channels for online player)
 */
object IdBanCommands {

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("idban")
                .requires { it.permissions.hasPermission(DefaultPermissions.MODERATORS) }

                // ── reload / save ──────────────────────────────────────────────
                .then(literal("reload").executes { ctx -> cmdReload(ctx) })
                .then(literal("save").executes { ctx -> cmdSave(ctx) })

                // ── ban ────────────────────────────────────────────────────────
                .then(
                    literal("ban")
                        .then(
                            literal("id")
                                .then(
                                    argument("modId", StringArgumentType.word())
                                        .executes { ctx -> cmdBanId(ctx, StringArgumentType.getString(ctx, "modId")) }
                                )
                        )
                        .then(
                            literal("keyword")
                                .then(
                                    argument("keyword", StringArgumentType.word())
                                        .executes { ctx -> cmdBanKeyword(ctx, StringArgumentType.getString(ctx, "keyword")) }
                                )
                        )
                )

                // ── unban ──────────────────────────────────────────────────────
                .then(
                    literal("unban")
                        .then(
                            literal("id")
                                .then(
                                    argument("modId", StringArgumentType.word())
                                        .executes { ctx -> cmdUnbanId(ctx, StringArgumentType.getString(ctx, "modId")) }
                                )
                        )
                        .then(
                            literal("keyword")
                                .then(
                                    argument("keyword", StringArgumentType.word())
                                        .executes { ctx -> cmdUnbanKeyword(ctx, StringArgumentType.getString(ctx, "keyword")) }
                                )
                        )
                )

                // ── list ───────────────────────────────────────────────────────
                .then(
                    literal("list")
                        .then(literal("ids").executes { ctx -> cmdListIds(ctx) })
                        .then(literal("keywords").executes { ctx -> cmdListKeywords(ctx) })
                )

                // ── whitelist mod ──────────────────────────────────────────────
                .then(
                    literal("whitelist")
                        .then(
                            literal("mod")
                                .then(
                                    literal("add").then(
                                        argument("modId", StringArgumentType.word())
                                            .executes { ctx -> cmdWhitelistModAdd(ctx, StringArgumentType.getString(ctx, "modId")) }
                                    )
                                )
                                .then(
                                    literal("remove").then(
                                        argument("modId", StringArgumentType.word())
                                            .executes { ctx -> cmdWhitelistModRemove(ctx, StringArgumentType.getString(ctx, "modId")) }
                                    )
                                )
                                .then(literal("list").executes { ctx -> cmdWhitelistModList(ctx) })
                        )
                        .then(
                            literal("player")
                                .then(
                                    literal("add").then(
                                        argument("player", StringArgumentType.word())
                                            .executes { ctx -> cmdWhitelistPlayerAdd(ctx, StringArgumentType.getString(ctx, "player")) }
                                    )
                                )
                                .then(
                                    literal("remove").then(
                                        argument("player", StringArgumentType.word())
                                            .executes { ctx -> cmdWhitelistPlayerRemove(ctx, StringArgumentType.getString(ctx, "player")) }
                                    )
                                )
                                .then(literal("list").executes { ctx -> cmdWhitelistPlayerList(ctx) })
                        )
                )

                // ── probe ──────────────────────────────────────────────────────
                .then(
                    literal("probe")
                        .then(
                            literal("add")
                                .then(
                                    argument("modId", StringArgumentType.word())
                                        .then(
                                            argument("translationKey", StringArgumentType.greedyString())
                                                .executes { ctx ->
                                                    cmdProbeAdd(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "modId"),
                                                        StringArgumentType.getString(ctx, "translationKey")
                                                    )
                                                }
                                        )
                                )
                        )
                        .then(
                            literal("remove").then(
                                argument("modId", StringArgumentType.word())
                                    .executes { ctx -> cmdProbeRemove(ctx, StringArgumentType.getString(ctx, "modId")) }
                            )
                        )
                        .then(literal("list").executes { ctx -> cmdProbeList(ctx) })
                )

                // ── check ──────────────────────────────────────────────────────
                .then(
                    literal("check").then(
                        argument("playerName", StringArgumentType.word())
                            .executes { ctx -> cmdCheck(ctx, StringArgumentType.getString(ctx, "playerName")) }
                    )
                )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command implementations
    // ─────────────────────────────────────────────────────────────────────────

    private fun cmdReload(ctx: CommandContext<ServerCommandSource>): Int {
        IdBanConfig.reload()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Config reloaded.") }, true)
        return 1
    }

    private fun cmdSave(ctx: CommandContext<ServerCommandSource>): Int {
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Config saved.") }, true)
        return 1
    }

    private fun cmdBanId(ctx: CommandContext<ServerCommandSource>, modId: String): Int {
        val list = IdBanConfig.config.bannedModIds
        if (list.any { it.equals(modId, ignoreCase = true) }) {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$modId' is already banned.") }, false)
            return 0
        }
        list.add(modId)
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Banned mod ID: $modId") }, true)
        return 1
    }

    private fun cmdUnbanId(ctx: CommandContext<ServerCommandSource>, modId: String): Int {
        val removed = IdBanConfig.config.bannedModIds.removeIf { it.equals(modId, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendFeedback({ Text.literal("§a[IdBan] Unbanned mod ID: $modId") }, true)
            1
        } else {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$modId' was not in the ban list.") }, false)
            0
        }
    }

    private fun cmdListIds(ctx: CommandContext<ServerCommandSource>): Int {
        val list = IdBanConfig.config.bannedModIds
        ctx.source.sendFeedback({
            Text.literal("§6[IdBan] Banned mod IDs (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdBanKeyword(ctx: CommandContext<ServerCommandSource>, keyword: String): Int {
        val list = IdBanConfig.config.bannedKeywords
        if (list.any { it.equals(keyword, ignoreCase = true) }) {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] Keyword '$keyword' is already banned.") }, false)
            return 0
        }
        list.add(keyword)
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Banned keyword: $keyword") }, true)
        return 1
    }

    private fun cmdUnbanKeyword(ctx: CommandContext<ServerCommandSource>, keyword: String): Int {
        val removed = IdBanConfig.config.bannedKeywords.removeIf { it.equals(keyword, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendFeedback({ Text.literal("§a[IdBan] Removed keyword: $keyword") }, true)
            1
        } else {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] Keyword '$keyword' not found.") }, false)
            0
        }
    }

    private fun cmdListKeywords(ctx: CommandContext<ServerCommandSource>): Int {
        val list = IdBanConfig.config.bannedKeywords
        ctx.source.sendFeedback({
            Text.literal("§6[IdBan] Banned keywords (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdWhitelistModAdd(ctx: CommandContext<ServerCommandSource>, modId: String): Int {
        val list = IdBanConfig.config.modWhitelist
        if (list.any { it.equals(modId, ignoreCase = true) }) {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$modId' already in mod whitelist.") }, false)
            return 0
        }
        list.add(modId)
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Added '$modId' to mod whitelist.") }, true)
        return 1
    }

    private fun cmdWhitelistModRemove(ctx: CommandContext<ServerCommandSource>, modId: String): Int {
        val removed = IdBanConfig.config.modWhitelist.removeIf { it.equals(modId, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendFeedback({ Text.literal("§a[IdBan] Removed '$modId' from mod whitelist.") }, true)
            1
        } else {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$modId' not in mod whitelist.") }, false)
            0
        }
    }

    private fun cmdWhitelistModList(ctx: CommandContext<ServerCommandSource>): Int {
        val list = IdBanConfig.config.modWhitelist
        ctx.source.sendFeedback({
            Text.literal(
                "§6[IdBan] Mod whitelist (${list.size})${if (list.isEmpty()) " — DISABLED (all mods allowed)" else ""}: " +
                        "§f${list.joinToString(", ").ifEmpty { "(none)" }}"
            )
        }, false)
        return 1
    }

    private fun cmdWhitelistPlayerAdd(ctx: CommandContext<ServerCommandSource>, player: String): Int {
        val list = IdBanConfig.config.playerWhitelist
        if (list.any { it.equals(player, ignoreCase = true) }) {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$player' already in player whitelist.") }, false)
            return 0
        }
        list.add(player)
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Added '$player' to player whitelist.") }, true)
        return 1
    }

    private fun cmdWhitelistPlayerRemove(ctx: CommandContext<ServerCommandSource>, player: String): Int {
        val removed = IdBanConfig.config.playerWhitelist.removeIf { it.equals(player, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendFeedback({ Text.literal("§a[IdBan] Removed '$player' from player whitelist.") }, true)
            1
        } else {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] '$player' not in player whitelist.") }, false)
            0
        }
    }

    private fun cmdWhitelistPlayerList(ctx: CommandContext<ServerCommandSource>): Int {
        val list = IdBanConfig.config.playerWhitelist
        ctx.source.sendFeedback({
            Text.literal("§6[IdBan] Player whitelist (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdProbeAdd(ctx: CommandContext<ServerCommandSource>, modId: String, key: String): Int {
        IdBanConfig.config.translationProbes[modId] = key
        IdBanConfig.save()
        ctx.source.sendFeedback({ Text.literal("§a[IdBan] Added probe: $modId → $key") }, true)
        return 1
    }

    private fun cmdProbeRemove(ctx: CommandContext<ServerCommandSource>, modId: String): Int {
        val removed = IdBanConfig.config.translationProbes.remove(modId)
        return if (removed != null) {
            IdBanConfig.save()
            ctx.source.sendFeedback({ Text.literal("§a[IdBan] Removed probe for '$modId'.") }, true)
            1
        } else {
            ctx.source.sendFeedback({ Text.literal("§e[IdBan] No probe found for '$modId'.") }, false)
            0
        }
    }

    private fun cmdProbeList(ctx: CommandContext<ServerCommandSource>): Int {
        val probes = IdBanConfig.config.translationProbes
        ctx.source.sendFeedback({
            Text.literal("§6[IdBan] Translation probes (${probes.size}):")
        }, false)
        probes.forEach { (modId, key) ->
            ctx.source.sendFeedback({ Text.literal("  §e$modId §7→ §f$key") }, false)
        }
        if (probes.isEmpty()) {
            ctx.source.sendFeedback({ Text.literal("  §7(none)") }, false)
        }
        return 1
    }

    private fun cmdCheck(ctx: CommandContext<ServerCommandSource>, playerName: String): Int {
        val server = ctx.source.server
        val target = server.playerManager.getPlayer(playerName)
        if (target == null) {
            ctx.source.sendFeedback({ Text.literal("§c[IdBan] Player '$playerName' not found online.") }, false)
            return 0
        }
        val channels = ModDetectionManager.detectedChannels[target.uuid] ?: emptySet()
        ctx.source.sendFeedback({
            Text.literal("§6[IdBan] Channels for ${target.name.string} (${channels.size}): §f${channels.joinToString(", ").ifEmpty { "(none / vanilla)" }}")
        }, false)
        return 1
    }
}