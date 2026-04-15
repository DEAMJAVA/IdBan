package net.deamjava.id_ban.config

import com.google.gson.GsonBuilder
import net.deamjava.id_ban.IdBan
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * The full configuration that is serialized to / from config/id-ban.json.
 */
data class IdBanConfiguration(
    // ------------------------------------------------------------------
    // Mod-ID ban list  (exact match against channel namespaces & probe IDs)
    // ------------------------------------------------------------------
    val bannedModIds: MutableList<String> = mutableListOf(),

    // ------------------------------------------------------------------
    // Keyword ban list  (substring match against channel names)
    // ------------------------------------------------------------------
    val bannedKeywords: MutableList<String> = mutableListOf(),

    // ------------------------------------------------------------------
    // Mod-ID whitelist  (players using ONLY whitelisted mods pass through)
    // If the list is EMPTY the whitelist is DISABLED entirely.
    // ------------------------------------------------------------------
    val modWhitelist: MutableList<String> = mutableListOf(),

    // ------------------------------------------------------------------
    // Player whitelist  (UUID strings or names — exempt from all checks)
    // ------------------------------------------------------------------
    val playerWhitelist: MutableList<String> = mutableListOf(),

    // ------------------------------------------------------------------
    // Translation-key probes  ─ anvil exploit
    // Each entry maps a friendly "modId" to one of its translation keys.
    // If the client resolves the key (i.e. returns != the raw key) the
    // mod is considered present.
    // Example:  "sodium" -> "sodium.option_impact.low"
    // ------------------------------------------------------------------
    val translationProbes: MutableMap<String, String> = mutableMapOf(
        // shipped with some common examples — operators can add their own
        "sodium"       to "sodium.option_impact.low",
        "lithium"      to "lithium.option.mixin.gen.chunk_tickets.tooltip",
        "iris"         to "options.iris.shaderPackSelection",
        "wurst-client" to "key.wurst.zoom",
        "meteor-client" to "key.meteor-client.open-gui",
        "xaeros-minimap" to "xaeros_minimap.gui.title"
    ),

    // ------------------------------------------------------------------
    // Client-command prefix detection
    // Maps modId → list of command prefixes that mod registers client-side.
    // When the player sends a tab-complete request starting with any prefix,
    // the mod is considered detected.
    // Examples: wurst registers ".b", ".fly" etc.; meteor registers ".help"
    // ------------------------------------------------------------------
    val clientCommandPrefixes: MutableMap<String, MutableList<String>> = mutableMapOf(
        "wurst-client"  to mutableListOf("."),          // all Wurst commands start with "."
        "meteor-client" to mutableListOf("."),          // Meteor also uses "."
        "lunar-client"  to mutableListOf("/lc"),
        "badlion-client" to mutableListOf("/blc")
    ),

    // ------------------------------------------------------------------
    // When true, a player who is NOT in the playerWhitelist is kicked if
    // their installed mods cannot be determined (vanilla client / no channels).
    // Most operators will want this FALSE.
    // ------------------------------------------------------------------
    val kickOnUndetectable: Boolean = false,

    // ------------------------------------------------------------------
    // Message shown to the kicked player.  {reason} is substituted.
    // ------------------------------------------------------------------
    val kickMessage: String = "§cYou are running a banned modification: §e{reason}",



    val probeDelayTicks: Int = 200
)

object IdBanConfig {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("id-ban.json").toFile()
    }

    var config: IdBanConfiguration = IdBanConfiguration()
        private set

    /** Load (or create) the config file. */
    fun load() {
        if (!configFile.exists()) {
            save()   // write defaults
            IdBan.LOGGER.info("Created default config at ${configFile.absolutePath}")
            return
        }
        try {
            config = GSON.fromJson(configFile.readText(), IdBanConfiguration::class.java)
                ?: IdBanConfiguration()
            IdBan.LOGGER.info("Loaded config from ${configFile.absolutePath}")
        } catch (e: Exception) {
            IdBan.LOGGER.error("Failed to load config, using defaults", e)
            config = IdBanConfiguration()
        }
    }

    /** Persist current config to disk. */
    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(GSON.toJson(config))
        } catch (e: Exception) {
            IdBan.LOGGER.error("Failed to save config", e)
        }
    }

    /** Replace running config and persist. */
    fun reload() {
        load()
    }
}