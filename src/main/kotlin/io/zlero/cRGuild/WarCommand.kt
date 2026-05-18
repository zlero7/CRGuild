package io.zlero.cRGuild

import io.zlero.cRFramework.command.CommandContext
import io.zlero.cRFramework.command.annotation.Command
import io.zlero.cRFramework.core.component.annotation.Component
import io.zlero.cRFramework.listener.annotation.Subscribe
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.UUID

@Component
class WarCommand(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager,
    private val config: GuildConfig
) {

    // 항복 2단계 확인 대기 목록 (CRGuild 에서 이전)
    private val surrenderConfirmSet = mutableSetOf<UUID>()

    // ─── /전쟁 ───────────────────────────────────────────────────────────

    @Command("전쟁", description = "전쟁 명령어")
    fun onWar(ctx: CommandContext) {
        val player = ctx.player   // 플레이어가 아니면 CommandException 자동 발생
        if (ctx.size == 0) { sendHelp(player); return }
        when (ctx.args[0]) {
            "선포" -> cmdDeclare(player, ctx.args)
            "항복" -> cmdSurrender(player)
            "현황" -> cmdStatus(player)
            else   -> sendHelp(player)
        }
    }

    // ─── /전쟁 선포 [길드명] ─────────────────────────────────────────────

    private fun cmdDeclare(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage(config.msg("war.declare.usage")); return }

        val myGuild = gm.getGuildByPlayer(player) ?: run { player.sendMessage(config.msg("common.no-guild")); return }
        if (!myGuild.isMaster(player.uniqueId)) { player.sendMessage(config.msg("war.declare.not-master")); return }

        val targetName = args[1]
        if (targetName == myGuild.name) { player.sendMessage(config.msg("war.declare.self")); return }

        val targetGuild = gm.getGuild(targetName) ?: run { player.sendMessage(config.msg("common.guild-not-found")); return }

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
                    config.msg("war.declare.broadcast", "attack_guild" to myGuild.name, "defend_guild" to targetGuild.name)
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

    private fun cmdSurrender(player: Player) {
        val myGuild = gm.getGuildByPlayer(player) ?: run { player.sendMessage(config.msg("common.no-guild")); return }
        if (!myGuild.isMaster(player.uniqueId)) { player.sendMessage(config.msg("war.surrender.not-master")); return }
        if (myGuild.warTarget == null) { player.sendMessage(config.msg("war.surrender.not-at-war")); return }

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

        surrenderConfirmSet.add(player.uniqueId)
        player.sendMessage("§e§l[항복 확인] §r§e항복 비용: §f${gm.formatMoney(cost)}원 §e(${myGuild.surrenderCount + 1}회차)")
        player.sendMessage("§7확인하려면 30초 내에 §f/전쟁 항복확인 §7을 입력하세요.")

        plugin.scheduler.runAfterSeconds(30) {
            if (surrenderConfirmSet.remove(player.uniqueId)) {
                player.sendMessage("§7항복 확인 시간이 만료되었습니다.")
            }
        }
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

    // ─── /전쟁 항복확인 2단계 처리 (CRGuild.onSurrenderConfirm 에서 이전) ─

    @Subscribe
    fun onSurrenderConfirm(event: PlayerCommandPreprocessEvent) {
        val player  = event.player
        val msgText = event.message.trim()
        if (msgText != "/전쟁 항복확인" && msgText != "/길드 항복확인") return
        if (player.uniqueId !in surrenderConfirmSet) return

        event.isCancelled = true
        surrenderConfirmSet.remove(player.uniqueId)

        val myGuild = gm.getGuildByPlayer(player)
        if (myGuild == null) { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }

        val targetName = myGuild.warTarget ?: ""
        gm.surrender(myGuild)
            .onSuccess {
                plugin.server.broadcastMessage(
                    config.msg("war.surrender.broadcast", "loser_guild" to myGuild.name, "winner_guild" to targetName)
                )
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }
}
