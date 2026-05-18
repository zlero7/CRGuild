package io.zlero.cRGuild

import io.zlero.cRFramework.command.CommandContext
import io.zlero.cRFramework.command.annotation.Command
import io.zlero.cRFramework.core.component.annotation.Component
import org.bukkit.Bukkit

@Component
class GuildAdminCommand(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager
) {

    @Command("길드관리", description = "길드 관리 명령어 (관리자 전용)", permission = "crguild.admin")
    fun onGuildAdmin(ctx: CommandContext) {
        if (ctx.size == 0) { sendHelp(ctx); return }
        when (ctx.args[0]) {
            "해산" -> cmdDisband(ctx)
            "정보" -> cmdInfo(ctx)
            "국고" -> cmdTreasury(ctx)
            "레벨" -> cmdLevel(ctx)
            "추방" -> cmdKick(ctx)
            "목록" -> cmdList(ctx)
            else   -> sendHelp(ctx)
        }
    }

    private fun cmdDisband(ctx: CommandContext) {
        if (ctx.size < 2) { ctx.sender.sendMessage("§c사용법: /길드관리 해산 [길드명]"); return }
        val guild = gm.getGuild(ctx.args[1]) ?: run { ctx.sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        if (guild.warActive) {
            val target = gm.getGuild(guild.warTarget ?: "")
            if (target != null) gm.endWar(guild, target)
        }
        gm.disbandGuild(guild.name)
        ctx.sender.sendMessage("§a${ctx.args[1]} 길드를 강제 해산했습니다.")
        plugin.server.broadcastMessage("§8[§c관리자§8] §f${ctx.args[1]} §7길드가 관리자에 의해 해산되었습니다.")
    }

    private fun cmdInfo(ctx: CommandContext) {
        if (ctx.size < 2) { ctx.sender.sendMessage("§c사용법: /길드관리 정보 [길드명]"); return }
        val g = gm.getGuild(ctx.args[1]) ?: run { ctx.sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val masterName = Bukkit.getOfflinePlayer(g.master).name ?: "알 수 없음"
        ctx.sender.sendMessage("§8§l═══ §6[관리자] ${g.name} §8§l═══")
        ctx.sender.sendMessage("  §7레벨: §f${g.level}  §7성 수: §f${g.beacons.size}")
        ctx.sender.sendMessage("  §7길드장: §f$masterName")
        ctx.sender.sendMessage("  §7인원: §f${g.totalMembers()}/${g.maxMembers()}")
        ctx.sender.sendMessage("  §7국고: §f${gm.formatMoney(g.treasury)}원")
        ctx.sender.sendMessage("  §7세금 미납: §f${g.taxMissedWeeks}/3회")
        val warInfo = when {
            g.warActive         -> "§c⚔ 전쟁 중 (vs ${g.warTarget})"
            g.warTarget != null -> "§e준비 중 (vs ${g.warTarget})"
            else                -> "§a평화"
        }
        ctx.sender.sendMessage("  §7전쟁: $warInfo")
        ctx.sender.sendMessage("  §7온라인: §a${gm.getOnlineCount(g)}§7/§f${g.totalMembers()}명")
    }

    private fun cmdTreasury(ctx: CommandContext) {
        if (ctx.size < 3) { ctx.sender.sendMessage("§c사용법: /길드관리 국고 [길드명] [금액]"); return }
        val guild  = gm.getGuild(ctx.args[1]) ?: run { ctx.sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val amount = ctx.args[2].replace(",", "").toLongOrNull() ?: run { ctx.sender.sendMessage("§c올바른 금액을 입력하세요."); return }
        guild.treasury += amount
        gm.saveGuild(guild)
        ctx.sender.sendMessage("§a${guild.name} 길드 국고에 §f${gm.formatMoney(amount)}원§a 입금. 현재: §f${gm.formatMoney(guild.treasury)}원")
    }

    private fun cmdLevel(ctx: CommandContext) {
        if (ctx.size < 3) { ctx.sender.sendMessage("§c사용법: /길드관리 레벨 [길드명] [1-5]"); return }
        val guild = gm.getGuild(ctx.args[1]) ?: run { ctx.sender.sendMessage("§c존재하지 않는 길드입니다."); return }
        val level = ctx.args[2].toIntOrNull() ?: run { ctx.sender.sendMessage("§c올바른 레벨(1-5)을 입력하세요."); return }
        if (level !in 1..5) { ctx.sender.sendMessage("§c레벨은 1~5 사이여야 합니다."); return }
        guild.level = level
        gm.saveGuild(guild)
        ctx.sender.sendMessage("§a${guild.name} 길드 레벨을 §fLv.${level}§a으로 설정했습니다.")
        gm.broadcastToGuild(guild, "§6§l[관리자] §r§e길드 레벨이 §fLv.${level}§e으로 조정되었습니다.")
    }

    private fun cmdKick(ctx: CommandContext) {
        if (ctx.size < 2) { ctx.sender.sendMessage("§c사용법: /길드관리 추방 [플레이어]"); return }
        val target = Bukkit.getOfflinePlayer(ctx.args[1])
        val guild  = gm.getGuildByPlayer(target.uniqueId) ?: run { ctx.sender.sendMessage("§c해당 플레이어가 길드에 없습니다."); return }
        if (target.uniqueId == guild.master) { ctx.sender.sendMessage("§c길드장은 추방할 수 없습니다. 길드 해산을 사용하세요."); return }
        gm.kickMember(guild, target.uniqueId)
        ctx.sender.sendMessage("§a${ctx.args[1]}님을 ${guild.name} 길드에서 강제 추방했습니다.")
        Bukkit.getPlayer(target.uniqueId)?.sendMessage("§c관리자에 의해 ${guild.name} 길드에서 추방되었습니다.")
    }

    private fun cmdList(ctx: CommandContext) {
        val guilds = gm.getAllGuilds()
        if (guilds.isEmpty()) { ctx.sender.sendMessage("§7길드가 없습니다."); return }
        ctx.sender.sendMessage("§8§l═══ §6[관리자] 길드 목록 §8§l═══")
        guilds.forEach { g ->
            val war    = if (g.warActive) " §c⚔" else if (g.warTarget != null) " §e⚠" else ""
            val online = gm.getOnlineCount(g)
            ctx.sender.sendMessage("  §f${g.name} §8Lv.${g.level} §7인원:§f${g.totalMembers()} §7온라인:§a${online} §7국고:§f${gm.formatMoney(g.treasury)}원$war")
        }
    }

    private fun sendHelp(ctx: CommandContext) {
        ctx.sender.sendMessage("§8§l═══ §c/길드관리 명령어 §8§l═══")
        ctx.sender.sendMessage("§7/길드관리 해산 §f[길드명] §8- 길드 강제 해산")
        ctx.sender.sendMessage("§7/길드관리 정보 §f[길드명] §8- 길드 상세 정보")
        ctx.sender.sendMessage("§7/길드관리 국고 §f[길드명] [금액] §8- 국고 강제 입금")
        ctx.sender.sendMessage("§7/길드관리 레벨 §f[길드명] [1-5] §8- 레벨 강제 설정")
        ctx.sender.sendMessage("§7/길드관리 추방 §f[플레이어] §8- 길드에서 강제 추방")
        ctx.sender.sendMessage("§7/길드관리 목록 §8- 전체 길드 목록 (상세)")
    }
}
