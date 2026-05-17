package io.zlero.cRGuild

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileInputStream

object CastleBuilder {

    private const val SCHEMATIC_FILE = "castle.schem"

    private var cachedClipboard: Clipboard? = null
    private var cachedFilePath: String? = null

    fun build(plugin: JavaPlugin, world: World, bx: Int, by: Int, bz: Int) {
        val schematicFile = File(plugin.dataFolder, SCHEMATIC_FILE)

        if (!schematicFile.exists()) {
            plugin.logger.severe("[CRGuild] 스케매틱 파일이 없습니다: ${schematicFile.absolutePath}")
            return
        }

        try {
            val clipboard = getClipboard(plugin, schematicFile)

            val weWorld = BukkitAdapter.adapt(world)
            val editSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .build()

            editSession.use { session ->
                val operation = ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(BlockVector3.at(bx, by, bz))
                    .ignoreAirBlocks(false)
                    .build()
                Operations.complete(operation)
            }

            plugin.logger.info("[CRGuild] 성 스케매틱 배치 완료! ($bx, $by, $bz)")
        } catch (e: Exception) {
            plugin.logger.severe("[CRGuild] 스케매틱 로딩 실패: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getClipboard(plugin: JavaPlugin, schematicFile: File): Clipboard {
        val path = schematicFile.absolutePath
        val cached = cachedClipboard
        if (cached != null && path == cachedFilePath) return cached

        val format = ClipboardFormats.findByFile(schematicFile)
            ?: throw IllegalStateException("지원하지 않는 스케매틱 형식입니다: $path")

        plugin.logger.info("[CRGuild] 스케매틱 파일 로드 중: $path")
        val clipboard = format.getReader(FileInputStream(schematicFile)).use { it.read() }
        cachedClipboard = clipboard
        cachedFilePath = path
        return clipboard
    }

    fun invalidateCache() {
        cachedClipboard = null
        cachedFilePath = null
    }
}
