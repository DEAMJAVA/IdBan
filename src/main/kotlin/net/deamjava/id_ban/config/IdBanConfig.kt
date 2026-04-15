package net.deamjava.id_ban.config

import com.google.gson.GsonBuilder
import net.deamjava.id_ban.IdBan
import net.fabricmc.loader.api.FabricLoader
import java.io.File


data class IdBanConfiguration(

    val bannedModIds: MutableList<String> = mutableListOf(),

    val bannedKeywords: MutableList<String> = mutableListOf(),

    val modWhitelist: MutableList<String> = mutableListOf(),

    val playerWhitelist: MutableList<String> = mutableListOf(),

    val translationProbes: MutableMap<String, String> = mutableMapOf(
        "sodium"       to "sodium.option_impact.low",
        "lithium"      to "lithium.option.mixin.gen.chunk_tickets.tooltip",
        "iris"         to "options.iris.shaderPackSelection",
        "wurst-client" to "key.wurst.zoom",
        "meteor-client" to "key.meteor-client.open-gui",
        "xaeros-minimap" to "xaeros_minimap.gui.title"
    ),

    val clientCommandPrefixes: MutableMap<String, MutableList<String>> = mutableMapOf(
        "wurst-client"  to mutableListOf("."),
        "meteor-client" to mutableListOf("."),
        "lunar-client"  to mutableListOf("/lc"),
        "badlion-client" to mutableListOf("/blc")
    ),

    val kickOnUndetectable: Boolean = false,

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

    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(GSON.toJson(config))
        } catch (e: Exception) {
            IdBan.LOGGER.error("Failed to save config", e)
        }
    }

    fun reload() {
        load()
    }
}