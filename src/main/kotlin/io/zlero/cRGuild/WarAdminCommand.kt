package io.zlero.cRGuild

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * /전쟁관리 - OP 전용 전쟁 관리 명령어
 *
 * /전쟁관리 시작 <길드A> <길드B>          - 준비 시간 건너뛰고 즉시 전쟁 시작
 * /전쟁관리 무승부 <길드A> <길드B>         - 진행 중인 전쟁을 무승부로 강제 종료
 * /전쟁관리 현황                           - 전쟁 목록 조회
 * ★ /전쟁관리 선포권 <플레이어> [수량]     - 플레이어에게 전쟁 선포권 지급
 */
class WarAdminCommand(private val plugin: CRGuildPlugin) : CommandExecutor, TabCompleter {

    private val gm get() = plugin.guildManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("crguild.admin")) { sender.sendMessage("§c이 명령어는 관리자만 사용할 수 있습니다."); return true }
        if (args.isEmpty()) { sendHelp(sender); return true }

        when (args[0]) {
            "시작"   -> cmdForceStart(sender, args)
            "무승부" -> cmdForceDraw(sender, args)
            "현황"   -> cmdStatus(sender)
            "선포권" -> cmdGiveWarTicket(sender, args)
            else     -> sendHelp(sender)
        }
        return true
    }

    private fun cmdForceStart(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) { sender.sendMessage("§c사용법: /전쟁관리 시작 [길드A] [길드B]"); return }
        gm.forceStartWar(args[1], args[2])
            .onSuccess {
                sender.sendMessage("§a${args[1]} vs ${args[2]} 전쟁을 즉시 시작했습니다.")
                plugin.server.broadcastMessage("§8[§4⚔ 관리자§8] §f${args[1]} §7vs §f${args[2]} §7전쟁이 즉시 시작되었습니다!")
            }
            .onFailure { sender.sendMessage("§c${it.message}") }
    }

    private fun cmdForceDraw(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) { sender.sendMessage("§c사용법: /전쟁관리 무승부 [길드A] [길드B]"); return }
        gm.forceDraw(args[1], args[2])
            .onSuccess { sender.sendMessage("§a${args[1]} vs ${args[2]} 전쟁을 무승부로 종료했습니다.") }
            .onFailure { sender.sendMessage("§c${it.message}") }
    }

    private fun cmdStatus(sender: CommandSender) {
        val shown = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        gm.getAllGuilds().filter { it.warTarget != null }.forEach { guild ->
            val key = listOf(guild.name, guild.warTarget!!).sorted().joinToString("vs")
            if (key !in shown) {
                shown.add(key)
                val status = if (guild.warActive) "§c진행 중" else "§e준비 중"
                lines.add("  $status §f${guild.name} §7vs §f${guild.warTarget}")
            }
        }
        if (lines.isEmpty()) { sender.sendMessage("§7현재 전쟁이 없습니다."); return }
        sender.sendMessage("§8§l═══ §c전쟁 현황 §8§l═══")
        lines.forEach { sender.sendMessage(it) }
    }

    // ★ 전쟁 선포권 지급
    private fun cmdGiveWarTicket(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) { sender.sendMessage("§c사용법: /전쟁관리 선포권 [플레이어] [수량]"); return }
        val target = Bukkit.getPlayer(args[1]) ?: run { sender.sendMessage("§c해당 플레이어가 온라인 상태가 아닙니다."); return }
        val amount = if (args.size >= 3) args[2].toIntOrNull() ?: 1 else 1
        if (amount !in 1..64) { sender.sendMessage("§c수량은 1~64 사이여야 합니다."); return }

        target.inventory.addItem(GuildItems.createWarTicket(amount))
        sender.sendMessage("§a${target.name}님에게 전쟁 선포권 §f${amount}개§a를 지급했습니다.")
        target.sendMessage("§6§l[전쟁 선포권] §r§6관리자로부터 전쟁 선포권 §f${amount}개§6를 받았습니다.")
        target.sendMessage("§7/전쟁 선포 명령어 사용 시 자동으로 소모됩니다.")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§8§l═══ §c/전쟁관리 명령어 §8§l═══")
        sender.sendMessage("§7/전쟁관리 시작 §f[길드A] [길드B] §8- 준비 시간 건너뛰고 즉시 전쟁 시작")
        sender.sendMessage("§7/전쟁관리 무승부 §f[길드A] [길드B] §8- 전쟁 무승부 강제 종료")
        sender.sendMessage("§7/전쟁관리 현황 §8- 전쟁 목록")
        sender.sendMessage("§7/전쟁관리 선포권 §f[플레이어] [수량] §8- 전쟁 선포권 지급")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("crguild.admin")) return emptyList()
        val guildNames = gm.getAllGuildNames().toList()
        val playerNames = Bukkit.getOnlinePlayers().map { it.name }
        return when {
            args.size == 1 -> listOf("시작", "무승부", "현황", "선포권").filter { it.startsWith(args[0]) }
            args.size == 2 && args[0] in listOf("시작", "무승부") -> guildNames.filter { it.startsWith(args[1]) }
            args.size == 3 && args[0] in listOf("시작", "무승부") -> guildNames.filter { it.startsWith(args[2]) && it != args[1] }
            args.size == 2 && args[0] == "선포권" -> playerNames.filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}