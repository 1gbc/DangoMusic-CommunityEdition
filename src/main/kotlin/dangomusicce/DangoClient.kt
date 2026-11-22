package dangomusicce

import dangomusicce.font.CjkFont
import dangomusicce.gfx.Gfx
import dangomusicce.i18n.I18n
import dangomusicce.module.impls.music.DynamicIslandModule
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.module.impls.music.NearbyModule
import dangomusicce.module.impls.music.NeteaseMusicModule
import dangomusicce.music.MusicScreen
import dangomusicce.platform.Platform

/**
 * 客户端入口：平台层在 mod 初始化时调用 [start]，把游戏接口（[Platform]）和绘图接口（[Gfx]）交进来。
 */
object DangoClient {

    lateinit var gfx: Gfx
        private set

    val platform: Platform get() = DangoMusic.instance.platform

    @JvmStatic
    fun start(platform: Platform, gfx: Gfx) {
        this.gfx = gfx
        CjkFont.preloadSystemFonts()
        I18n.language = I18n.CHINESE
        val musicModule = NeteaseMusicModule()
        DangoMusic.start(
            platform,
            listOf(
                musicModule,
                LyricsModule(),
                DynamicIslandModule(),
                NearbyModule(),
            ),
        )
        musicModule.applyLanguage() // ConfigManager 已读回手动选择，优先于首次默认值。
    }

    /** 平台层打开播放器界面时用：新建一个播放器界面，[close] 由平台层提供（关掉这个界面） */
    @JvmStatic
    fun newMusicScreen(close: () -> Unit): MusicScreen =
        MusicScreen(DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java), close)

    @JvmStatic
    fun renderMainMenuNowPlaying() {
        if (platform.inMainMenu) {
            val modules = DangoMusic.instance.moduleManager
            val island = modules.get(DynamicIslandModule::class.java)
            island.renderIsland()
        }
    }
}
