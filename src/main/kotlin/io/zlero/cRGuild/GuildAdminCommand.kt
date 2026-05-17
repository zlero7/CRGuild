package io.zlero.cRGuild

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * /길드관리 - OP 전용 길드 관리 명령어
 *
 * /길드관리 해산 <길드명>              - 길드 강제 해산
 * /길드관리 정보 <길드명>              - 길드 상세 정보 (관리자용)
 * /길드관리 국고 <길드명> <금액>       - 국고 강제 입금
 * /길드관리 레벨 <길드명> <레벨(1-5)> - 길드 레벨 강제 설정
 * /길드관리 추방 <플레이어>            - 특정 플레이어 길드에서 강제 추방
 * /길드관리 목록                       - 전체 길드 목록 (상세)
 */
class GuildAdminCommand(private val plugin: CRGuildPlugin) : CommandExecutor, TabCompleter {

    private val gm get() = plugin.guildManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("crguild.admin")) { sender.sendMessage("§c이 명령어는 관리자만 사용할 수 있습니다."); return true }
        if (args.isEmpty()) { sendHelp(sender); return true }

        when (args[0]) {
            "해산" -> cmdDisband(sender, args)
            "정보" -> cmdInfo(sender, args)
            "국고" -> cmdTreasury(sender, args)
            "레벨" -> cmdLevel(sender, args)
            "추방" -> cmdKick(sender, args)
            "목록" -> cmdList(sender)
            else   -> sendHelp(sender)
        }
        return true
    }

    private fun cmdDisband(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) { sender.sendMessage("§c사용법: /길드관리 해산 [길드명]"); return }
        val guild = gm.getGuild(args[1]) ?: run { sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        if (guild.warActive) {
            val target = gm.getGuild(guild.warTarget ?: "")
            if (target != null) gm.endWar(guild, target)
        }
        gm.disbandGuild(guild.name)
        sender.sendMessage("§a${args[1]} 길드를 강제 해산했습니다.")
        plugin.server.broadcastMessage("§8[§c관리자§8] §f${args[1]} §7길드가 관리자에 의해 해산되었습니다.")
    }

    private fun cmdInfo(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) { sender.sendMessage("§c사용법: /길드관리 정보 [길드명]"); return }
        val g = gm.getGuild(args[1]) ?: run { sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val masterName = Bukkit.getOfflinePlayer(g.master).name ?: "알 수 없음"
        sender.sendMessage("§8§l═══ §6[관리자] ${g.name} §8§l═══")
        sender.sendMessage("  §7레벨: §f${g.level}  §7성 수: §f${g.beacons.size}")
        sender.sendMessage("  §7길드장: §f$masterName")
        sender.sendMessage("  §7인원: §f${g.totalMembers()}/${g.maxMembers()}")
        sender.sendMessage("  §7국고: §f${gm.formatMoney(g.treasury)}원")
        sender.sendMessage("  §7세금 미납: §f${g.taxMissedWeeks}/3회")
        val warInfo = when {
            g.warActive         -> "§c⚔ 전쟁 중 (vs ${g.warTarget})"
            g.warTarget != null -> "§e준비 중 (vs ${g.warTarget})"
            else                -> "§a평화"
        }
        sender.sendMessage("  §7전쟁: $warInfo")
        // 온라인 카운트
        val onlineCount = gm.getOnlineCount(g)
        sender.sendMessage("  §7온라인: §a${onlineCount}§7/§f${g.totalMembers()}명")
    }

    private fun cmdTreasury(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) { sender.sendMessage("§c사용법: /길드관리 국고 [길드명] [금액]"); return }
        val guild = gm.getGuild(args[1]) ?: run { sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val amount = args[2].replace(",", "").toLongOrNull() ?: run { sender.sendMessage("§c올바른 금액을 입력하세요."); return }
        guild.treasury += amount
        gm.saveGuild(guild)
        sender.sendMessage("§a${guild.name} 길드 국고에 §f${gm.formatMoney(amount)}원§a 입금. 현재: §f${gm.formatMoney(guild.treasury)}원")
    }

    private fun cmdLevel(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) { sender.sendMessage("§c사용법: /길드관리 레벨 [길드명] [1-5]"); return }
        val guild = gm.getGuild(args[1]) ?: run { sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val level = args[2].toIntOrNull() ?: run { sender.sendMessage("§c올바른 레벨(1-5)을 입력하세요."); return }
        if (level !in 1..5) { sender.sendMessage("§c레벨은 1~5 사이여야 합니다."); return }
        guild.level = level
        gm.saveGuild(guild)
        sender.sendMessage("§a${guild.name} 길드 레벨을 §fLv.${level}§a으로 설정했습니다.")
        gm.broadcastToGuild(guild, "§6§l[관리자] §r§e길드 레벨이 §fLv.${level}§e으로 조정되었습니다.")
    }

    private fun cmdKick(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) { sender.sendMessage("§c사용법: /길드관리 추방 [플레이어]"); return }
        val target = Bukkit.getOfflinePlayer(args[1])
        val guild  = gm.getGuildByPlayer(target.uniqueId) ?: run { sender.sendMessage("§c해당 플레이어가 길드에 없습니다."); return }
        if (target.uniqueId == guild.master) { sender.sendMessage("§c길드장은 추방할 수 없습니다. 길드 해산을 사용하세요."); return }
        gm.kickMember(guild, target.uniqueId)
        sender.sendMessage("§a${args[1]}님을 ${guild.name} 길드에서 강제 추방했습니다.")
        Bukkit.getPlayer(target.uniqueId)?.sendMessage("§c관리자에 의해 ${guild.name} 길드에서 추방되었습니다.")
    }

    private fun cmdList(sender: CommandSender) {
        val guilds = gm.getAllGuilds()
        if (guilds.isEmpty()) { sender.sendMessage("§7길드가 없습니다."); return }
        sender.sendMessage("§8§l═══ §6[관리자] 길드 목록 §8§l═══")
        guilds.forEach { g ->
            val war = if (g.warActive) " §c⚔" else if (g.warTarget != null) " §e⚠" else ""
            val online = gm.getOnlineCount(g)
            sender.sendMessage("  §f${g.name} §8Lv.${g.level} §7인원:§f${g.totalMembers()} §7온라인:§a${online} §7국고:§f${gm.formatMoney(g.treasury)}원$war")
        }
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§8§l═══ §c/길드관리 명령어 §8§l═══")
        sender.sendMessage("§7/길드관리 해산 §f[길드명] §8- 길드 강제 해산")
        sender.sendMessage("§7/길드관리 정보 §f[길드명] §8- 길드 상세 정보")
        sender.sendMessage("§7/길드관리 국고 §f[길드명] [금액] §8- 국고 강제 입금")
        sender.sendMessage("§7/길드관리 레벨 §f[길드명] [1-5] §8- 레벨 강제 설정")
        sender.sendMessage("§7/길드관리 추방 §f[플레이어] §8- 길드에서 강제 추방")
        sender.sendMessage("§7/길드관리 목록 §8- 전체 길드 목록 (상세)")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("crguild.admin")) return emptyList()
        val guildNames = gm.getAllGuildNames().toList()
        return when {
            args.size == 1 -> listOf("해산","정보","국고","레벨","추방","목록").filter { it.startsWith(args[0]) }
            args.size == 2 && args[0] in listOf("해산","정보","국고","레벨") -> guildNames.filter { it.startsWith(args[1]) }
            args.size == 2 && args[0] == "추방" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}