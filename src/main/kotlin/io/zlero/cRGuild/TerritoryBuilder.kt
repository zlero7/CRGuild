package io.zlero.cRGuild

import org.bukkit.Material
import org.bukkit.World

/**
 * 영토 생성기
 *
 * 전쟁 신호기(bx-2, bz) 기준 50x50 영토:
 *  - 테두리: 기반암
 *  - 내부: 잔디
 *  - Y 기준: 신호기 설치 Y (by) - 1 (지면)
 *
 * 영토 중심 = bx-2 (전쟁 신호기 x 좌표)
 * 범위:
 *  x: (bx-2)-25 ~ (bx-2)+25
 *  z: bz-25 ~ bz+25
 */
object TerritoryBuilder {

    /** 영토 바닥 생성 */
    fun build(world: World, bx: Int, by: Int, bz: Int) {
        val groundY = by - 1
        val cx = bx + GuildLayout.WAR_BEACON_DX
        val r  = GuildLayout.TERRITORY_RADIUS

        for (dx in -r..r) {
            for (dz in -r..r) {
                val isBorder = dx == -r || dx == r || dz == -r || dz == r
                val mat = if (isBorder) Material.BEDROCK else Material.GRASS_BLOCK
                world.getBlockAt(cx + dx, groundY, bz + dz).type = mat
            }
        }
    }

    /**
     * 영토 안의 블록을 전부 AIR로 제거 (길드 해산 / 전쟁 패배 시 호출).
     */
    fun destroy(world: World, bx: Int, by: Int, bz: Int) {
        val groundY = by - 1
        val topY    = by + GuildLayout.DESTROY_HEIGHT
        val cx = bx + GuildLayout.WAR_BEACON_DX
        val r  = GuildLayout.TERRITORY_RADIUS

        for (dx in -r..r) {
            for (dz in -r..r) {
                for (y in groundY..topY) {
                    world.getBlockAt(cx + dx, y, bz + dz).type = Material.AIR
                }
            }
        }
    }
}