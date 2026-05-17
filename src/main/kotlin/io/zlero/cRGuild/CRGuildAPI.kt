package io.zlero.cRGuild

import org.bukkit.entity.Player
import java.util.UUID

/**
 * CRGuild 외부 API
 *
 * 다른 플러그인에서 길드 정보를 조회할 때 사용합니다.
 *
 * ─── 사용 예시 (다른 플러그인에서) ───────────────────────────────────────
 *
 * // 1. 플러그인 가져오기
 * val crGuild = Bukkit.getPluginManager().getPlugin("CRGuild") as? CRGuildPlugin
 *
 * // 2. API 사용
 * val guildName = CRGuildAPI.getGuildName(player)           // "우리길드" or null
 * val rank      = CRGuildAPI.getGuildRank(player)           // "길드장" / "부길드장" / "멤버" / null
 * val isAtWar   = CRGuildAPI.isAtWar(player)                // true / false
 *
 * ─── softdepend 설정 (상대 플러그인의 plugin.yml에 추가) ──────────────────
 * softdepend: [CRGuild]
 */
object CRGuildAPI {

    private val plugin get() = CRGuildPlugin.instance
    private val gm     get() = plugin.guildManager

    // ─── 길드 소속 조회 ───────────────────────────────────────────────────

    /** 플레이어가 소속된 길드명 반환. 소속 없으면 null */
    fun getGuildName(player: Player): String? =
        gm.getGuildByPlayer(player)?.name

    /** UUID로 길드명 반환 */
    fun getGuildName(uuid: UUID): String? =
        gm.getGuildByPlayer(uuid)?.name

    /** 플레이어가 길드에 소속되어 있는지 */
    fun isInGuild(player: Player): Boolean =
        gm.getGuildByPlayer(player) != null

    // ─── 길드 상세 정보 ───────────────────────────────────────────────────

    /** 플레이어의 길드 레벨 반환. 소속 없으면 null */
    fun getGuildLevel(player: Player): Int? =
        gm.getGuildByPlayer(player)?.level

    /** 플레이어의 길드 국고 잔액 반환. 소속 없으면 null */
    fun getGuildTreasury(player: Player): Long? =
        gm.getGuildByPlayer(player)?.treasury

    /** 플레이어의 길드 인원 수 반환. 소속 없으면 null */
    fun getGuildMemberCount(player: Player): Int? =
        gm.getGuildByPlayer(player)?.totalMembers()

    /** 플레이어의 길드 공지 반환. 소속 없으면 null */
    fun getGuildAnnouncement(player: Player): String? =
        gm.getGuildByPlayer(player)?.announcement

    // ─── 직책 조회 ────────────────────────────────────────────────────────

    /**
     * 플레이어의 길드 직책 반환
     * @return GuildRank.MASTER / OFFICER / MEMBER / NONE
     */
    fun getGuildRank(player: Player): GuildRank =
        gm.getGuildByPlayer(player)?.getRank(player.uniqueId) ?: GuildRank.NONE

    /** 플레이어가 길드장인지 */
    fun isMaster(player: Player): Boolean =
        gm.getGuildByPlayer(player)?.isMaster(player.uniqueId) == true

    /** 플레이어가 부길드장 이상인지 */
    fun isOfficerOrAbove(player: Player): Boolean =
        gm.getGuildByPlayer(player)?.isOfficer(player.uniqueId) == true

    // ─── 전쟁 상태 조회 ───────────────────────────────────────────────────

    /** 플레이어의 길드가 현재 전쟁 중인지 */
    fun isAtWar(player: Player): Boolean =
        gm.getGuildByPlayer(player)?.warActive == true

    /** 플레이어의 길드가 전쟁 중인 상대 길드명 반환. 전쟁 중 아니면 null */
    fun getWarTarget(player: Player): String? =
        gm.getGuildByPlayer(player)?.warTarget

    /** 두 플레이어가 서로 교전 중인 길드 소속인지 */
    fun isEnemies(a: Player, b: Player): Boolean {
        val guildA = gm.getGuildByPlayer(a) ?: return false
        val guildB = gm.getGuildByPlayer(b) ?: return false
        return gm.isAtWar(guildA, guildB)
    }

    /** 두 플레이어가 같은 길드 소속인지 */
    fun isSameGuild(a: Player, b: Player): Boolean {
        val nameA = getGuildName(a) ?: return false
        val nameB = getGuildName(b) ?: return false
        return nameA == nameB
    }

    // ─── 영토 조회 ────────────────────────────────────────────────────────

    /** 해당 좌표가 어느 길드의 영토인지 반환. 영토 없으면 null */
    fun getGuildNameAt(world: String, x: Int, z: Int): String? =
        gm.getGuildByTerritory(world, x, z)?.name

    /** 플레이어가 현재 서 있는 위치가 어느 길드 영토인지 반환 */
    fun getGuildNameAt(player: Player): String? {
        val loc = player.location
        return gm.getGuildByTerritory(loc.world?.name ?: return null, loc.blockX, loc.blockZ)?.name
    }

    /** 플레이어가 자신의 길드 영토 안에 있는지 */
    fun isInOwnTerritory(player: Player): Boolean {
        val guild = gm.getGuildByPlayer(player) ?: return false
        val loc   = player.location
        return guild.isInTerritory(loc.world?.name ?: return false, loc.blockX, loc.blockZ)
    }

    /** 플레이어가 적 길드 영토 안에 있는지 */
    fun isInEnemyTerritory(player: Player): Boolean {
        val myGuild    = gm.getGuildByPlayer(player) ?: return false
        val loc        = player.location
        val otherGuild = gm.getGuildByTerritory(loc.world?.name ?: return false, loc.blockX, loc.blockZ)
            ?: return false
        return otherGuild.name != myGuild.name
    }

    // ─── 길드명으로 직접 조회 ─────────────────────────────────────────────

    /** 길드명으로 길드 레벨 조회 */
    fun getGuildLevel(guildName: String): Int? =
        gm.getGuild(guildName)?.level

    /** 길드명으로 길드 인원 수 조회 */
    fun getGuildMemberCount(guildName: String): Int? =
        gm.getGuild(guildName)?.totalMembers()

    /** 모든 길드 이름 목록 */
    fun getAllGuildNames(): Set<String> =
        gm.getAllGuildNames()
}