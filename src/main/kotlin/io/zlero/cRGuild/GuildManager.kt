package io.zlero.cRGuild

import io.zlero.cRFramework.core.component.annotation.Component
import io.zlero.cRFramework.core.component.annotation.Module
import io.zlero.cRFramework.core.component.annotation.Setup
import io.zlero.cRFramework.core.component.annotation.Singleton
import io.zlero.cRFramework.core.component.annotation.Teardown
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

@Component
@Singleton
@Module
class GuildManager(
    private val plugin: CRGuildPlugin,
    private val config: GuildConfig
) {

    private val guilds            = HashMap<String, GuildData>()
    private val playerGuildMap    = HashMap<UUID, String>()
    private val pendingDeclarations = HashMap<UUID, String>()
    private var taxTask: BukkitTask? = null
    private lateinit var eco: Economy

    // 전쟁 보스바: guildName → BossBar
    private val warBossBars  = HashMap<String, BossBar>()
    // 보스바 갱신 태스크: guildName → taskId
    private val warBarTasks  = HashMap<String, BukkitTask>()

    // ★ 초대 대기 목록: targetUUID → (guild, inviterUUID)
    private val pendingInvites = HashMap<UUID, Pair<GuildData, UUID>>()

    // ─── 저장소 (YAML / MySQL / SQLite 중 config.yml 설정에 따라 결정) ──
    val storage: GuildStorage = GuildStorageFactory.create(plugin)

    // ★ 탈퇴 쿨타임: playerUUID → 탈퇴 시각(ms) - 24시간 쿨타임
    private val leaveCooldowns   = HashMap<UUID, Long>()
    private val LEAVE_COOLDOWN_MS = 24L * 60 * 60 * 1000

    // ─── 생명주기 (@Setup / @Teardown) ──────────────────────────────────

    @Setup
    fun setup() {
        // Vault Economy 연동
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java)
            ?: throw IllegalStateException("Vault 또는 경제 플러그인을 찾을 수 없습니다. 플러그인을 비활성화합니다.")
        eco = rsp.provider

        loadAll()
        startTaxScheduler()
    }

    @Teardown
    fun teardown() {
        stopTaxScheduler()
        clearAllWarBossBars()
        saveAll()
    }

    // ─── 로드/저장 ────────────────────────────────────────────────────────

    fun loadAll() {
        storage.init()

        storage.loadAll().forEach { guild ->
            guilds[guild.name] = guild
            playerGuildMap[guild.master] = guild.name
            guild.officers.forEach { playerGuildMap[it] = guild.name }
            guild.members.forEach  { playerGuildMap[it] = guild.name }
        }

        leaveCooldowns.putAll(storage.loadLeaveCooldowns())

        // 서버 재시작 시 진행 중인 전쟁 보스바 복원
        guilds.values.forEach { guild ->
            if (guild.warTarget != null) restoreWarBossBar(guild)
        }

        plugin.logger.info("길드 ${guilds.size}개 로드 완료. (저장소: ${storage::class.simpleName})")
    }

    fun saveAll() {
        guilds.values.forEach { saveGuild(it) }
        saveLeaveCooldowns()
        (storage as? SqlGuildStorage)?.close()
    }

    fun saveGuild(guild: GuildData) {
        storage.save(guild)
    }

    fun saveGuildAsync(guild: GuildData) {
        plugin.scheduler.runAsync {
            runCatching { storage.save(guild) }
                .onFailure { plugin.logger.warning("[CRGuild] 비동기 저장 실패 (${guild.name}): ${it.message}") }
        }
    }

    private fun saveLeaveCooldowns() {
        val now = System.currentTimeMillis()
        val filtered = leaveCooldowns.filter { now - it.value < LEAVE_COOLDOWN_MS }
        storage.saveLeaveCooldowns(filtered)
    }

    // ─── 조회 ────────────────────────────────────────────────────────────

    fun getGuild(name: String): GuildData?             = guilds[name]
    fun getGuildByPlayer(uuid: UUID): GuildData?       = playerGuildMap[uuid]?.let { guilds[it] }
    fun getGuildByPlayer(player: Player): GuildData?   = getGuildByPlayer(player.uniqueId)
    fun getAllGuilds(): Collection<GuildData>           = guilds.values
    fun getAllGuildNames(): Set<String>                 = guilds.keys

    fun getGuildByBeacon(world: String, x: Int, y: Int, z: Int): GuildData? {
        return guilds.values.find { guild ->
            guild.beacons.any { it.world == world && it.x == x && it.y == y && it.z == z }
        }
    }

    fun getGuildByWarBeacon(world: String, wx: Int, wy: Int, wz: Int): GuildData? {
        return guilds.values.find { guild ->
            guild.beacons.any { it.isWarBeacon(world, wx, wy, wz) }
        }
    }

    fun getGuildByTerritory(world: String, x: Int, z: Int): GuildData? {
        return guilds.values.find { it.isInTerritory(world, x, z) }
    }

    // ─── 선포 대기 ───────────────────────────────────────────────────────

    fun setPendingDeclaration(player: Player, guildName: String) { pendingDeclarations[player.uniqueId] = guildName }
    fun getPendingDeclaration(uuid: UUID): String?               = pendingDeclarations[uuid]
    fun clearPendingDeclaration(uuid: UUID)                      { pendingDeclarations.remove(uuid) }

    // ─── ★ 초대 대기 관리 ───────────────────────────────────────────────

    fun setPendingInvite(target: Player, guild: GuildData, inviter: UUID) {
        pendingInvites[target.uniqueId] = Pair(guild, inviter)
        plugin.scheduler.runAfterSeconds(60) {
            if (pendingInvites.containsKey(target.uniqueId)) {
                pendingInvites.remove(target.uniqueId)
                target.sendMessage("§7${guild.name} 길드 초대가 만료되었습니다.")
            }
        }
    }

    fun getPendingInvite(uuid: UUID): Pair<GuildData, UUID>? = pendingInvites[uuid]

    fun acceptInvite(target: Player): Result<Unit> {
        val (guild, _) = pendingInvites.remove(target.uniqueId)
            ?: return Result.failure(IllegalStateException("대기 중인 길드 초대가 없습니다."))
        if (guild.totalMembers() >= guild.maxMembers())
            return Result.failure(IllegalStateException("길드 최대 인원(${guild.maxMembers()}명)에 도달했습니다."))
        if (getGuildByPlayer(target) != null)
            return Result.failure(IllegalStateException("이미 다른 길드에 소속되어 있습니다."))

        val remaining = getLeaveCooldownRemaining(target.uniqueId)
        if (remaining > 0)
            return Result.failure(IllegalStateException("길드 재가입 쿨타임이 남아있습니다. (${formatCooldown(remaining)} 후 가능)"))

        guild.members.add(target.uniqueId)
        playerGuildMap[target.uniqueId] = guild.name
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun declineInvite(target: Player): Result<Unit> {
        pendingInvites.remove(target.uniqueId)
            ?: return Result.failure(IllegalStateException("대기 중인 길드 초대가 없습니다."))
        return Result.success(Unit)
    }

    // ─── ★ 탈퇴 쿨타임 관리 ─────────────────────────────────────────────

    fun getLeaveCooldownRemaining(uuid: UUID): Long {
        val leaveTime = leaveCooldowns[uuid] ?: return 0L
        val elapsed   = System.currentTimeMillis() - leaveTime
        return (LEAVE_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    fun formatCooldown(ms: Long): String {
        val totalSecs = ms / 1000
        val hours     = totalSecs / 3600
        val mins      = (totalSecs % 3600) / 60
        val secs      = totalSecs % 60
        return "%d시간 %d분 %d초".format(hours, mins, secs)
    }

    // ─── 길드 생성 ───────────────────────────────────────────────────────

    fun createGuild(player: Player, guildName: String, world: String, bx: Int, by: Int, bz: Int): Result<GuildData> {
        if (guilds.containsKey(guildName))
            return Result.failure(IllegalArgumentException("이미 존재하는 길드명입니다."))
        if (getGuildByPlayer(player) != null)
            return Result.failure(IllegalStateException("이미 길드에 소속되어 있습니다."))
        val conflict = getGuildByTerritory(world, bx, bz)
        if (conflict != null)
            return Result.failure(IllegalStateException("${conflict.name} 길드의 영토와 겹칩니다."))

        val guild = GuildData(name = guildName, master = player.uniqueId)
        guild.beacons.add(BeaconData(world, bx, by, bz))

        guilds[guildName] = guild
        playerGuildMap[player.uniqueId] = guildName
        clearPendingDeclaration(player.uniqueId)
        saveGuild(guild)
        return Result.success(guild)
    }

    fun addBeacon(guild: GuildData, world: String, bx: Int, by: Int, bz: Int): Result<Unit> {
        val conflict = getGuildByTerritory(world, bx, bz)
        if (conflict != null && conflict.name != guild.name)
            return Result.failure(IllegalStateException("${conflict.name} 길드의 영토와 겹칩니다."))
        guild.beacons.add(BeaconData(world, bx, by, bz))
        saveGuild(guild)
        return Result.success(Unit)
    }

    // ─── 길드 해산 ───────────────────────────────────────────────────────

    fun disbandGuild(guildName: String) {
        val guild = guilds.remove(guildName) ?: return
        playerGuildMap.remove(guild.master)
        guild.officers.forEach { playerGuildMap.remove(it) }
        guild.members.forEach  { playerGuildMap.remove(it) }

        guild.beacons.forEach { b ->
            val world = Bukkit.getWorld(b.world) ?: return@forEach
            if (Bukkit.isPrimaryThread()) {
                TerritoryBuilder.destroy(world, b.x, b.y, b.z)
            } else {
                plugin.scheduler.run { TerritoryBuilder.destroy(world, b.x, b.y, b.z) }
            }
        }

        removeWarBossBar(guildName)
        storage.delete(guildName)
        broadcastToGuild(guild, "§c§l[길드 해산] §r§c${guildName} 길드가 해산되었습니다.")
    }

    // ─── 전쟁 신호기 파괴 → 영토 제거 ──────────────────────────────────

    fun destroyBeaconTerritory(world: String, wx: Int, wy: Int, wz: Int): Boolean {
        val guild      = getGuildByWarBeacon(world, wx, wy, wz) ?: return false
        val beaconData = guild.beacons.find { it.isWarBeacon(world, wx, wy, wz) } ?: return false
        val bWorld     = Bukkit.getWorld(world) ?: return false

        TerritoryBuilder.destroy(bWorld, beaconData.x, beaconData.y, beaconData.z)
        guild.beacons.remove(beaconData)

        if (guild.beacons.isEmpty()) {
            val winnerName = guild.warTarget
            val winner = if (winnerName != null) guilds[winnerName] else null

            broadcastToGuild(guild, config.msg("war.defeat.to-loser", "guild" to guild.name))
            if (winner != null) {
                broadcastToGuild(winner, config.msg("war.defeat.to-winner", "guild" to guild.name))
                Bukkit.broadcastMessage(config.msg("war.defeat.broadcast", "winner_guild" to winner.name, "guild" to guild.name))
            }
            if (winner != null) endWar(guild, winner)
            disbandGuild(guild.name)
            return true
        }

        saveGuild(guild)
        if (guild.warActive) {
            val winnerName = guild.warTarget
            val winner = if (winnerName != null) guilds[winnerName] else null
            broadcastToGuild(guild,   config.msg("war.beacon-destroy.to-loser",  "guild" to guild.name, "remaining" to guild.beacons.size))
            if (winner != null) broadcastToGuild(winner, config.msg("war.beacon-destroy.to-winner", "guild" to guild.name, "remaining" to guild.beacons.size))
        }

        return true
    }

    // ─── 멤버 관리 ───────────────────────────────────────────────────────

    fun inviteMember(guild: GuildData, inviter: Player, target: Player): Result<Unit> {
        if (guild.totalMembers() >= guild.maxMembers())
            return Result.failure(IllegalStateException("길드 최대 인원(${guild.maxMembers()}명)에 도달했습니다."))
        if (getGuildByPlayer(target) != null)
            return Result.failure(IllegalStateException("${target.name}은(는) 이미 다른 길드에 소속되어 있습니다."))
        if (pendingInvites.containsKey(target.uniqueId))
            return Result.failure(IllegalStateException("${target.name}님은 이미 길드 초대를 받은 상태입니다."))

        val remaining = getLeaveCooldownRemaining(target.uniqueId)
        if (remaining > 0) {
            val msg = config.msg("guild.invite-cooldown", "player" to target.name, "time" to formatCooldown(remaining))
            return Result.failure(IllegalStateException(msg))
        }

        setPendingInvite(target, guild, inviter.uniqueId)
        return Result.success(Unit)
    }

    fun kickMember(guild: GuildData, targetUuid: UUID): Result<Unit> {
        if (targetUuid == guild.master)
            return Result.failure(IllegalArgumentException("길드장은 추방할 수 없습니다."))
        guild.officers.remove(targetUuid)
        guild.members.remove(targetUuid)
        playerGuildMap.remove(targetUuid)
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun leaveGuild(player: Player): Result<Unit> {
        val guild = getGuildByPlayer(player)
            ?: return Result.failure(IllegalStateException("소속된 길드가 없습니다."))
        if (guild.isMaster(player.uniqueId))
            return Result.failure(IllegalStateException("길드장은 탈퇴할 수 없습니다. 해산 또는 위임하세요."))

        val remaining = getLeaveCooldownRemaining(player.uniqueId)
        if (remaining > 0)
            return Result.failure(IllegalStateException("길드 재가입 쿨타임이 남아있습니다. (${formatCooldown(remaining)} 후 가능)"))

        guild.officers.remove(player.uniqueId)
        guild.members.remove(player.uniqueId)
        playerGuildMap.remove(player.uniqueId)

        leaveCooldowns[player.uniqueId] = System.currentTimeMillis()
        val filtered = leaveCooldowns.filter { System.currentTimeMillis() - it.value < LEAVE_COOLDOWN_MS }
        plugin.scheduler.runAsync {
            runCatching { storage.saveLeaveCooldowns(filtered) }
                .onFailure { plugin.logger.warning("[CRGuild] 탈퇴 쿨타임 저장 실패: ${it.message}") }
        }

        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun promoteToOfficer(guild: GuildData, targetUuid: UUID): Result<Unit> {
        if (!guild.members.contains(targetUuid))
            return Result.failure(IllegalArgumentException("해당 플레이어는 일반 멤버가 아닙니다."))
        guild.members.remove(targetUuid)
        guild.officers.add(targetUuid)
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun demoteOfficer(guild: GuildData, targetUuid: UUID): Result<Unit> {
        if (!guild.officers.contains(targetUuid))
            return Result.failure(IllegalArgumentException("해당 플레이어는 부길드장이 아닙니다."))
        guild.officers.remove(targetUuid)
        guild.members.add(targetUuid)
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun transferMaster(guild: GuildData, newMasterUuid: UUID): Result<Unit> {
        if (!guild.isMember(newMasterUuid))
            return Result.failure(IllegalArgumentException("길드 멤버가 아닙니다."))
        guild.officers.remove(newMasterUuid)
        guild.members.remove(newMasterUuid)
        guild.members.add(guild.master)
        guild.master = newMasterUuid
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    // ─── 국고 ────────────────────────────────────────────────────────────

    fun depositTreasury(player: Player, guild: GuildData, amount: Long): Result<Unit> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("금액은 1원 이상이어야 합니다."))
        if (guild.warTarget != null)
            return Result.failure(IllegalStateException("전쟁 중(준비 포함)에는 국고 입금이 불가능합니다."))
        if (!eco.has(player, amount.toDouble()))
            return Result.failure(IllegalStateException("잔액이 부족합니다."))
        eco.withdrawPlayer(player, amount.toDouble())
        guild.treasury += amount
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    fun withdrawTreasury(player: Player, guild: GuildData, amount: Long): Result<Unit> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("금액은 1원 이상이어야 합니다."))
        if (guild.warTarget != null)
            return Result.failure(IllegalStateException("전쟁 중(준비 포함)에는 국고 출금이 불가능합니다."))
        if (!guild.isOfficer(player.uniqueId))
            return Result.failure(IllegalStateException("부길드장 이상만 국고에서 출금할 수 있습니다."))
        if (guild.treasury < amount)
            return Result.failure(IllegalStateException("국고 잔액이 부족합니다. 현재: ${formatMoney(guild.treasury)}원"))
        guild.treasury -= amount
        eco.depositPlayer(player, amount.toDouble())
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    // ─── 레벨업 ──────────────────────────────────────────────────────────

    fun levelUpGuild(guild: GuildData): Result<Unit> {
        if (guild.level >= 5)
            return Result.failure(IllegalStateException("이미 최대 레벨(5레벨)입니다."))
        val cost = guild.levelUpCost()
        if (guild.treasury < cost)
            return Result.failure(IllegalStateException("국고가 부족합니다. 필요: ${formatMoney(cost)}원, 현재: ${formatMoney(guild.treasury)}원"))
        guild.treasury -= cost
        guild.level++
        saveGuildAsync(guild)
        return Result.success(Unit)
    }

    // ─── 세금 스케줄러 ────────────────────────────────────────────────────

    private fun startTaxScheduler() {
        // 1시간 후 첫 실행, 이후 매시간 체크. lastTaxAt 기준 1주일이 지난 길드에만 징수
        taxTask = plugin.scheduler.runTimer(20L * 60 * 60, 20L * 60 * 60) { collectWeeklyTax() }
    }

    private fun stopTaxScheduler() { taxTask?.cancel(); taxTask = null }

    private fun collectWeeklyTax() {
        val now       = System.currentTimeMillis()
        val weekMs    = 7L * 24 * 60 * 60 * 1000
        val toDisband = mutableListOf<String>()

        guilds.values.forEach { guild ->
            if (guild.lastTaxAt != 0L && now - guild.lastTaxAt < weekMs) return@forEach

            val tax = guild.weeklyTax()
            guild.lastTaxAt = now

            if (guild.treasury >= tax) {
                guild.treasury -= tax
                guild.taxMissedWeeks = 0
                broadcastToGuild(guild, "§e§l[길드 세금] §r§e이번 주 세금 §f${formatMoney(tax)}원§e이 납부되었습니다. 남은 국고: §f${formatMoney(guild.treasury)}원")
            } else {
                guild.taxMissedWeeks++
                broadcastToGuild(guild, "§c§l[세금 미납 경고] §r§c${guild.taxMissedWeeks}/3회 미납! 3회 시 길드 해산됩니다.")
                if (guild.taxMissedWeeks >= 3) {
                    broadcastToGuild(guild, "§4§l[길드 해산] §r§c세금 3회 미납으로 ${guild.name} 길드가 해산됩니다!")
                    toDisband.add(guild.name)
                }
            }
            if (guild.name !in toDisband) saveGuild(guild)
        }
        toDisband.forEach { disbandGuild(it) }
    }

    // ─── 전쟁 ────────────────────────────────────────────────────────────

    fun declareWar(attacker: GuildData, defender: GuildData): Result<Unit> {
        if (attacker.warActive || attacker.warTarget != null)
            return Result.failure(IllegalStateException("이미 전쟁 중입니다."))
        if (defender.warActive || defender.warTarget != null)
            return Result.failure(IllegalStateException("상대 길드가 이미 전쟁 중입니다."))

        val minOnline      = config.warMinOnline
        val attackerOnline = getOnlineCount(attacker)
        val defenderOnline = getOnlineCount(defender)
        if (attackerOnline < minOnline)
            return Result.failure(IllegalStateException("아군 온라인 인원이 부족합니다. (현재 ${attackerOnline}명 / 최소 ${minOnline}명)"))
        if (defenderOnline < minOnline)
            return Result.failure(IllegalStateException("상대 길드 온라인 인원이 부족합니다. (현재 ${defenderOnline}명 / 최소 ${minOnline}명)"))

        attacker.warTarget     = defender.name
        attacker.warDeclaredAt = System.currentTimeMillis()
        attacker.isWarAttacker = true
        defender.warTarget     = attacker.name
        defender.warDeclaredAt = System.currentTimeMillis()
        defender.isWarAttacker = false
        saveGuild(attacker); saveGuild(defender)

        startWarBossBar(attacker, defender, false)

        plugin.scheduler.runAfterSeconds(5 * 60) {
            if (attacker.warTarget == defender.name && !attacker.warActive) startWar(attacker, defender)
        }

        soundToGuild(attacker, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.8f)
        soundToGuild(defender, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.8f)
        return Result.success(Unit)
    }

    fun startWar(a: GuildData, b: GuildData) {
        a.warActive = true; b.warActive = true
        saveGuild(a); saveGuild(b)

        startWarBossBar(a, b, true)

        soundToGuild(a, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f)
        soundToGuild(b, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f)

        plugin.scheduler.runAfterSeconds(10 * 60) {
            if (a.warActive && a.warTarget == b.name) {
                Bukkit.broadcastMessage(config.msg("war.draw.broadcast", "guild_a" to a.name, "guild_b" to b.name))
                endWar(a, b)
            }
        }
    }

    fun getSurrenderCost(guild: GuildData): Long {
        return config.surrenderBaseCost + config.surrenderCostIncrease * guild.surrenderCount
    }

    fun surrender(loser: GuildData): Result<Unit> {
        val winnerName = loser.warTarget ?: return Result.failure(IllegalStateException("전쟁 중이 아닙니다."))
        val winner     = guilds[winnerName] ?: return Result.failure(IllegalStateException("상대 길드를 찾을 수 없습니다."))

        val cost = getSurrenderCost(loser)
        if (loser.treasury < cost)
            return Result.failure(IllegalStateException("국고 부족. 항복 비용: ${formatMoney(cost)}원 (${loser.surrenderCount + 1}회차), 현재: ${formatMoney(loser.treasury)}원"))

        loser.treasury -= cost
        loser.surrenderCount++
        endWar(loser, winner)
        broadcastToGuild(loser,  config.msg("war.surrender.loser",  "cost" to formatMoney(cost)))
        broadcastToGuild(winner, config.msg("war.surrender.winner", "loser_guild" to loser.name))
        return Result.success(Unit)
    }

    fun endWar(a: GuildData, b: GuildData) {
        removeWarBossBar(a.name)
        removeWarBossBar(b.name)
        a.warTarget = null; a.warActive = false; a.warDeclaredAt = 0L; a.isWarAttacker = false
        b.warTarget = null; b.warActive = false; b.warDeclaredAt = 0L; b.isWarAttacker = false
        saveGuild(a); saveGuild(b)
    }

    fun forceStartWar(attackerName: String, defenderName: String): Result<Unit> {
        val a = guilds[attackerName] ?: return Result.failure(IllegalStateException("길드를 찾을 수 없습니다: $attackerName"))
        val b = guilds[defenderName] ?: return Result.failure(IllegalStateException("길드를 찾을 수 없습니다: $defenderName"))
        if (a.warTarget != defenderName || b.warTarget != attackerName)
            return Result.failure(IllegalStateException("두 길드가 서로 전쟁 선포 상태가 아닙니다."))
        if (a.warActive)
            return Result.failure(IllegalStateException("이미 전쟁이 진행 중입니다."))
        startWar(a, b)
        return Result.success(Unit)
    }

    fun forceDraw(attackerName: String, defenderName: String): Result<Unit> {
        val a = guilds[attackerName] ?: return Result.failure(IllegalStateException("길드를 찾을 수 없습니다: $attackerName"))
        val b = guilds[defenderName] ?: return Result.failure(IllegalStateException("길드를 찾을 수 없습니다: $defenderName"))
        if (a.warTarget != defenderName)
            return Result.failure(IllegalStateException("두 길드가 전쟁 중이 아닙니다."))
        Bukkit.broadcastMessage("§8[§4⚔ 관리자 강제 종료§8] " + config.msg("war.draw.broadcast", "guild_a" to a.name, "guild_b" to b.name))
        broadcastToGuild(a, "§7관리자에 의해 전쟁이 무승부로 종료되었습니다.")
        broadcastToGuild(b, "§7관리자에 의해 전쟁이 무승부로 종료되었습니다.")
        endWar(a, b)
        return Result.success(Unit)
    }

    fun isAtWar(a: GuildData, b: GuildData): Boolean = a.warActive && a.warTarget == b.name

    fun isDefender(guild: GuildData): Boolean = guild.warTarget != null && !guild.isWarAttacker

    // ─── 온라인 인원 수 ──────────────────────────────────────────────────

    fun getOnlineCount(guild: GuildData): Int {
        return (setOf(guild.master) + guild.officers + guild.members)
            .count { Bukkit.getPlayer(it) != null }
    }

    // ─── 보스바 관리 ─────────────────────────────────────────────────────

    fun startWarBossBar(a: GuildData, b: GuildData, isActive: Boolean) {
        removeWarBossBar(a.name)
        removeWarBossBar(b.name)

        val totalSecs = if (isActive) 60 * 10 else 60 * 5
        val startTime = System.currentTimeMillis()
        val color     = if (isActive) BarColor.RED else BarColor.YELLOW

        val barA = Bukkit.createBossBar("", color, BarStyle.SEGMENTED_10)
        val barB = Bukkit.createBossBar("", color, BarStyle.SEGMENTED_10)
        warBossBars[a.name] = barA
        warBossBars[b.name] = barB

        addBossBarPlayers(a, barA)
        addBossBarPlayers(b, barB)

        val task = plugin.scheduler.runTimer(0L, 20L) {
            val elapsed   = (System.currentTimeMillis() - startTime) / 1000.0
            val remaining = (totalSecs - elapsed).coerceAtLeast(0.0)
            val progress  = (remaining / totalSecs).coerceIn(0.0, 1.0)

            val mins    = (remaining / 60).toInt()
            val secs    = (remaining % 60).toInt()
            val timeStr = "%d:%02d".format(mins, secs)

            val prefix = if (isActive) "§c⚔ 전쟁 진행 중" else "§e⚔ 전쟁 준비 중"
            val title  = "$prefix §7| §f${a.name} §7vs §f${b.name} §7| §f${timeStr} §7남음"

            barA.setTitle(title); barA.progress = progress
            barB.setTitle(title); barB.progress = progress

            addBossBarPlayers(a, barA)
            addBossBarPlayers(b, barB)
        }

        warBarTasks[a.name] = task
        warBarTasks[b.name] = task
    }

    private fun addBossBarPlayers(guild: GuildData, bar: BossBar) {
        (setOf(guild.master) + guild.officers + guild.members).forEach { uuid ->
            val p = Bukkit.getPlayer(uuid) ?: return@forEach
            if (!bar.players.contains(p)) bar.addPlayer(p)
        }
    }

    fun removeWarBossBar(guildName: String) {
        warBossBars.remove(guildName)?.removeAll()
        warBarTasks.remove(guildName)?.cancel()
    }

    fun removePlayerFromWarBossBar(player: Player, guildName: String) {
        warBossBars[guildName]?.removePlayer(player)
    }

    private fun restoreWarBossBar(guild: GuildData) {
        val targetName = guild.warTarget ?: return
        val target     = guilds[targetName] ?: return
        if (guild.name > targetName) return

        val isActive  = guild.warActive
        val totalSecs = if (isActive) 60 * 10 else 60 * 5
        val elapsed   = ((System.currentTimeMillis() - guild.warDeclaredAt) / 1000).toInt()
        val remaining = (totalSecs - elapsed).coerceAtLeast(0)
        if (remaining <= 0) return

        startWarBossBar(guild, target, isActive)
    }

    fun clearAllWarBossBars() {
        warBossBars.values.forEach { it.removeAll() }
        warBossBars.clear()
        warBarTasks.values.forEach { it.cancel() }
        warBarTasks.clear()
    }

    fun restoreBossBarForPlayer(player: Player, guild: GuildData) {
        val bar = warBossBars[guild.name] ?: return
        if (!bar.players.contains(player)) bar.addPlayer(player)
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────

    fun broadcastToGuild(guild: GuildData, message: String) {
        (setOf(guild.master) + guild.officers + guild.members)
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { it.sendMessage(message) }
    }

    fun soundToGuild(guild: GuildData, sound: org.bukkit.Sound, volume: Float = 1.0f, pitch: Float = 1.0f) {
        (setOf(guild.master) + guild.officers + guild.members)
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { it.playSound(it.location, sound, volume, pitch) }
    }

    fun formatMoney(amount: Long): String = String.format("%,d", amount)
}
