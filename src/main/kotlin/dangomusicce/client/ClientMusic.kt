package dangomusicce.client

import dangomusicce.DangoClient
import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.module.impls.music.NeteaseMusicModule
import dangomusicce.music.MusicPlayer
import dangomusicce.music.NativeLogin
import dangomusicce.music.NearbyAudioPlayer
import dangomusicce.music.NeteaseLogin
import dangomusicce.network.NearbyClient
import dangomusicce.platform.McCompat
import dangomusicce.platform.ModernKeyBindings
import dangomusicce.platform.ModernPlaceholderScreen
import dangomusicce.platform.ModernPlatform
import dangomusicce.platform.StubGfx
import net.minecraft.client.gui.screens.Screen
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object ClientMusic {
    private val controls = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { command -> Thread(command, "DangoMusic-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile
    private var started = false

    fun start(directory: File) {
        if (started) return
        BilibiliRequests.loadRoom(directory.toPath().resolve("bilibili-room.txt"))
        DangoClient.start(ModernPlatform, StubGfx)
        NearbyClient.init()
        Runtime.getRuntime().addShutdownHook(Thread({
            controls.shutdownNow()
            runCatching { NeteaseLogin.cancel() }
            runCatching { NativeLogin.cancel() }
            runCatching { NearbyAudioPlayer.stop() }
            runCatching { BilibiliRequests.shutdown() }
            runCatching { MusicPlayer.shutdown() }
        }, "DangoMusic-shutdown"))
        started = true
    }

    fun control(action: () -> Unit) {
        if (controls.isShutdown) return
        try {
            controls.execute(action)
        } catch (t: Throwable) {
            DangoLogger.warn("Control action failed: {}", t.toString())
        }
    }

    fun setVolume(value: Float) {
        DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).setVolume(value)
    }

    fun settingsExportSnapshot(): String {
        val instance = DangoMusic.instance
        instance.moduleManager.get(NeteaseMusicModule::class.java).syncPlayerKeys()
        return instance.configManager.exportSnapshot(ModernKeyBindings.exportSnapshot())
    }

    fun open(parent: Screen?) {
        McCompat.setScreen(ModernPlaceholderScreen(parent))
    }
}
