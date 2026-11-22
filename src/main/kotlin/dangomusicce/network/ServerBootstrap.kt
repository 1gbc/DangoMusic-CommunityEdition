package dangomusicce.network

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import java.io.File

/** Server-only entry point; never loaded on a client. */
object ServerBootstrap {
    @JvmStatic
    fun init(event: FMLPreInitializationEvent) {
        // 从 DangoMusic GTNH Edition 升级的服务器：server.cfg 和听歌房（groups.json）跟着复制过来
        dangomusicce.LegacyData.migrateConfig(event.modConfigurationDirectory)
        ServerConfig.load(File(event.modConfigurationDirectory, "dangomusicce/server.cfg"))
        MusicGroups.attach(File(event.modConfigurationDirectory, "dangomusicce/groups.json"))
        FMLCommonHandler.instance().bus().register(ServerEvents)
    }

    @JvmStatic
    fun serverStarting(event: FMLServerStartingEvent) {
        event.registerServerCommand(CommandDangoMusic())
    }
}
