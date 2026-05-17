package io.zlero.cRGuild

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 길드 커스텀 아이템 유틸
 * ★ 폭탄: NETHER_STAR(네더의 별)로 재료 변경
 * ★ 전쟁 선포권: 신규 추가 (PAPER 기반)
 */
object GuildItems {

    private const val BOMB_NAME = "§c§l[ 길드 폭탄 ]"
    private val BOMB_LORE = listOf(
        "§7전쟁 중 적 길드 철문에",
        "§7우클릭하여 파괴할 수 있습니다.",
        "§8사용 시 소모됩니다."
    )

    // ★ 전쟁 선포권
    private const val WAR_TICKET_NAME = "§6§l[ 전쟁 선포권 ]"
    private val WAR_TICKET_LORE = listOf(
        "§7/전쟁 선포 명령어 사용 시 소모됩니다.",
        "§8길드장만 사용 가능합니다."
    )

    /** 폭탄 아이템 생성 (★ 재료: 네더의 별) */
    fun createBomb(amount: Int = 1): ItemStack {
        val item = ItemStack(Material.NETHER_STAR, amount)
        val meta = item.itemMeta!!
        meta.setDisplayName(BOMB_NAME)
        meta.lore = BOMB_LORE
        item.itemMeta = meta
        return item
    }

    /** 해당 아이템이 길드 폭탄인지 확인 (★ 재료: 네더의 별) */
    fun isBomb(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.NETHER_STAR) return false
        val meta = item.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName == BOMB_NAME
    }

    /** 전쟁 선포권 아이템 생성 */
    fun createWarTicket(amount: Int = 1): ItemStack {
        val item = ItemStack(Material.PAPER, amount)
        val meta = item.itemMeta!!
        meta.setDisplayName(WAR_TICKET_NAME)
        meta.lore = WAR_TICKET_LORE
        item.itemMeta = meta
        return item
    }

    /** 해당 아이템이 전쟁 선포권인지 확인 */
    fun isWarTicket(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.PAPER) return false
        val meta = item.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName == WAR_TICKET_NAME
    }
}