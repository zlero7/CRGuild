package io.zlero.cRGuild

import io.zlero.cRFramework.yaml.CRYamlConfiguration
import io.zlero.cRFramework.yaml.annotation.Configuration
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin

@Configuration("config.yml")
class GuildConfig(plugin: JavaPlugin) : CRYamlConfiguration(plugin, "config.yml") {

    init { plugin.saveDefaultConfig() }

    val storageType: String get() = string("storage.type", "yaml")
    val storageHost: String get() = string("storage.host", "localhost")
    val storagePort: Int get() = int("storage.port", 3306)
    val storageDatabase: String get() = string("storage.database", "crguild")
    val storageUsername: String get() = string("storage.username", "root")
    val storagePassword: String get() = string("storage.password", "")

    val declareCost: Long get() = long("guild.declare-cost", 1_000_000L)
    val warMinOnline: Int get() = int("war.min-online", 4)
    val surrenderBaseCost: Long get() = long("war.surrender-base-cost", 1_000_000L)
    val surrenderCostIncrease: Long get() = long("war.surrender-cost-increase", 1_000_000L)

    fun msg(key: String, vararg placeholders: Pair<String, Any>): String {
        val path = "messages.$key"
        val raw: String = when (val value = config.get(path)) {
            is String  -> value
            is List<*> -> value.joinToString("\n") { it.toString() }
            null       -> return "§c[msg:$key]"
            else       -> value.toString()
        }
        var text = ChatColor.translateAlternateColorCodes('&', raw)
        placeholders.forEach { (k, v) -> text = text.replace("%${k}%", v.toString()) }
        return text
    }
}
