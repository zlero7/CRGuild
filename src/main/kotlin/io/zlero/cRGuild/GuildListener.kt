package io.zlero.cRGuild

import io.zlero.cRFramework.core.component.annotation.Component
import io.zlero.cRFramework.listener.annotation.Subscribe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

@Component
class GuildListener(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager,
    private val config: GuildConfig
) {

    // 열림 상태로 고정된 길드 철문의 하단 블록 위치 목록
    private val openDoors = HashSet<Location>()

    // 길드 채팅 토글 ON 상태인 플레이어 UUID 목록
    val guildChatPlayers = HashSet<java.util.UUID>()

    // ─── 접속 시 보스바 재부여 ────────────────────────────────────────────

    @Subscribe
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val guild  = gm.getGuildByPlayer(player) ?: return
        if (guild.warTarget == null) return
        plugin.scheduler.runLater(20L) {
            gm.restoreBossBarForPlayer(player, guild)
        }
    }

    // ─── 퇴장 시 보스바 제거 & 길드 채팅 해제 ───────────────────────────

    @Subscribe
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val guild  = gm.getGuildByPlayer(player)
        if (guild != null) {
            gm.removePlayerFromWarBossBar(player, guild.name)
        }
        guildChatPlayers.remove(player.uniqueId)
    }

    // ─── 신호기 우클릭 GUI 차단 ──────────────────────────────────────────

    @Subscribe(priority = EventPriority.NORMAL)
    fun onBeaconInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BEACON) return
        event.isCancelled = true
    }

    // ─── 철문 상호작용: 길드원 토글 & 전쟁 중 폭탄 파괴 ─────────────────

    @Subscribe(priority = EventPriority.HIGH)
    fun onDoorInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.IRON_DOOR) return

        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)

        val player    = event.player
        val world     = block.world
        val bottomLoc = getBottomDoorLoc(block.location)
        val bx = bottomLoc.blockX; val bz = bottomLoc.blockZ

        val ownerGuild = gm.getGuildByTerritory(world.name, bx, bz)

        if (ownerGuild == null) return

        event.isCancelled = true

        val playerGuild = gm.getGuildByPlayer(player)
        val handItem    = player.inventory.itemInMainHand

        // ── 전쟁 중 + 상대 길드원 + 폭탄 소지 → 철문 파괴 ──
        if (ownerGuild.warActive
            && playerGuild != null
            && playerGuild.name == ownerGuild.warTarget
            && GuildItems.isBomb(handItem)
        ) {
            if (handItem.amount > 1) handItem.amount -= 1
            else player.inventory.setItemInMainHand(null)

            val bottomBlock = world.getBlockAt(bottomLoc)
            val topBlock    = world.getBlockAt(bottomLoc.clone().add(0.0, 1.0, 0.0))
            world.dropItemNaturally(bottomLoc, ItemStack(Material.IRON_DOOR, 1))
            openDoors.remove(bottomLoc)
            bottomBlock.type = Material.AIR
            topBlock.type    = Material.AIR
            return
        }

        // ── 자기 길드원 → 철문 토글 ──
        if (playerGuild != null && playerGuild.name == ownerGuild.name) {
            toggleDoor(bottomLoc, player)
            return
        }

        // ── 그 외 → 안내 메시지 ──
        if (ownerGuild.warActive && playerGuild?.name == ownerGuild.warTarget) {
            player.sendMessage("§c폭탄을 손에 들고 우클릭해야 철문을 파괴할 수 있습니다.")
        } else {
            player.sendMessage("§c${ownerGuild.name} 길드 영토이기에 해당 행동이 불가능합니다.")
        }
    }

    /** 철문 상하단 블록을 동시에 열기/닫기 토글 */
    private fun toggleDoor(bottomLoc: Location, player: Player) {
        val world       = bottomLoc.world ?: return
        val bottomBlock = world.getBlockAt(bottomLoc)
        val topBlock    = world.getBlockAt(bottomLoc.clone().add(0.0, 1.0, 0.0))

        val bottomData = bottomBlock.blockData as? Door ?: return
        val topData    = topBlock.blockData    as? Door ?: return

        val nowOpen = bottomData.isOpen
        bottomData.isOpen = !nowOpen
        topData.isOpen    = !nowOpen
        bottomBlock.blockData = bottomData
        topBlock.blockData    = topData

        player.sendBlockChange(bottomBlock.location, bottomData)
        player.sendBlockChange(topBlock.location, topData)

        if (!nowOpen) openDoors.add(bottomLoc.clone()) else openDoors.remove(bottomLoc)
    }

    /** 클릭한 블록이 철문 상단이면 하단 위치로 변환 */
    private fun getBottomDoorLoc(loc: Location): Location {
        val data = loc.block.blockData as? Door ?: return loc
        return if (data.half == Bisected.Half.TOP) loc.clone().add(0.0, -1.0, 0.0) else loc
    }

    // ─── 신호기 설치 → 길드 생성 or 추가 성 등록 ─────────────────────────

    @Subscribe(priority = EventPriority.NORMAL)
    fun onBeaconPlace(event: BlockPlaceEvent) {
        if (event.block.type != Material.BEACON) return
        val player = event.player
        val block  = event.block
        val world  = block.world
        val bx = block.x; val by = block.y; val bz = block.z

        // ── 길드 창설 대기 중 ──
        val pendingName = gm.getPendingDeclaration(player.uniqueId)
        if (pendingName != null) {
            val conflict = gm.getGuildByTerritory(world.name, bx, bz)
            if (conflict != null) {
                player.sendMessage("§c${conflict.name} 길드의 영토와 겹칩니다. 다른 위치에 설치해주세요.")
                event.isCancelled = true
                return
            }

            val result = gm.createGuild(player, pendingName, world.name, bx, by, bz)
            result.onSuccess { guild ->
                player.sendMessage("§a§l[길드 창설] §r§f${pendingName} §a길드가 창설되었습니다!")
                player.sendMessage("§71번성 위치: §f$bx, $by, $bz  §7| 영토: §f50x50")
                player.sendMessage("§7주간 세금: §f${gm.formatMoney(guild.weeklyTax())}원 §7| 최대 인원: §f${guild.maxMembers()}명")
                plugin.server.broadcastMessage(config.msg("guild.create-broadcast", "guild" to pendingName))
                plugin.scheduler.run {
                    buildCastleAt(world, bx, by, bz)
                    player.sendMessage("§7영토와 기본 성이 설치되었습니다.")
                }
            }
            result.onFailure {
                player.sendMessage("§c길드 창설 실패: ${it.message}")
                // 비콘 설치 실패 시 선포 비용 환불
                gm.economy.depositPlayer(player, config.declareCost.toDouble())
                player.sendMessage("§7선포 비용 §f${gm.formatMoney(config.declareCost)}원§7이 환불되었습니다.")
                event.isCancelled = true
            }
            return
        }

        // ── 이미 길드 소속 → 추가 성 등록 (길드장만) ──
        val myGuild = gm.getGuildByPlayer(player) ?: return
        if (!myGuild.isMaster(player.uniqueId)) return

        if (myGuild.isInTerritory(world.name, bx, bz)) {
            player.sendMessage("§c본인 길드 영토 안에는 추가 성을 설치할 수 없습니다.")
            event.isCancelled = true
            return
        }

        val conflict = gm.getGuildByTerritory(world.name, bx, bz)
        if (conflict != null) {
            player.sendMessage("§c${conflict.name} 길드의 영토와 겹칩니다.")
            event.isCancelled = true
            return
        }

        val result = gm.addBeacon(myGuild, world.name, bx, by, bz)
        result.onSuccess {
            val num = myGuild.beacons.size
            player.sendMessage("§a§l[성 추가] §r§a${num}번성이 등록되었습니다! §7위치: $bx, $by, $bz")
            gm.broadcastToGuild(myGuild, "§e${num}번성이 새롭게 등록되었습니다!")
            plugin.server.broadcastMessage(config.msg("guild.beacon-add-broadcast", "guild" to myGuild.name))
            plugin.scheduler.run { buildCastleAt(world, bx, by, bz) }
        }
        result.onFailure {
            player.sendMessage("§c성 등록 실패: ${it.message}")
            event.isCancelled = true
        }
    }

    private fun buildCastleAt(world: World, bx: Int, by: Int, bz: Int) {
        TerritoryBuilder.build(plugin, world, bx, by, bz)
        CastleBuilder.build(plugin, world, bx, by, bz)

        val warX     = bx + GuildLayout.WAR_BEACON_DX
        val emeraldY = by + GuildLayout.EMERALD_DY
        for (dx in -1..1) {
            for (dz in -1..1) {
                world.getBlockAt(warX + dx, emeraldY, bz + dz).type = Material.EMERALD_BLOCK
            }
        }

        val warBeaconY     = by + GuildLayout.WAR_BEACON_DY
        val warBeaconBlock = world.getBlockAt(warX, warBeaconY, bz)
        warBeaconBlock.type = Material.BEACON
        (warBeaconBlock.state as? org.bukkit.block.Beacon)?.let { beacon ->
            beacon.setPrimaryEffect(null)
            beacon.update(true)
        }

        val maxY = world.maxHeight
        for (clearY in (warBeaconY + 1) until maxY) {
            val above = world.getBlockAt(warX, clearY, bz)
            if (above.type != Material.AIR) above.type = Material.AIR
        }
    }

    // ─── 아군 공격 방지 ───────────────────────────────────────────────────

    @Subscribe(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPvP(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim   = event.entity   as? Player ?: return
        val aGuild   = gm.getGuildByPlayer(attacker) ?: return
        val vGuild   = gm.getGuildByPlayer(victim)   ?: return

        if (aGuild.name == vGuild.name) {
            event.isCancelled = true
            attacker.sendMessage("§c같은 길드원은 공격할 수 없습니다.")
        }
    }

    // ─── 길드 채팅 (토글 모드) ──────────────────────────────────────────

    @Subscribe(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (player.uniqueId !in guildChatPlayers) return
        event.isCancelled = true
        val guild = gm.getGuildByPlayer(player)
        if (guild == null) {
            guildChatPlayers.remove(player.uniqueId)
            player.sendMessage("§c길드에 소속되어 있지 않아 길드 채팅이 해제되었습니다.")
            return
        }
        val rank    = guild.getRank(player.uniqueId)
        val message = LegacyComponentSerializer.legacySection().serialize(event.message())
        gm.broadcastToGuild(guild, "§8[§b길드§8] ${rank.displayName} §f${player.name}§8: §7$message")
    }

    // ─── 영토 내 블록 파괴 보호 ───────────────────────────────────────────

    @Subscribe(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTerritoryBlockBreak(event: BlockBreakEvent) {
        val block  = event.block
        val player = event.player
        val world  = block.world.name
        val bx = block.x; val bz = block.z

        if (block.type == Material.BEACON || block.type == Material.EMERALD_BLOCK) return

        val ownerGuild = gm.getGuildByTerritory(world, bx, bz) ?: return

        val playerGuild = gm.getGuildByPlayer(player)
        if (playerGuild != null && playerGuild.name == ownerGuild.name) return

        event.isCancelled = true
        player.sendMessage("§c${ownerGuild.name} 길드 영토이기에 해당 행동이 불가능합니다.")
    }

    // ─── 영토 내 블록 설치 보호 ───────────────────────────────────────────

    @Subscribe(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTerritoryBlockPlace(event: BlockPlaceEvent) {
        val block  = event.block
        val player = event.player
        val world  = block.world.name
        val bx = block.x; val bz = block.z

        if (block.type == Material.BEACON) return
        if (block.type == Material.IRON_DOOR) return

        val ownerGuild = gm.getGuildByTerritory(world, bx, bz) ?: return

        val playerGuild = gm.getGuildByPlayer(player)
        if (playerGuild != null && playerGuild.name == ownerGuild.name) return

        event.isCancelled = true
        player.sendMessage("§c${ownerGuild.name} 길드 영토이기에 해당 행동이 불가능합니다.")
    }

    // ─── 전쟁 신호기 채굴 시작 → 슬로우니스 부여 ────────────────────────

    @Subscribe(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBeaconDamage(event: BlockDamageEvent) {
        val block = event.block
        if (block.type != Material.BEACON) return

        val worldName = block.world.name
        if (gm.getGuildByWarBeacon(worldName, block.x, block.y, block.z) == null) return

        event.player.addPotionEffect(
            PotionEffect(PotionEffectType.SLOW_DIGGING, 1200, 0, false, false, false)
        )
    }

    // ─── 신호기 파괴 처리 ─────────────────────────────────────────────────

    @Subscribe(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBeaconBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.BEACON) return

        val worldName  = block.world.name
        val bx = block.x; val by = block.y; val bz = block.z
        val player     = event.player

        val ownerGuild  = gm.getGuildByWarBeacon(worldName, bx, by, bz) ?: return
        val playerGuild = gm.getGuildByPlayer(player)

        // ── 전쟁 중이 아닐 때 ──
        if (!ownerGuild.warActive) {
            event.isCancelled = true
            if (playerGuild != null && playerGuild.name == ownerGuild.name) return
            player.sendMessage("§c전쟁 중에만 적 신호기를 파괴할 수 있습니다.")
            return
        }

        // ── 전쟁 중일 때 ──
        if (playerGuild == null || playerGuild.name != ownerGuild.warTarget) {
            event.isCancelled = true
            if (playerGuild != null && playerGuild.name == ownerGuild.name) return
            player.sendMessage("§c이 신호기를 파괴할 권한이 없습니다.")
            return
        }

        if (!playerGuild.isMaster(player.uniqueId) && !playerGuild.isOfficer(player.uniqueId)) {
            event.isCancelled = true
            player.sendMessage("§c신호기 파괴는 길드장과 부길드장만 가능합니다.")
            return
        }

        player.removePotionEffect(PotionEffectType.SLOW_DIGGING)
        plugin.scheduler.run {
            gm.destroyBeaconTerritory(worldName, bx, by, bz)
        }
    }

    // ─── 에메랄드 블록 파괴 차단 (전쟁 신호기 받침대 보호) ──────────────

    @Subscribe(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEmeraldBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.EMERALD_BLOCK) return

        val worldName = block.world.name
        val bx = block.x; val by = block.y; val bz = block.z

        val isProtected = gm.getAllGuilds().any { guild ->
            guild.beacons.any { b ->
                b.world == worldName &&
                by == b.y + GuildLayout.EMERALD_DY &&
                bx in (b.warX - 1)..(b.warX + 1) &&
                bz in (b.z - 1)..(b.z + 1)
            }
        }

        if (isProtected) {
            event.isCancelled = true
            event.player.sendMessage("§c이 블록은 파괴할 수 없습니다.")
        }
    }
}
