package io.zlero.cRGuild

import net.milkbowl.vault.economy.Economy
import org.bukkit.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class CRGuildPlugin : JavaPlugin(), Listener {

    lateinit var guildManager: GuildManager
        private set
    lateinit var guildListener: GuildListener
        private set
    lateinit var economy: Economy
        private set

    val surrenderConfirmSet = mutableSetOf<UUID>()

    // ─── msg() : config.yml 에서 메시지를 읽어 변수 치환 후 반환 ─────────
    //
    //  사용법:
    //    plugin.msg("war.declare.broadcast",
    //        "attack_guild" to "홍길동길드",
    //        "defend_guild" to "방어길드"
    //    )
    //
    //  config.yml 에서는:
    //    war.declare.broadcast: "&f%attack_guild% 길드가 %defend_guild% 길드에 선전포고!"
    //
    //  · % 로 감싼 변수명을 pairs 의 값으로 치환합니다.
    //  · & 색상 코드 자동 변환 ( &a → §a )
    //  · 값이 List 이면 "\n" 으로 합쳐서 반환 (다중 줄 지원)
    //  · 키 누락 시 "§c[msg:<key>]" 반환

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

    // ─── onEnable / onDisable ─────────────────────────────────────────────

    override fun onEnable() {
        instance = this
        saveDefaultConfig()

        // storage 설정이 없으면 yaml 기본값 기록
        if (!config.isSet("storage.type")) {
            config.set("storage.type", "yaml")
            config.set("storage.host", "localhost")
            config.set("storage.port", 3306)
            config.set("storage.database", "crguild")
            config.set("storage.username", "root")
            config.set("storage.password", "")
            saveConfig()
        }


        if (!setupEconomy()) {
            logger.severe("Vault 또는 경제 플러그인을 찾을 수 없습니다. 플러그인을 비활성화합니다.")
            server.pluginManager.disablePlugin(this)
            return
        }

        guildManager = GuildManager(this)
        guildManager.loadAll()
        guildManager.startTaxScheduler()

        guildListener = GuildListener(this)
        server.pluginManager.registerEvents(guildListener, this)
        server.pluginManager.registerEvents(TeleportGui, this)
        server.pluginManager.registerEvents(this, this)

        getCommand("길드")?.let {
            val cmd = GuildCommand(this)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        getCommand("전쟁관리")?.let {
            val cmd = WarAdminCommand(this)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        getCommand("길드관리")?.let {
            val cmd = GuildAdminCommand(this)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        getCommand("전쟁")?.let {
            val cmd = WarCommand(this)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        logger.info("CRGuild 활성화 완료! (길드 ${guildManager.getAllGuilds().size}개 로드)")
    }

    override fun onDisable() {
        if (::guildManager.isInitialized) {
            guildManager.stopTaxScheduler()
            guildManager.clearAllWarBossBars()
            guildManager.saveAll()
        }
        logger.info("CRGuild 비활성화 완료.")
    }

    // ─── 플레이어 퇴장 시 보스바 처리 (접속은 GuildListener.onPlayerJoin 에서 처리) ─

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val guild  = guildManager.getGuildByPlayer(player)
        if (guild != null) {
            // 보스바에서 해당 플레이어만 제거 (길드 전체 보스바는 유지)
            guildManager.removePlayerFromWarBossBar(player, guild.name)
        }
        // 퇴장 시 길드 채팅 토글 자동 해제
        guildListener.guildChatPlayers.remove(player.uniqueId)
    }

    // ─── /전쟁 항복확인 2단계 처리 ───────────────────────────────────────

    @EventHandler
    fun onSurrenderConfirm(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val msgText = event.message.trim()
        if (msgText != "/전쟁 항복확인" && msgText != "/길드 항복확인") return
        if (player.uniqueId !in surrenderConfirmSet) return

        event.isCancelled = true
        surrenderConfirmSet.remove(player.uniqueId)

        val myGuild = guildManager.getGuildByPlayer(player)
        if (myGuild == null) { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }

        val targetName = myGuild.warTarget ?: ""
        guildManager.surrender(myGuild)
            .onSuccess {
                server.broadcastMessage(msg("war.surrender.broadcast", "loser_guild" to myGuild.name, "winner_guild" to targetName))
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── Vault 연동 ───────────────────────────────────────────────────────

    private fun setupEconomy(): Boolean {
        if (server.pluginManager.getPlugin("Vault") == null) return false
        val rsp = server.servicesManager.getRegistration(Economy::class.java) ?: return false
        economy = rsp.provider
        return true
    }

    companion object {
        lateinit var instance: CRGuildPlugin
            private set
    }
}