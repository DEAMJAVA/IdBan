package net.deamjava.id_ban.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.deamjava.id_ban.config.IdBanConfig
import net.deamjava.id_ban.detection.AnvilProbeManager
import net.deamjava.id_ban.detection.ModDetectionManager
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions

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

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("idban")
                .requires { it.permissions().hasPermission(Permissions.COMMANDS_MODERATOR) }

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

                // ── cmdprefix  (client-command snoop prefixes) ─────────────────
                // /idban cmdprefix add <modId> <prefix>
                // /idban cmdprefix remove <modId> <prefix>
                // /idban cmdprefix list
                .then(
                    literal("cmdprefix")
                        .then(
                            literal("add")
                                .then(
                                    argument("modId", StringArgumentType.word())
                                        .then(
                                            argument("prefix", StringArgumentType.greedyString())
                                                .executes { ctx ->
                                                    cmdPrefixAdd(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "modId"),
                                                        StringArgumentType.getString(ctx, "prefix")
                                                    )
                                                }
                                        )
                                )
                        )
                        .then(
                            literal("remove")
                                .then(
                                    argument("modId", StringArgumentType.word())
                                        .then(
                                            argument("prefix", StringArgumentType.greedyString())
                                                .executes { ctx ->
                                                    cmdPrefixRemove(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "modId"),
                                                        StringArgumentType.getString(ctx, "prefix")
                                                    )
                                                }
                                        )
                                )
                        )
                        .then(literal("list").executes { ctx -> cmdPrefixList(ctx) })
                )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command implementations
    // ─────────────────────────────────────────────────────────────────────────

    private fun cmdReload(ctx: CommandContext<CommandSourceStack>): Int {
        IdBanConfig.reload()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Config reloaded.") }, true)
        return 1
    }

    private fun cmdSave(ctx: CommandContext<CommandSourceStack>): Int {
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Config saved.") }, true)
        return 1
    }

    private fun cmdBanId(ctx: CommandContext<CommandSourceStack>, modId: String): Int {
        val list = IdBanConfig.config.bannedModIds
        if (list.any { it.equals(modId, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$modId' is already banned.") }, false)
            return 0
        }
        list.add(modId)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Banned mod ID: $modId") }, true)
        return 1
    }

    private fun cmdUnbanId(ctx: CommandContext<CommandSourceStack>, modId: String): Int {
        val removed = IdBanConfig.config.bannedModIds.removeIf { it.equals(modId, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendSuccess({ Component.literal("§a[IdBan] Unbanned mod ID: $modId") }, true)
            1
        } else {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$modId' was not in the ban list.") }, false)
            0
        }
    }

    private fun cmdListIds(ctx: CommandContext<CommandSourceStack>): Int {
        val list = IdBanConfig.config.bannedModIds
        ctx.source.sendSuccess({
            Component.literal("§6[IdBan] Banned mod IDs (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdBanKeyword(ctx: CommandContext<CommandSourceStack>, keyword: String): Int {
        val list = IdBanConfig.config.bannedKeywords
        if (list.any { it.equals(keyword, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] Keyword '$keyword' is already banned.") }, false)
            return 0
        }
        list.add(keyword)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Banned keyword: $keyword") }, true)
        return 1
    }

    private fun cmdUnbanKeyword(ctx: CommandContext<CommandSourceStack>, keyword: String): Int {
        val removed = IdBanConfig.config.bannedKeywords.removeIf { it.equals(keyword, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendSuccess({ Component.literal("§a[IdBan] Removed keyword: $keyword") }, true)
            1
        } else {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] Keyword '$keyword' not found.") }, false)
            0
        }
    }

    private fun cmdListKeywords(ctx: CommandContext<CommandSourceStack>): Int {
        val list = IdBanConfig.config.bannedKeywords
        ctx.source.sendSuccess({
            Component.literal("§6[IdBan] Banned keywords (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdWhitelistModAdd(ctx: CommandContext<CommandSourceStack>, modId: String): Int {
        val list = IdBanConfig.config.modWhitelist
        if (list.any { it.equals(modId, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$modId' already in mod whitelist.") }, false)
            return 0
        }
        list.add(modId)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Added '$modId' to mod whitelist.") }, true)
        return 1
    }

    private fun cmdWhitelistModRemove(ctx: CommandContext<CommandSourceStack>, modId: String): Int {
        val removed = IdBanConfig.config.modWhitelist.removeIf { it.equals(modId, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendSuccess({ Component.literal("§a[IdBan] Removed '$modId' from mod whitelist.") }, true)
            1
        } else {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$modId' not in mod whitelist.") }, false)
            0
        }
    }

    private fun cmdWhitelistModList(ctx: CommandContext<CommandSourceStack>): Int {
        val list = IdBanConfig.config.modWhitelist
        ctx.source.sendSuccess({
            Component.literal(
                "§6[IdBan] Mod whitelist (${list.size})${if (list.isEmpty()) " — DISABLED (all mods allowed)" else ""}: " +
                        "§f${list.joinToString(", ").ifEmpty { "(none)" }}"
            )
        }, false)
        return 1
    }

    private fun cmdWhitelistPlayerAdd(ctx: CommandContext<CommandSourceStack>, player: String): Int {
        val list = IdBanConfig.config.playerWhitelist
        if (list.any { it.equals(player, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$player' already in player whitelist.") }, false)
            return 0
        }
        list.add(player)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Added '$player' to player whitelist.") }, true)
        return 1
    }

    private fun cmdWhitelistPlayerRemove(ctx: CommandContext<CommandSourceStack>, player: String): Int {
        val removed = IdBanConfig.config.playerWhitelist.removeIf { it.equals(player, ignoreCase = true) }
        return if (removed) {
            IdBanConfig.save()
            ctx.source.sendSuccess({ Component.literal("§a[IdBan] Removed '$player' from player whitelist.") }, true)
            1
        } else {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] '$player' not in player whitelist.") }, false)
            0
        }
    }

    private fun cmdWhitelistPlayerList(ctx: CommandContext<CommandSourceStack>): Int {
        val list = IdBanConfig.config.playerWhitelist
        ctx.source.sendSuccess({
            Component.literal("§6[IdBan] Player whitelist (${list.size}): §f${list.joinToString(", ").ifEmpty { "(none)" }}")
        }, false)
        return 1
    }

    private fun cmdProbeAdd(ctx: CommandContext<CommandSourceStack>, modId: String, key: String): Int {
        IdBanConfig.config.translationProbes[modId] = key
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Added probe: $modId → $key") }, true)
        return 1
    }

    private fun cmdProbeRemove(ctx: CommandContext<CommandSourceStack>, modId: String): Int {
        val removed = IdBanConfig.config.translationProbes.remove(modId)
        return if (removed != null) {
            IdBanConfig.save()
            ctx.source.sendSuccess({ Component.literal("§a[IdBan] Removed probe for '$modId'.") }, true)
            1
        } else {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] No probe found for '$modId'.") }, false)
            0
        }
    }

    private fun cmdProbeList(ctx: CommandContext<CommandSourceStack>): Int {
        val probes = IdBanConfig.config.translationProbes
        ctx.source.sendSuccess({
            Component.literal("§6[IdBan] Translation probes (${probes.size}):")
        }, false)
        probes.forEach { (modId, key) ->
            ctx.source.sendSuccess({ Component.literal("  §e$modId §7→ §f$key") }, false)
        }
        if (probes.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("  §7(none)") }, false)
        }
        return 1
    }

    private fun cmdPrefixAdd(ctx: CommandContext<CommandSourceStack>, modId: String, prefix: String): Int {
        val map = IdBanConfig.config.clientCommandPrefixes
        val list = map.getOrPut(modId) { mutableListOf() }
        if (list.any { it.equals(prefix, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] Prefix '$prefix' for '$modId' already exists.") }, false)
            return 0
        }
        list.add(prefix)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Added prefix '$prefix' for mod '$modId'.") }, true)
        return 1
    }

    private fun cmdPrefixRemove(ctx: CommandContext<CommandSourceStack>, modId: String, prefix: String): Int {
        val list = IdBanConfig.config.clientCommandPrefixes[modId]
        if (list == null || !list.removeIf { it.equals(prefix, ignoreCase = true) }) {
            ctx.source.sendSuccess({ Component.literal("§e[IdBan] Prefix '$prefix' not found for '$modId'.") }, false)
            return 0
        }
        if (list.isEmpty()) IdBanConfig.config.clientCommandPrefixes.remove(modId)
        IdBanConfig.save()
        ctx.source.sendSuccess({ Component.literal("§a[IdBan] Removed prefix '$prefix' from '$modId'.") }, true)
        return 1
    }

    private fun cmdPrefixList(ctx: CommandContext<CommandSourceStack>): Int {
        val map = IdBanConfig.config.clientCommandPrefixes
        ctx.source.sendSuccess({ Component.literal("§6[IdBan] Client command prefixes (${map.size} mod(s)):") }, false)
        if (map.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("  §7(none)") }, false)
        } else {
            map.forEach { (modId, prefixes) ->
                ctx.source.sendSuccess({ Component.literal("  §e$modId§7: §f${prefixes.joinToString(", ")}") }, false)
            }
        }
        return 1
    }

    private fun cmdCheck(ctx: CommandContext<CommandSourceStack>, playerName: String): Int {
        val server = ctx.source.server
        val target = server.playerList.getPlayerByName(playerName)
        if (target == null) {
            ctx.source.sendSuccess({ Component.literal("§c[IdBan] Player '$playerName' not found online.") }, false)
            return 0
        }

        // ── Channel results (immediate, from join snapshot) ──────────────────
        val channels = ModDetectionManager.detectedChannels[target.uuid] ?: emptySet()
        ctx.source.sendSuccess({
            Component.literal("§6[IdBan] Channels for §e${target.name.string}§6 (${channels.size}): §f${channels.joinToString(", ").ifEmpty { "(none / vanilla)" }}")
        }, false)

        // ── Translation probes (async, results sent when all probes complete) ─
        AnvilProbeManager.scheduleCheckProbes(target, ctx.source)

        return 1
    }
}