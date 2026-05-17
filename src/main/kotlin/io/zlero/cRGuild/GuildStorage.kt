package io.zlero.cRGuild

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

// ════════════════════════════════════════════════════════════════
//  GuildStorage 인터페이스
// ════════════════════════════════════════════════════════════════

interface GuildStorage {
    fun init()
    fun loadAll(): List<GuildData>
    fun save(guild: GuildData)
    fun delete(guildName: String)
    fun loadLeaveCooldowns(): Map<UUID, Long>
    fun saveLeaveCooldowns(map: Map<UUID, Long>)
}

// ════════════════════════════════════════════════════════════════
//  팩토리
// ════════════════════════════════════════════════════════════════

object GuildStorageFactory {
    fun create(plugin: JavaPlugin): GuildStorage {
        val cfg  = plugin.config
        val type = cfg.getString("storage.type", "yaml")!!.lowercase()

        return when (type) {
            "mysql" -> SqlGuildStorage(
                plugin = plugin,
                driver = "com.mysql.cj.jdbc.Driver",
                url    = "jdbc:mysql://${cfg.getString("storage.host", "localhost")}:" +
                         "${cfg.getInt("storage.port", 3306)}/" +
                         "${cfg.getString("storage.database", "crguild")}?useSSL=false&characterEncoding=UTF-8",
                user   = cfg.getString("storage.username", "root")!!,
                pass   = cfg.getString("storage.password", "")!!
            )
            "sqlite" -> SqlGuildStorage(
                plugin = plugin,
                driver = "org.sqlite.JDBC",
                url    = "jdbc:sqlite:${File(plugin.dataFolder, "guilds.db").absolutePath}",
                user   = "",
                pass   = ""
            )
            else -> YamlGuildStorage(plugin)
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  YAML 구현체
// ════════════════════════════════════════════════════════════════

class YamlGuildStorage(private val plugin: JavaPlugin) : GuildStorage {

    private val guildsDir get() = File(plugin.dataFolder, "guilds")

    override fun init() { guildsDir.mkdirs() }

    override fun loadAll(): List<GuildData> {
        if (!guildsDir.exists()) return emptyList()
        val result = mutableListOf<GuildData>()
        guildsDir.listFiles { f -> f.extension == "yml" }?.forEach { file ->
            val cfg    = YamlConfiguration.loadConfiguration(file)
            val name   = cfg.getString("name")   ?: return@forEach
            val master = cfg.getString("master")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@forEach

            val guild = GuildData(
                id             = cfg.getString("id")?.let { runCatching { UUID.fromString(it) }.getOrElse { UUID.randomUUID() } } ?: UUID.randomUUID(),
                name           = name,
                master         = master,
                level          = cfg.getInt("level", 1),
                treasury       = cfg.getLong("treasury", 0L),
                taxMissedWeeks = cfg.getInt("taxMissedWeeks", 0),
                warTarget      = cfg.getString("warTarget"),
                warDeclaredAt  = cfg.getLong("warDeclaredAt", 0L),
                warActive      = cfg.getBoolean("warActive", false),
                announcement   = cfg.getString("announcement", "")!!,
                surrenderCount = cfg.getInt("surrenderCount", 0),
                isWarAttacker  = cfg.getBoolean("isWarAttacker", false),
                lastTaxAt      = cfg.getLong("lastTaxAt", 0L)
            )

            cfg.getStringList("officers").mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .forEach { guild.officers.add(it) }
            cfg.getStringList("members").mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .forEach { guild.members.add(it) }

            val beaconSec = cfg.getConfigurationSection("beacons")
            beaconSec?.getKeys(false)?.forEach { key ->
                val s = beaconSec.getConfigurationSection(key) ?: return@forEach
                guild.beacons.add(BeaconData(
                    world = s.getString("world") ?: return@forEach,
                    x     = s.getInt("x"),
                    y     = s.getInt("y"),
                    z     = s.getInt("z")
                ))
            }
            result.add(guild)
        }
        return result
    }

    override fun save(guild: GuildData) {
        guildsDir.mkdirs()
        val file = File(guildsDir, "${guild.name}.yml")
        val cfg  = YamlConfiguration()
        cfg.set("id",             guild.id.toString())
        cfg.set("name",           guild.name)
        cfg.set("master",         guild.master.toString())
        cfg.set("officers",       guild.officers.map { it.toString() })
        cfg.set("members",        guild.members.map  { it.toString() })
        cfg.set("level",          guild.level)
        cfg.set("treasury",       guild.treasury)
        cfg.set("taxMissedWeeks", guild.taxMissedWeeks)
        cfg.set("warTarget",      guild.warTarget)
        cfg.set("warDeclaredAt",  guild.warDeclaredAt)
        cfg.set("warActive",      guild.warActive)
        cfg.set("announcement",   guild.announcement)
        cfg.set("surrenderCount", guild.surrenderCount)
        cfg.set("isWarAttacker",  guild.isWarAttacker)
        cfg.set("lastTaxAt",      guild.lastTaxAt)
        guild.beacons.forEachIndexed { i, b ->
            cfg.set("beacons.$i.world", b.world)
            cfg.set("beacons.$i.x",     b.x)
            cfg.set("beacons.$i.y",     b.y)
            cfg.set("beacons.$i.z",     b.z)
        }
        cfg.save(file)
    }

    override fun delete(guildName: String) {
        File(guildsDir, "${guildName}.yml").delete()
    }

    override fun loadLeaveCooldowns(): Map<UUID, Long> {
        val file = File(plugin.dataFolder, "leave_cooldowns.yml")
        if (!file.exists()) return emptyMap()
        val cfg = YamlConfiguration.loadConfiguration(file)
        return cfg.getKeys(false).mapNotNull { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
            uuid to cfg.getLong(key)
        }.toMap()
    }

    override fun saveLeaveCooldowns(map: Map<UUID, Long>) {
        val file = File(plugin.dataFolder, "leave_cooldowns.yml")
        val cfg  = YamlConfiguration()
        map.forEach { (uuid, time) -> cfg.set(uuid.toString(), time) }
        cfg.save(file)
    }
}

// ════════════════════════════════════════════════════════════════
//  SQL 구현체 (MySQL / SQLite 공용) — HikariCP 커넥션 풀 사용
// ════════════════════════════════════════════════════════════════

class SqlGuildStorage(
    private val plugin: JavaPlugin,
    private val driver: String,
    private val url:    String,
    private val user:   String,
    private val pass:   String
) : GuildStorage {

    private lateinit var dataSource: HikariDataSource

    override fun init() {
        // MySQL 전용: DB 자동 생성
        if (url.startsWith("jdbc:mysql")) {
            val dbName  = url.substringAfterLast("/").substringBefore("?")
            val baseUrl = url.substringBeforeLast("/") + "?" + url.substringAfter("?")
            runCatching {
                Class.forName(driver)
                java.sql.DriverManager.getConnection(baseUrl, user, pass).use { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate(
                            "CREATE DATABASE IF NOT EXISTS `$dbName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                        )
                    }
                }
                plugin.logger.info("[CRGuild] MySQL 데이터베이스 '$dbName' 확인/생성 완료.")
            }.onFailure {
                plugin.logger.warning("[CRGuild] DB 자동 생성 시도 실패: ${it.message}")
            }
        }

        val cfg = HikariConfig().apply {
            jdbcUrl         = url
            username        = user.ifEmpty { null }
            password        = pass.ifEmpty { null }
            driverClassName = driver
            maximumPoolSize = 5
            minimumIdle     = 1
            connectionTimeout   = 10_000
            idleTimeout         = 60_000
            maxLifetime         = 1_800_000
            poolName            = "CRGuild-Pool"
        }
        dataSource = HikariDataSource(cfg)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guilds (
                        name           VARCHAR(64)   PRIMARY KEY,
                        id             VARCHAR(36)   NOT NULL,
                        master         VARCHAR(36)   NOT NULL,
                        level          INT           NOT NULL DEFAULT 1,
                        treasury       BIGINT        NOT NULL DEFAULT 0,
                        taxMissedWeeks INT           NOT NULL DEFAULT 0,
                        warTarget      VARCHAR(64),
                        warDeclaredAt  BIGINT        NOT NULL DEFAULT 0,
                        warActive      BOOLEAN       NOT NULL DEFAULT FALSE,
                        announcement   VARCHAR(512)  NOT NULL DEFAULT '',
                        surrenderCount INT           NOT NULL DEFAULT 0,
                        isWarAttacker  BOOLEAN       NOT NULL DEFAULT FALSE,
                        lastTaxAt      BIGINT        NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_members (
                        guild_name VARCHAR(64) NOT NULL,
                        uuid       VARCHAR(36) NOT NULL,
                        role       VARCHAR(16) NOT NULL,
                        PRIMARY KEY (guild_name, uuid)
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_beacons (
                        guild_name VARCHAR(64) NOT NULL,
                        idx        INT         NOT NULL,
                        world      VARCHAR(64) NOT NULL,
                        x          INT         NOT NULL,
                        y          INT         NOT NULL,
                        z          INT         NOT NULL,
                        PRIMARY KEY (guild_name, idx)
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS leave_cooldowns (
                        uuid       VARCHAR(36) PRIMARY KEY,
                        leave_time BIGINT NOT NULL
                    )
                """.trimIndent())
            }
        }
        plugin.logger.info("[CRGuild] SQL 저장소 초기화 완료 ($url)")
    }

    override fun loadAll(): List<GuildData> {
        val guilds = mutableMapOf<String, GuildData>()

        dataSource.connection.use { conn ->
            // 1) 길드 기본 정보 일괄 로드
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM guilds").use { rs ->
                    while (rs.next()) {
                        val name   = rs.getString("name")
                        val master = runCatching { UUID.fromString(rs.getString("master")) }.getOrNull() ?: continue
                        guilds[name] = GuildData(
                            id             = runCatching { UUID.fromString(rs.getString("id")) }.getOrElse { UUID.randomUUID() },
                            name           = name,
                            master         = master,
                            level          = rs.getInt("level"),
                            treasury       = rs.getLong("treasury"),
                            taxMissedWeeks = rs.getInt("taxMissedWeeks"),
                            warTarget      = rs.getString("warTarget"),
                            warDeclaredAt  = rs.getLong("warDeclaredAt"),
                            warActive      = rs.getBoolean("warActive"),
                            announcement   = rs.getString("announcement") ?: "",
                            surrenderCount = rs.getInt("surrenderCount"),
                            isWarAttacker  = rs.getBoolean("isWarAttacker"),
                            lastTaxAt      = rs.getLong("lastTaxAt")
                        )
                    }
                }
            }

            // 2) 멤버 일괄 로드 (N+1 없음)
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT guild_name, uuid, role FROM guild_members").use { rs ->
                    while (rs.next()) {
                        val guild = guilds[rs.getString("guild_name")] ?: continue
                        val uuid  = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                        when (rs.getString("role")) {
                            "officer" -> guild.officers.add(uuid)
                            "member"  -> guild.members.add(uuid)
                        }
                    }
                }
            }

            // 3) 비콘 일괄 로드 (N+1 없음)
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT guild_name, world, x, y, z FROM guild_beacons ORDER BY guild_name, idx").use { rs ->
                    while (rs.next()) {
                        val guild = guilds[rs.getString("guild_name")] ?: continue
                        guild.beacons.add(BeaconData(
                            world = rs.getString("world"),
                            x     = rs.getInt("x"),
                            y     = rs.getInt("y"),
                            z     = rs.getInt("z")
                        ))
                    }
                }
            }
        }

        return guilds.values.toList()
    }

    override fun save(guild: GuildData) {
        dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    REPLACE INTO guilds
                      (name,id,master,level,treasury,taxMissedWeeks,
                       warTarget,warDeclaredAt,warActive,announcement,surrenderCount,isWarAttacker,lastTaxAt)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()).use { ps ->
                    ps.setString(1,  guild.name)
                    ps.setString(2,  guild.id.toString())
                    ps.setString(3,  guild.master.toString())
                    ps.setInt(4,     guild.level)
                    ps.setLong(5,    guild.treasury)
                    ps.setInt(6,     guild.taxMissedWeeks)
                    ps.setString(7,  guild.warTarget)
                    ps.setLong(8,    guild.warDeclaredAt)
                    ps.setBoolean(9, guild.warActive)
                    ps.setString(10, guild.announcement)
                    ps.setInt(11,    guild.surrenderCount)
                    ps.setBoolean(12, guild.isWarAttacker)
                    ps.setLong(13,   guild.lastTaxAt)
                    ps.executeUpdate()
                }

                conn.prepareStatement("DELETE FROM guild_members WHERE guild_name = ?").use { ps ->
                    ps.setString(1, guild.name); ps.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO guild_members (guild_name, uuid, role) VALUES (?, ?, ?)"
                ).use { ps ->
                    guild.officers.forEach { uuid ->
                        ps.setString(1, guild.name); ps.setString(2, uuid.toString()); ps.setString(3, "officer")
                        ps.addBatch()
                    }
                    guild.members.forEach { uuid ->
                        ps.setString(1, guild.name); ps.setString(2, uuid.toString()); ps.setString(3, "member")
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }

                conn.prepareStatement("DELETE FROM guild_beacons WHERE guild_name = ?").use { ps ->
                    ps.setString(1, guild.name); ps.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO guild_beacons (guild_name, idx, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    guild.beacons.forEachIndexed { i, b ->
                        ps.setString(1, guild.name); ps.setInt(2, i)
                        ps.setString(3, b.world); ps.setInt(4, b.x); ps.setInt(5, b.y); ps.setInt(6, b.z)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    override fun delete(guildName: String) {
        dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                for (table in listOf("guild_beacons", "guild_members")) {
                    conn.prepareStatement("DELETE FROM $table WHERE guild_name = ?").use { ps ->
                        ps.setString(1, guildName); ps.executeUpdate()
                    }
                }
                conn.prepareStatement("DELETE FROM guilds WHERE name = ?").use { ps ->
                    ps.setString(1, guildName); ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    override fun loadLeaveCooldowns(): Map<UUID, Long> {
        val map = mutableMapOf<UUID, Long>()
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT uuid, leave_time FROM leave_cooldowns").use { rs ->
                        while (rs.next()) {
                            val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                            map[uuid] = rs.getLong("leave_time")
                        }
                    }
                }
            }
        }.onFailure { plugin.logger.warning("[CRGuild] 탈퇴 쿨타임 로드 실패: ${it.message}") }
        return map
    }

    override fun saveLeaveCooldowns(map: Map<UUID, Long>) {
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.executeUpdate("DELETE FROM leave_cooldowns") }
                if (map.isEmpty()) return
                conn.prepareStatement(
                    "INSERT INTO leave_cooldowns (uuid, leave_time) VALUES (?, ?)"
                ).use { ps ->
                    map.forEach { (uuid, time) ->
                        ps.setString(1, uuid.toString()); ps.setLong(2, time)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }.onFailure { plugin.logger.warning("[CRGuild] 탈퇴 쿨타임 저장 실패: ${it.message}") }
    }

    fun close() { runCatching { if (::dataSource.isInitialized) dataSource.close() } }
}
