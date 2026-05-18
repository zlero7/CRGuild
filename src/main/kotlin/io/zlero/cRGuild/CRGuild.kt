package io.zlero.cRGuild

import io.zlero.cRFramework.CRPlugin
import kotlin.reflect.KClass

class CRGuildPlugin : CRPlugin() {

    // CRGuildAPI 하위 호환성을 위해 inject()로 위임
    val guildManager: GuildManager get() = inject()

    override fun components(): List<KClass<*>> = listOf(
        GuildConfig::class,
        GuildManager::class,
        GuildListener::class,
        GuildCommand::class,
        WarCommand::class,
        GuildAdminCommand::class,
        WarAdminCommand::class,
    )

    override fun onCREnabled() {
        instance = this
        logger.info("CRGuild 활성화 완료!")
    }

    override fun onCRDisabled() {
        logger.info("CRGuild 비활성화 완료.")
    }

    companion object {
        lateinit var instance: CRGuildPlugin
            private set
    }
}
