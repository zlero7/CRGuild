package io.zlero.cRGuild

import io.zlero.cRFramework.command.CommandContext
import io.zlero.cRFramework.command.annotation.Command
import io.zlero.cRFramework.core.component.annotation.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player

@Component
class GuildCommand(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager,
    private val config: GuildConfig,
    private val listener: GuildListener
) {

    @Command("길드", description = "길드 명령어")
    fun onGuild(ctx: CommandContext) {
        val player = ctx.player   // 플레이어가 아니면 CommandException 자동 발생
        if (ctx.size == 0) { showMyGuildInfo(player); return }
        val args = ctx.args
        when (args[0]) {
            "선포"   -> cmdDeclare(player, args)
            "정보"   -> cmdInfo(player, args)
            "초대"   -> cmdInvite(player, args)
            "수락"   -> cmdAcceptInvite(player)
            "거절"   -> cmdDeclineInvite(player)
            "추방"   -> cmdKick(player, args)
            "탈퇴"   -> cmdLeave(player)
            "해산"   -> cmdDisband(player)
            "국고"   -> cmdTreasury(player, args)
            "레벨업" -> cmdLevelUp(player)
            "공지"   -> cmdAnnounce(player, args)
            "임명"   -> cmdPromote(player, args)
            "해임"   -> cmdDemote(player, args)
            "위임"   -> cmdTransfer(player, args)
            "목록"   -> cmdList(player)
            "이동"   -> cmdTeleport(player)
            "채팅"   -> cmdChatToggle(player)
            "폭탄"   -> cmdBomb(player, args)
            "리로드" -> cmdReload(player)
            "도움말" -> sendHelp(player)
            else     -> sendHelp(player)
        }
    }

    // ─── /길드 (내 길드 정보) ─────────────────────────────────────────────

    private fun showMyGuildInfo(player: Player) {
        val guild = gm.getGuildByPlayer(player)
        if (guild == null) {
            player.sendMessage(config.msg("guild.no-guild"))
            return
        }
        printGuildInfo(player, guild, isMember = true)
    }

    private fun printGuildInfo(player: Player, guild: GuildData, isMember: Boolean) {
        val masterName = Bukkit.getOfflinePlayer(guild.master).name ?: "알 수 없음"
        val warStatus  = when {
            guild.warActive         -> "§c⚔ 전쟁 중 (vs ${guild.warTarget})"
            guild.warTarget != null -> "§e⚠ 전쟁 선포됨 (vs ${guild.warTarget})"
            else                    -> "§a평화"
        }

        player.sendMessage("§8§l═══════════════════════════")
        player.sendMessage("  §6§l${guild.name} §e§lLv.${guild.level}")
        player.sendMessage("§8§l═══════════════════════════")
        if (guild.announcement.isNotBlank()) player.sendMessage("  §7공지: §f${guild.announcement}")
        player.sendMessage("  §7길드장: §f$masterName")
        player.sendMessage("  §7인원: §f${guild.totalMembers()}§7/§f${guild.maxMembers()}명")
        player.sendMessage("  §7성 수: §f${guild.beacons.size}개")
        player.sendMessage("  §7전쟁 상태: $warStatus")

        if (isMember) {
            player.sendMessage("  §7국고: §f${gm.formatMoney(guild.treasury)}원")
            player.sendMessage("  §7주간 세금: §f${gm.formatMoney(guild.weeklyTax())}원 §8(미납 ${guild.taxMissedWeeks}/3회)")
            if (guild.level < 5) player.sendMessage("  §7레벨업 비용: §f${gm.formatMoney(guild.levelUpCost())}원")
        }

        player.sendMessage("  §7§l─ 길드원 목록 ─")
        guild.allMembers().forEach { uuid ->
            val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
            val name = offlinePlayer.name ?: uuid.toString().take(8)
            val rank = when {
                uuid == guild.master   -> "§6[장]"
                uuid in guild.officers -> "§e[부]"
                else                   -> "§7[멤]"
            }
            if (offlinePlayer.isOnline) {
                player.sendMessage("    $rank §a$name §a●")
            } else {
                player.sendMessage("    $rank §7$name §8●")
            }
        }

        player.sendMessage("§8§l═══════════════════════════")
    }

    // ─── /길드 선포 [이름] ────────────────────────────────────────────────

    private fun cmdDeclare(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 선포 [이름]"); return }
        val name = args[1]
        if (name.length > 10) { player.sendMessage("§c길드명은 10자 이하여야 합니다."); return }
        if (!name.matches(Regex("[가-힣a-zA-Z0-9]+"))) { player.sendMessage("§c길드명은 한글/영문/숫자만 가능합니다."); return }
        if (gm.getGuild(name) != null) { player.sendMessage("§c이미 존재하는 길드명입니다."); return }
        if (gm.getGuildByPlayer(player) != null) { player.sendMessage("§c이미 길드에 소속되어 있습니다."); return }

        val declareCost = config.declareCost
        if (!gm.economy.has(player, declareCost.toDouble())) {
            player.sendMessage("§c길드 선포에 §f${gm.formatMoney(declareCost)}원§c이 필요합니다. (현재 잔액 부족)")
            return
        }
        gm.economy.withdrawPlayer(player, declareCost.toDouble())

        gm.setPendingDeclaration(player, name)
        player.sendMessage("§a§l[길드 선포] §r§f${name} §a길드 창설을 선포했습니다!")
        player.sendMessage("§7선포 비용 §f${gm.formatMoney(declareCost)}원§7이 차감되었습니다.")
        player.sendMessage("§7이제 §f신호기§7를 설치하면 1번성이 등록되며 길드가 창설됩니다.")
    }

    // ─── /길드 정보 [길드명] ──────────────────────────────────────────────

    private fun cmdInfo(player: Player, args: Array<out String>) {
        val guild = if (args.size < 2) {
            gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c소속된 길드가 없습니다. §7/길드 정보 [길드명] 으로 다른 길드를 조회하세요."); return }
        } else {
            gm.getGuild(args[1]) ?: run { player.sendMessage("§c존재하지 않는 길드입니다."); return }
        }
        val isMember = gm.getGuildByPlayer(player)?.name == guild.name
        printGuildInfo(player, guild, isMember)
    }

    // ─── /길드 초대 ───────────────────────────────────────────────────────

    private fun cmdInvite(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 초대 [플레이어]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isOfficer(player.uniqueId)) { player.sendMessage("§c부길드장 이상만 초대할 수 있습니다."); return }
        val target = Bukkit.getPlayer(args[1]) ?: run { player.sendMessage("§c해당 플레이어가 온라인 상태가 아닙니다."); return }

        gm.inviteMember(guild, player, target)
            .onSuccess {
                player.sendMessage("§a${target.name}님에게 길드 초대를 보냈습니다. (60초 내 수락 필요)")
                target.sendMessage("§6§l[길드 초대] §r§f${guild.name} §a길드에서 초대가 왔습니다!")
                target.sendMessage("§7수락: §f/길드 수락  §7거절: §f/길드 거절")
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 수락 ───────────────────────────────────────────────────────

    private fun cmdAcceptInvite(player: Player) {
        gm.acceptInvite(player)
            .onSuccess {
                val guild = gm.getGuildByPlayer(player)
                if (guild != null) {
                    player.sendMessage("§a§l[길드 가입] §r§f${guild.name} §a길드에 가입했습니다!")
                    gm.broadcastToGuild(guild, "§7${player.name}님이 길드에 가입했습니다.")
                }
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 거절 ───────────────────────────────────────────────────────

    private fun cmdDeclineInvite(player: Player) {
        val invite = gm.getPendingInvite(player.uniqueId)
        gm.declineInvite(player)
            .onSuccess {
                player.sendMessage("§7길드 초대를 거절했습니다.")
                if (invite != null) {
                    val (guild, inviterUuid) = invite
                    Bukkit.getPlayer(inviterUuid)?.sendMessage("§c${player.name}님이 ${guild.name} 길드 초대를 거절했습니다.")
                }
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 추방 ───────────────────────────────────────────────────────

    private fun cmdKick(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 추방 [플레이어]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isOfficer(player.uniqueId)) { player.sendMessage("§c부길드장 이상만 추방할 수 있습니다."); return }
        val targetUuid = findMemberUuid(guild, args[1]) ?: run { player.sendMessage("§c${args[1]}님은 길드 멤버가 아닙니다."); return }

        gm.kickMember(guild, targetUuid)
            .onSuccess {
                player.sendMessage("§a${args[1]}님을 추방했습니다.")
                gm.broadcastToGuild(guild, "§c${args[1]}님이 길드에서 추방되었습니다.")
                Bukkit.getPlayer(targetUuid)?.sendMessage("§c${guild.name} 길드에서 추방되었습니다.")
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 탈퇴 ───────────────────────────────────────────────────────

    private fun cmdLeave(player: Player) {
        gm.leaveGuild(player)
            .onSuccess { player.sendMessage("§a길드를 탈퇴했습니다. §7(24시간 재가입 쿨타임 시작)") }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 해산 ───────────────────────────────────────────────────────

    private fun cmdDisband(player: Player) {
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isMaster(player.uniqueId)) { player.sendMessage("§c길드장만 해산할 수 있습니다."); return }
        if (guild.warActive) { player.sendMessage("§c전쟁 중에는 해산할 수 없습니다."); return }
        gm.disbandGuild(guild.name)
    }

    // ─── /길드 국고 ──────────────────────────────────────────────────────

    private fun cmdTreasury(player: Player, args: Array<out String>) {
        if (args.size < 3 || args[1] !in listOf("입금", "출금")) {
            player.sendMessage("§c사용법: /길드 국고 입금 [금액]")
            player.sendMessage("§c사용법: /길드 국고 출금 [금액] §8(부길드장 이상)")
            return
        }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        val amount = args[2].replace(",", "").toLongOrNull()
        if (amount == null || amount <= 0) { player.sendMessage("§c올바른 금액을 입력해주세요."); return }

        when (args[1]) {
            "입금" -> gm.depositTreasury(player, guild, amount)
                .onSuccess {
                    player.sendMessage("§a국고에 §f${gm.formatMoney(amount)}원§a 입금. 현재 국고: §f${gm.formatMoney(guild.treasury)}원")
                    gm.broadcastToGuild(guild, "§7${player.name}님이 국고에 §f${gm.formatMoney(amount)}원§7을 입금했습니다.")
                }
                .onFailure { player.sendMessage("§c${it.message}") }
            "출금" -> gm.withdrawTreasury(player, guild, amount)
                .onSuccess {
                    player.sendMessage("§a국고에서 §f${gm.formatMoney(amount)}원§a 출금. 현재 국고: §f${gm.formatMoney(guild.treasury)}원")
                    gm.broadcastToGuild(guild, "§e${player.name}님이 국고에서 §f${gm.formatMoney(amount)}원§e을 출금했습니다.")
                }
                .onFailure { player.sendMessage("§c${it.message}") }
        }
    }

    // ─── /길드 레벨업 ─────────────────────────────────────────────────────

    private fun cmdLevelUp(player: Player) {
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isMaster(player.uniqueId)) { player.sendMessage("§c길드장만 레벨업할 수 있습니다."); return }
        val cost = guild.levelUpCost()
        gm.levelUpGuild(guild)
            .onSuccess {
                gm.broadcastToGuild(guild, "§6§l[길드 레벨업] §r§e${guild.name} 길드가 §fLv.${guild.level}§e으로 성장! 최대 인원: §f${guild.maxMembers()}명")
                gm.broadcastToGuild(guild, "§7국고에서 §f${gm.formatMoney(cost)}원§7 차감. 남은 국고: §f${gm.formatMoney(guild.treasury)}원")
            }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    // ─── /길드 공지 ───────────────────────────────────────────────────────

    private fun cmdAnnounce(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 공지 [내용]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isOfficer(player.uniqueId)) { player.sendMessage("§c부길드장 이상만 공지를 설정할 수 있습니다."); return }
        guild.announcement = args.drop(1).joinToString(" ")
        gm.saveGuildAsync(guild)
        gm.broadcastToGuild(guild, "§e§l[길드 공지] §r§e${guild.announcement}")
    }

    // ─── /길드 임명/해임/위임 ────────────────────────────────────────────

    private fun cmdPromote(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 임명 [플레이어]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isMaster(player.uniqueId)) { player.sendMessage("§c길드장만 부길드장을 임명할 수 있습니다."); return }
        val targetUuid = findMemberUuid(guild, args[1]) ?: run { player.sendMessage("§c${args[1]}님은 길드 멤버가 아닙니다."); return }
        gm.promoteToOfficer(guild, targetUuid)
            .onSuccess { gm.broadcastToGuild(guild, "§e${args[1]}님이 부길드장으로 임명되었습니다.") }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    private fun cmdDemote(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 해임 [플레이어]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isMaster(player.uniqueId)) { player.sendMessage("§c길드장만 해임할 수 있습니다."); return }
        val targetUuid = findMemberUuid(guild, args[1]) ?: run { player.sendMessage("§c${args[1]}님은 길드 멤버가 아닙니다."); return }
        gm.demoteOfficer(guild, targetUuid)
            .onSuccess { gm.broadcastToGuild(guild, "§7${args[1]}님이 일반 멤버로 변경되었습니다.") }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    private fun cmdTransfer(player: Player, args: Array<out String>) {
        if (args.size < 2) { player.sendMessage("§c사용법: /길드 위임 [플레이어]"); return }
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (!guild.isMaster(player.uniqueId)) { player.sendMessage("§c길드장만 위임할 수 있습니다."); return }
        val targetUuid = findMemberUuid(guild, args[1]) ?: run { player.sendMessage("§c${args[1]}님은 길드 멤버가 아닙니다."); return }
        gm.transferMaster(guild, targetUuid)
            .onSuccess { gm.broadcastToGuild(guild, "§6§l[길드장 위임] §r§6${args[1]}님이 새로운 길드장이 되었습니다.") }
            .onFailure { player.sendMessage("§c${it.message}") }
    }

    private fun findMemberUuid(guild: GuildData, name: String): java.util.UUID? {
        return guild.allMembers()
            .find { Bukkit.getOfflinePlayer(it).name?.equals(name, ignoreCase = true) == true }
    }

    // ─── /길드 목록 ───────────────────────────────────────────────────────

    private fun cmdList(player: Player) {
        val guilds = gm.getAllGuilds()
        if (guilds.isEmpty()) { player.sendMessage("§7현재 존재하는 길드가 없습니다."); return }
        player.sendMessage("§8§l═══ §6길드 목록 §8§l═══")
        guilds.forEach { g ->
            val war = if (g.warActive) " §c⚔" else ""
            player.sendMessage("  §f${g.name} §8Lv.${g.level} §7(${g.totalMembers()}/${g.maxMembers()})$war")
        }
    }

    // ─── /길드 이동 ───────────────────────────────────────────────────────

    private fun cmdTeleport(player: Player) {
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        TeleportGui.open(player, guild, guild.warActive, plugin, gm)
    }

    // ─── /길드 채팅 (토글) ───────────────────────────────────────────────

    private fun cmdChatToggle(player: Player) {
        val guild = gm.getGuildByPlayer(player) ?: run { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }
        if (player.uniqueId in listener.guildChatPlayers) {
            listener.guildChatPlayers.remove(player.uniqueId)
            player.sendMessage("§7[길드 채팅] §c꺼짐 §7— 일반 채팅으로 돌아왔습니다.")
        } else {
            listener.guildChatPlayers.add(player.uniqueId)
            player.sendMessage("§7[길드 채팅] §a켜짐 §7— 이제 모든 채팅이 §b${guild.name} §7길드원에게만 전송됩니다.")
            player.sendMessage("§7해제하려면 §f/길드 채팅 §7을 다시 입력하세요.")
        }
    }

    // ─── /길드 폭탄 ───────────────────────────────────────────────────────

    private fun cmdBomb(player: Player, args: Array<out String>) {
        if (!player.hasPermission("crguild.admin")) { player.sendMessage("§c이 명령어는 관리자만 사용할 수 있습니다."); return }
        val amount = if (args.size >= 2) args[1].toIntOrNull() ?: 1 else 1
        if (amount !in 1..64) { player.sendMessage("§c수량은 1~64 사이여야 합니다."); return }
        player.inventory.addItem(GuildItems.createBomb(amount))
        player.sendMessage("§a§l[길드 폭탄] §r§a폭탄 §f${amount}개§a를 지급했습니다.")
        player.sendMessage("§7전쟁 중 적 길드 철문에 우클릭하면 파괴할 수 있습니다.")
    }

    // ─── /길드 리로드 ─────────────────────────────────────────────────────

    private fun cmdReload(player: Player) {
        if (!player.hasPermission("crguild.admin")) { player.sendMessage("§c이 명령어는 관리자만 사용할 수 있습니다."); return }
        config.reload()
        player.sendMessage("§a§l[리로드] §r§aconfig.yml을 다시 불러왔습니다.")
    }

    // ─── 도움말 ───────────────────────────────────────────────────────────

    private fun sendHelp(player: Player) {
        player.sendMessage("§8§l═══ §6/길드 명령어 §8§l═══")
        player.sendMessage("§7/길드 §8- 내 길드 정보")
        player.sendMessage("§7/길드 선포 §f[이름] §8- 길드 창설 선포 (§f100만원§8 차감)")
        player.sendMessage("§7/길드 정보 §f[길드명] §8- 길드 정보 조회")
        player.sendMessage("§7/길드 초대 §f[플레이어] §8- 멤버 초대 (부길드장↑, 수락 필요)")
        player.sendMessage("§7/길드 수락 §8- 길드 초대 수락")
        player.sendMessage("§7/길드 거절 §8- 길드 초대 거절")
        player.sendMessage("§7/길드 추방 §f[플레이어] §8- 멤버 추방 (부길드장↑)")
        player.sendMessage("§7/길드 탈퇴 §8- 길드 탈퇴 (24시간 재가입 쿨타임)")
        player.sendMessage("§7/길드 해산 §8- 길드 해산 (길드장)")
        player.sendMessage("§7/길드 국고 입금 §f[금액] §8- 국고 입금")
        player.sendMessage("§7/길드 국고 출금 §f[금액] §8- 국고 출금 (부길드장↑)")
        player.sendMessage("§7/길드 레벨업 §8- 길드 레벨업 (국고 차감, 길드장)")
        player.sendMessage("§7/길드 공지 §f[내용] §8- 공지 설정 (부길드장↑)")
        player.sendMessage("§7/길드 임명/해임 §f[플레이어] §8- 부길드장 관리 (길드장)")
        player.sendMessage("§7/길드 위임 §f[플레이어] §8- 길드장 위임 (길드장)")
        player.sendMessage("§7/길드 이동 §8- 성 이동 GUI")
        player.sendMessage("§7/길드 목록 §8- 전체 길드 목록")
        player.sendMessage("§7/길드 채팅 §8- 길드 채팅 ON/OFF 토글")
        player.sendMessage("§7/길드 폭탄 §f[수량] §8- 폭탄 지급 (관리자 전용)")
        player.sendMessage("§7/길드 리로드 §8- config.yml 리로드 (관리자 전용)")
    }
}
