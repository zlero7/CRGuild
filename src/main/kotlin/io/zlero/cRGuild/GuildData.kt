package io.zlero.cRGuild

import java.util.UUID

object GuildLayout {
    const val TERRITORY_RADIUS = 25
    // 전쟁 비콘은 원본 비콘 기준 X-2, Y+13 위치에 있음
    const val WAR_BEACON_DX = -2
    const val WAR_BEACON_DY = 13
    // 에메랄드 블록은 원본 비콘 기준 Y+12 위치에 있음
    const val EMERALD_DY = 12
    // 영토 영역 파괴 높이 하한
    const val DESTROY_HEIGHT = 32
}

data class BeaconData(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int
) {
    val warX: Int get() = x + GuildLayout.WAR_BEACON_DX
    val warY: Int get() = y + GuildLayout.WAR_BEACON_DY

    fun isWarBeacon(w: String, wx: Int, wy: Int, wz: Int): Boolean =
        world == w && warX == wx && warY == wy && z == wz
}

data class GuildData(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    var master: UUID,
    val officers: MutableSet<UUID> = mutableSetOf(),
    val members: MutableSet<UUID> = mutableSetOf(),
    var level: Int = 1,
    var treasury: Long = 0L,
    var taxMissedWeeks: Int = 0,
    val beacons: MutableList<BeaconData> = mutableListOf(),
    var warTarget: String? = null,
    var warDeclaredAt: Long = 0L,
    var warActive: Boolean = false,
    var announcement: String = "",
    var surrenderCount: Int = 0,
    var isWarAttacker: Boolean = false,
    var lastTaxAt: Long = 0L
) {
    fun totalMembers(): Int = 1 + officers.size + members.size

    fun maxMembers(): Int = when (level) {
        1 -> 6; 2 -> 8; 3 -> 10; 4 -> 12; 5 -> 14; else -> 6
    }

    fun levelUpCost(): Long = when (level) {
        1 -> 5_000_000L; 2 -> 10_000_000L; 3 -> 20_000_000L; 4 -> 40_000_000L
        else -> Long.MAX_VALUE
    }

    fun weeklyTax(): Long = when (level) {
        1 -> 500_000L; 2 -> 800_000L; 3 -> 1_200_000L; 4 -> 1_800_000L; 5 -> 2_500_000L
        else -> 500_000L
    }

    fun isMember(uuid: UUID) = uuid == master || uuid in officers || uuid in members
    fun isOfficer(uuid: UUID) = uuid in officers || uuid == master
    fun isMaster(uuid: UUID) = uuid == master

    fun getRank(uuid: UUID): GuildRank = when {
        uuid == master   -> GuildRank.MASTER
        uuid in officers -> GuildRank.OFFICER
        uuid in members  -> GuildRank.MEMBER
        else             -> GuildRank.NONE
    }

    fun isInTerritory(world: String, x: Int, z: Int): Boolean {
        return beacons.any { b ->
            val cx = b.x + GuildLayout.WAR_BEACON_DX
            val r = GuildLayout.TERRITORY_RADIUS
            b.world == world &&
                    x in (cx - r)..(cx + r) &&
                    z in (b.z - r)..(b.z + r)
        }
    }
}

enum class GuildRank(val displayName: String) {
    MASTER("§6[ 길드장 ]"),
    OFFICER("§e[ 부길드장 ]"),
    MEMBER("§7[ 멤버 ]"),
    NONE("§8[ 없음 ]")
}
