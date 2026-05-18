package io.zlero.cRGuild

import io.zlero.cRFramework.command.CommandContext
import io.zlero.cRFramework.command.annotation.Command
import io.zlero.cRFramework.core.component.annotation.Component
import org.bukkit.Bukkit

@Component
class WarAdminCommand(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager
) {

    @Command("전쟁관리", description = "전쟁 관리 명령어 (관리자 전용)", permission = "crguild.admin")
    fun onWarAdmin(ctx: CommandContext) {
        if (ctx.size == 0) { sendHelp(ctx); return }
        when (ctx.args[0]) {
            "시작"   -> cmdForceStart(ctx)
            "무승부" -> cmdForceDraw(ctx)
            "현황"   -> cmdStatus(ctx)
            "선포권" -> cmdGiveWarTicket(ctx)
            else     -> sendHelp(ctx)
        }
    }

    private fun cmdForceStart(ctx: CommandContext) {
        if (ctx.size < 3) { ctx.sender.sendMessage("§c사용법: /전쟁관리 시작 [길드A] [길드B]"); return }
        gm.forceStartWar(ctx.args[1], ctx.args[2])
            .onSuccess {
                ctx.sender.sendMessage("§a${ctx.args[1]} vs ${ctx.args[2]} 전쟁을 즉시 시작했습니다.")
                plugin.server.broadcastMessage("§8[§4⚔ 관리자§8] §f${ctx.args[1]} §7vs §f${ctx.args[2]} §7전쟁이 즉시 시작되었습니다!")
            }
            .onFailure { ctx.sender.sendMessage("§c${it.message}") }
    }

    private fun cmdForceDraw(ctx: CommandContext) {
        if (ctx.size < 3) { ctx.sender.sendMessage("§c사용법: /전쟁관리 무승부 [길드A] [길드B]"); return }
        gm.forceDraw(ctx.args[1], ctx.args[2])
            .onSuccess { ctx.sender.sendMessage("§a${ctx.args[1]} vs ${ctx.args[2]} 전쟁을 무승부로 종료했습니다.") }
            .onFailure { ctx.sender.sendMessage("§c${it.message}") }
    }

    private fun cmdStatus(ctx: CommandContext) {
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
        if (lines.isEmpty()) { ctx.sender.sendMessage("§7현재 전쟁이 없습니다."); return }
        ctx.sender.sendMessage("§8§l═══ §c전쟁 현황 §8§l═══")
        lines.forEach { ctx.sender.sendMessage(it) }
    }

    private fun cmdGiveWarTicket(ctx: CommandContext) {
        if (ctx.size < 2) { ctx.sender.sendMessage("§c사용법: /전쟁관리 선포권 [플레이어] [수량]"); return }
        val target = Bukkit.getPlayer(ctx.args[1]) ?: run { ctx.sender.sendMessage("§c해당 플레이어가 온라인 상태가 아닙니다."); return }
        val amount = if (ctx.size >= 3) ctx.args[2].toIntOrNull() ?: 1 else 1
        if (amount !in 1..64) { ctx.sender.sendMessage("§c수량은 1~64 사이여야 합니다."); return }

        target.inventory.addItem(GuildItems.createWarTicket(amount))
        ctx.sender.sendMessage("§a${target.name}님에게 전쟁 선포권 §f${amount}개§a를 지급했습니다.")
        target.sendMessage("§6§l[전쟁 선포권] §r§6관리자로부터 전쟁 선포권 §f${amount}개§6를 받았습니다.")
        target.sendMessage("§7/전쟁 선포 명령어 사용 시 자동으로 소모됩니다.")
    }

    private fun sendHelp(ctx: CommandContext) {
        ctx.sender.sendMessage("§8§l═══ §c/전쟁관리 명령어 §8§l═══")
        ctx.sender.sendMessage("§7/전쟁관리 시작 §f[길드A] [길드B] §8- 준비 시간 건너뛰고 즉시 전쟁 시작")
        ctx.sender.sendMessage("§7/전쟁관리 무승부 §f[길드A] [길드B] §8- 전쟁 무승부 강제 종료")
        ctx.sender.sendMessage("§7/전쟁관리 현황 §8- 전쟁 목록")
        ctx.sender.sendMessage("§7/전쟁관리 선포권 §f[플레이어] [수량] §8- 전쟁 선포권 지급")
    }
}
