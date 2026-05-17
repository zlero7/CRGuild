package io.zlero.cRGuild

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

object TeleportGui : Listener {

    private const val GUI_TITLE = "§6§l성 이동"

    // 이동 대기 중인 플레이어 <UUID, 출발 위치>
    private val pendingTeleports = HashMap<UUID, Location>()
    // 카운트다운 태스크 ID
    private val countdownTasks = HashMap<UUID, Int>()

    fun openGui(player: Player, guild: GuildData, isAtWar: Boolean) {
        val beacons = guild.beacons
        if (beacons.isEmpty()) { player.sendMessage("§c등록된 성이 없습니다."); return }

        val size = if (beacons.size <= 9) 9 else 18
        val inv: Inventory = Bukkit.createInventory(null, size, GUI_TITLE)

        val delay = if (isAtWar) 10 else 3

        beacons.forEachIndexed { i, beacon ->
            val item = ItemStack(Material.BEACON)
            val meta = item.itemMeta!!
            meta.setDisplayName("§e§l${i + 1}번성")
            meta.lore = listOf(
                "§7위치: §f${beacon.x}, ${beacon.y}, ${beacon.z}",
                "§7월드: §f${beacon.world}",
                "",
                if (isAtWar) "§c§l⚔ 전쟁 중 — §f${delay}초 §c후 이동"
                else         "§a좌클릭으로 이동 §8(${delay}초 후)",
                ""
            )
            item.itemMeta = meta
            inv.setItem(i, item)
        }

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title != GUI_TITLE) return
        event.isCancelled = true
        if (event.clickedInventory == null) return

        val item = event.currentItem ?: return
        if (item.type != Material.BEACON) return
        if (event.isRightClick) return

        val player = event.whoClicked as? Player ?: return
        player.closeInventory()

        val plugin = CRGuildPlugin.instance
        val guild  = plugin.guildManager.getGuildByPlayer(player)
        if (guild == null) { player.sendMessage("§c길드에 소속되어 있지 않습니다."); return }

        // 이미 대기 중인 텔레포트 취소
        cancelPending(player.uniqueId)

        val index  = event.slot
        val beacon = guild.beacons.getOrNull(index) ?: return
        val world  = Bukkit.getWorld(beacon.world)
        if (world == null) { player.sendMessage("§c해당 월드를 찾을 수 없습니다."); return }

        // 신호기 설치 좌표 기준 x-2, y+1 위치로 이동
        val dest  = Location(world, (beacon.x - 2) + 0.5, (beacon.y + 1).toDouble(), beacon.z + 0.5)
        val delay = if (guild.warActive) 10 else 3

        pendingTeleports[player.uniqueId] = player.location.clone()

        // 1초마다 타이틀 카운트다운
        val taskId = Bukkit.getScheduler().runTaskTimer(plugin, object : Runnable {
            var remaining = delay
            override fun run() {
                if (!player.isOnline) { cancelPending(player.uniqueId); return }

                // 움직임 체크
                val saved = pendingTeleports[player.uniqueId]
                if (saved != null && player.location.distance(saved) > 1.0) {
                    player.sendMessage("§c움직여서 이동이 취소되었습니다.")
                    showCancelTitle(player)
                    cancelPending(player.uniqueId)
                    return
                }

                if (remaining > 0) {
                    showCountdownTitle(player, index + 1, remaining, guild.warActive)
                    remaining--
                } else {
                    // 이동!
                    player.teleport(dest)
                    showArriveTitle(player, index + 1)
                    cancelPending(player.uniqueId)
                }
            }
        }, 0L, 20L).taskId

        countdownTasks[player.uniqueId] = taskId
    }

    // ─── 취소 ─────────────────────────────────────────────────────────────

    fun cancelPending(uuid: UUID) {
        countdownTasks.remove(uuid)?.let { Bukkit.getScheduler().cancelTask(it) }
        pendingTeleports.remove(uuid)
    }

    fun isPending(uuid: UUID) = uuid in pendingTeleports

    // ─── 타이틀 헬퍼 ─────────────────────────────────────────────────────

    private fun showCountdownTitle(player: Player, castleNum: Int, sec: Int, isWar: Boolean) {
        val color  = if (isWar) "§c" else "§a"
        val times  = Title.Times.times(
            Ticks.duration(0), Ticks.duration(25), Ticks.duration(5)
        )
        player.showTitle(
            Title.title(
                Component.text("§e§l${castleNum}번성 이동"),
                Component.text("${color}§l${sec}초 §r§7후 이동합니다..."),
                times
            )
        )
    }

    private fun showArriveTitle(player: Player, castleNum: Int) {
        val times = Title.Times.times(
            Ticks.duration(5), Ticks.duration(40), Ticks.duration(10)
        )
        player.showTitle(
            Title.title(
                Component.text("§a§l이동 완료!"),
                Component.text("§f§l${castleNum}번성 §r§7에 도착했습니다."),
                times
            )
        )
    }

    private fun showCancelTitle(player: Player) {
        val times = Title.Times.times(
            Ticks.duration(3), Ticks.duration(30), Ticks.duration(10)
        )
        player.showTitle(
            Title.title(
                Component.text("§c§l이동 취소"),
                Component.text("§7움직여서 이동이 취소되었습니다."),
                times
            )
        )
    }
}