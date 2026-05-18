package io.zlero.cRGuild

import io.zlero.cRFramework.view.View
import io.zlero.cRFramework.view.scope.CreateScope
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 성 이동 GUI — CRFramework View 기반
 *
 * 열기: TeleportGui.open(player, guild, isAtWar, plugin, gm)
 */
class TeleportGui private constructor(
    private val plugin: CRGuildPlugin,
    private val gm: GuildManager,
    private val guild: GuildData,
    private val isAtWar: Boolean
) : View(plugin, GUI_TITLE, rows = if (guild.beacons.size <= 9) 1 else 2) {

    companion object {
        private const val GUI_TITLE = "§6§l성 이동"

        // 카운트다운 대기 중인 플레이어 상태 (공유 static)
        private val pendingTeleports = HashMap<UUID, Location>()
        private val countdownTasks   = HashMap<UUID, BukkitTask>()

        /** GuildCommand 에서 호출하는 팩토리 */
        fun open(player: Player, guild: GuildData, isAtWar: Boolean, plugin: CRGuildPlugin, gm: GuildManager) {
            if (guild.beacons.isEmpty()) { player.sendMessage("§c등록된 성이 없습니다."); return }
            TeleportGui(plugin, gm, guild, isAtWar).open(player)
        }

        fun cancelPending(uuid: UUID) {
            countdownTasks.remove(uuid)?.cancel()
            pendingTeleports.remove(uuid)
        }

        fun isPending(uuid: UUID) = uuid in pendingTeleports
    }

    // ─── View 생명주기 ────────────────────────────────────────────────────

    override fun CreateScope.onCreate() {
        val delay = if (isAtWar) 10 else 3

        guild.beacons.forEachIndexed { i, beacon ->
            button(i) {
                item { _ ->
                    ItemStack(Material.BEACON).also { stack ->
                        val meta = stack.itemMeta!!
                        meta.setDisplayName("§e§l${i + 1}번성")
                        meta.lore = listOf(
                            "§7위치: §f${beacon.x}, ${beacon.y}, ${beacon.z}",
                            "§7월드: §f${beacon.world}",
                            "",
                            if (isAtWar) "§c§l⚔ 전쟁 중 — §f${delay}초 §c후 이동"
                            else         "§a좌클릭으로 이동 §8(${delay}초 후)",
                            ""
                        )
                        stack.itemMeta = meta
                    }
                }
                onClick { player ->
                    player.closeInventory()   // GUI 닫기 (handleClose → onClose no-op)

                    val world = Bukkit.getWorld(beacon.world)
                    if (world == null) { player.sendMessage("§c해당 월드를 찾을 수 없습니다."); return@onClick }

                    // 기존 카운트다운 취소 후 새 카운트다운 시작
                    cancelPending(player.uniqueId)

                    val dest = Location(world, (beacon.x - 2) + 0.5, (beacon.y + 1).toDouble(), beacon.z + 0.5)
                    pendingTeleports[player.uniqueId] = player.location.clone()

                    var remaining = delay
                    val task = plugin.scheduler.runTimer(0L, 20L) {
                        if (!player.isOnline) { cancelPending(player.uniqueId); return@runTimer }

                        // 움직임 감지
                        val saved = pendingTeleports[player.uniqueId]
                        if (saved != null && player.location.distance(saved) > 1.0) {
                            player.sendMessage("§c움직여서 이동이 취소되었습니다.")
                            showCancelTitle(player)
                            cancelPending(player.uniqueId)
                            return@runTimer
                        }

                        if (remaining > 0) {
                            showCountdownTitle(player, i + 1, remaining, guild.warActive)
                            remaining--
                        } else {
                            player.teleport(dest)
                            showArriveTitle(player, i + 1)
                            cancelPending(player.uniqueId)
                        }
                    }
                    countdownTasks[player.uniqueId] = task
                }
            }
        }
    }

    // 인벤토리를 수동으로 닫아도 카운트다운은 유지 (원본 동작과 동일)
    override fun onClose(player: Player) = Unit

    // ─── 타이틀 헬퍼 ─────────────────────────────────────────────────────

    private fun showCountdownTitle(player: Player, castleNum: Int, sec: Int, isWar: Boolean) {
        val color = if (isWar) "§c" else "§a"
        val times = Title.Times.times(Ticks.duration(0), Ticks.duration(25), Ticks.duration(5))
        player.showTitle(
            Title.title(
                Component.text("§e§l${castleNum}번성 이동"),
                Component.text("${color}§l${sec}초 §r§7후 이동합니다..."),
                times
            )
        )
    }

    private fun showArriveTitle(player: Player, castleNum: Int) {
        val times = Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(10))
        player.showTitle(
            Title.title(
                Component.text("§a§l이동 완료!"),
                Component.text("§f§l${castleNum}번성 §r§7에 도착했습니다."),
                times
            )
        )
    }

    private fun showCancelTitle(player: Player) {
        val times = Title.Times.times(Ticks.duration(3), Ticks.duration(30), Ticks.duration(10))
        player.showTitle(
            Title.title(
                Component.text("§c§l이동 취소"),
                Component.text("§7움직여서 이동이 취소되었습니다."),
                times
            )
        )
    }
}
