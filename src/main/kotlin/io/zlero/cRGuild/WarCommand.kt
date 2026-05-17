package io.zlero.cRGuild

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class WarCommand(private val plugin: CRGuildPlugin) : CommandExecutor, TabCompleter {

    private val gm get() = plugin.guildManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("§c플레이어만 사용 가능합니다."); return true }
        if (args.isEmpty()) { sendHelp(sender); return true }

        when (args[0]) {
            "선포" -> cmdDeclare(sender, args)
            "항복" -> cmdSurrender(sender)
            "현황" -> cmdStatus(sender)
            else   -> sendHelp(sender)
        }
        return true
    }

    // ─── /전쟁 선포 [길드명] ──────────────────────────────────────────────
    // ★ 수정: 전쟁 선포권 아이템 소지 필요

    private fun cmdDeclare(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage(plugin.msg("war.declare.usage")); return }

        val myGuild = gm.getGuildByPlayer(player) ?: run { player.sendMessage(plugin.msg("common.no-guild")); return }
        if (!myGuild.isMaster(player.uniqueId)) { player.sendMessage(plugin.msg("war.declare.not-master")); return }

        val targetName = args[1]
        if (targetName == myGuild.name) { player.sendMessage(plugin.msg("war.declare.self")); return }

        val targetGuild = gm.getGuild(targetName) ?: run { player.sendMessage(plugin.msg("common.guild-not-found")); return }

        // ★ 전쟁 선포권 확인
        val inv = player.inventory
        val ticketSlot = (0 until inv.size).firstOrNull { slot ->
            GuildItems.isWarTicket(inv.getItem(slot))
        }
        if (ticketSlot == null) {
            player.sendMessage("§c전쟁 선포를 하려면 §6§l전쟁 선포권§c이 필요합니다.")
            return
        }

        // 선포권 1개 소모
        val ticketItem = inv.getItem(ticketSlot)!!
        if (ticketItem.amount > 1) ticketItem.amount -= 1
        else inv.setItem(ticketSlot, null)

        gm.declareWar(myGuild, targetGuild)
            .onSuccess {
                plugin.server.broadcastMessage(
                    plugin.msg("war.declare.broadcast", "attack_guild" to myGuild.name, "defend_guild" to targetGuild.name)
                )
                player.sendMessage("§7전쟁 선포권 1개가 소모되었습니다.")
            }
            .onFailure {
                // 선포 실패 시 선포권 반환
                player.inventory.addItem(GuildItems.createWarTicket(1))
                player.sendMessage("§c${it.message}")
                player.sendMessage("§7전쟁 선포권이 반환되었습니다.")
            }
    }

    // ─── /전쟁 항복 ─────────────────────────────────────────────────────
    // ★ 수정: 선포 당한 수비 길드만 항복 가능

    private fun cmdSurrender(player: Player) {
        val myGuild = gm.getGuildByPlayer(player) ?: run { player.sendMessage(plugin.msg("common.no-guild")); return }
        if (!myGuild.isMaster(player.uniqueId)) { player.sendMessage(plugin.msg("war.surrender.not-master")); return }
        if (myGuild.warTarget == null) { player.sendMessage(plugin.msg("war.surrender.not-at-war")); return }

        if (!gm.isDefender(myGuild)) {
            player.sendMessage("§c전쟁을 선포한 쪽(공격 길드)은 항복할 수 없습니다.")
            player.sendMessage("§7항복은 전쟁 선포를 당한 길드만 가능합니다.")
            return
        }

        val cost = gm.getSurrenderCost(myGuild)
        if (myGuild.treasury < cost) {
            player.sendMessage("§c국고 부족으로 항복이 불가합니다. 항복 비용: §f${gm.formatMoney(cost)}원 §c(${myGuild.surrenderCount + 1}회차)")
            player.sendMessage("§7현재 국고: §f${gm.formatMoney(myGuild.treasury)}원")
            return
        }

        plugin.surrenderConfirmSet.add(player.uniqueId)
        player.sendMessage("§e§l[항복 확인] §r§e항복 비용: §f${gm.formatMoney(cost)}원 §e(${myGuild.surrenderCount + 1}회차)")
        player.sendMessage("§7확인하려면 30초 내에 §f/전쟁 항복확인 §7을 입력하세요.")

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (plugin.surrenderConfirmSet.remove(player.uniqueId)) {
                player.sendMessage("§7항복 확인 시간이 만료되었습니다.")
            }
        }, 20L * 30)
    }

    // ─── /전쟁 현황 ───────────────────────────────────────────────────────

    private fun cmdStatus(player: Player) {
        val all = gm.getAllGuilds().filter { it.warTarget != null }
        if (all.isEmpty()) { player.sendMessage("§7현재 진행 중인 전쟁이 없습니다."); return }

        player.sendMessage("§8§l═══ §c전쟁 현황 §8§l═══")
        val shown = mutableSetOf<String>()
        all.forEach { guild ->
            val key = listOf(guild.name, guild.warTarget!!).sorted().joinToString("vs")
            if (key in shown) return@forEach
            shown.add(key)

            if (guild.warActive) {
                player.sendMessage("  §c⚔ §f${guild.name} §7vs §f${guild.warTarget} §8[전쟁 진행 중]")
            } else {
                val elapsed   = (System.currentTimeMillis() - guild.warDeclaredAt) / 1000
                val remaining = (60 * 5 - elapsed).coerceAtLeast(0)
                val mins = remaining / 60; val secs = remaining % 60
                player.sendMessage("  §e⚠ §f${guild.name} §7vs §f${guild.warTarget} §8[준비 중 - §e%d:%02d §8남음]".format(mins, secs))
            }
        }
    }

    private fun sendHelp(player: Player) {
        player.sendMessage("§8§l═══ §c/전쟁 명령어 §8§l═══")
        player.sendMessage("§7/전쟁 선포 §f[길드명] §8- 전쟁 선포 (§6전쟁 선포권§8 필요, 5분 후 시작, 길드장)")
        player.sendMessage("§7/전쟁 항복 §8- 항복 선언 (선포 당한 길드만 가능, 국고 차감, 길드장)")
        player.sendMessage("§7/전쟁 현황 §8- 진행 중인 전쟁 목록")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when {
            args.size == 1 -> listOf("선포", "항복", "현황").filter { it.startsWith(args[0]) }
            args.size == 2 && args[0] == "선포" -> gm.getAllGuildNames().filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}