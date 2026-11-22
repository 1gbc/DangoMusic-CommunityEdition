package dangomusicce.platform

import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.LegacyData
import dangomusicce.client.ClientMusic
import dangomusicce.events.TickEvent

/** 高版本客户端生命周期转发入口（各加载器客户端入口共用）。 */
object ModernDango {
    @Volatile
    @JvmStatic
    var started = false
        private set

    @JvmStatic
    fun start() {
        if (started) return
        val configDir = ModernPlatform.configDir
        LegacyData.migrateConfig(configDir.parent.toFile())
        ClientMusic.start(configDir.toFile())
        started = true
    }

    @JvmStatic
    fun tick() {
        if (!started) return
        try {
            DangoMusic.callEvent(TickEvent)
        } catch (t: Throwable) {
            DangoLogger.error("DangoMusic tick failed: {}", t.toString())
        }
    }
}
