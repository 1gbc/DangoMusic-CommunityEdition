package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.gfx.CjkStyle
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.musicui.AppleLyricsLayout
import me.sorihiko.dangomusicgtnh.musicui.AppleLyricsMenu
import me.sorihiko.dangomusicgtnh.musicui.AppleQueuePanel
import me.sorihiko.dangomusicgtnh.musicui.ArtistPage
import me.sorihiko.dangomusicgtnh.musicui.ArtistRef
import me.sorihiko.dangomusicgtnh.musicui.GridPage
import me.sorihiko.dangomusicgtnh.musicui.AppleDiscoverPage
import me.sorihiko.dangomusicgtnh.musicui.FlowingCoverBackdrop
import me.sorihiko.dangomusicgtnh.musicui.GroupPage
import me.sorihiko.dangomusicgtnh.musicui.Library
import me.sorihiko.dangomusicgtnh.musicui.LoginDialog
import me.sorihiko.dangomusicgtnh.musicui.LyricsPage
import me.sorihiko.dangomusicgtnh.musicui.SongComments
import me.sorihiko.dangomusicgtnh.musicui.Page
import me.sorihiko.dangomusicgtnh.musicui.PageSnapshot
import me.sorihiko.dangomusicgtnh.musicui.AppleLyricsIcons
import me.sorihiko.dangomusicgtnh.musicui.PlayerIcons
import me.sorihiko.dangomusicgtnh.musicui.PlayerBar
import me.sorihiko.dangomusicgtnh.musicui.RED
import me.sorihiko.dangomusicgtnh.musicui.ScreenState
import me.sorihiko.dangomusicgtnh.musicui.SettingsPage
import me.sorihiko.dangomusicgtnh.musicui.Sidebar
import me.sorihiko.dangomusicgtnh.musicui.TracksPage
import me.sorihiko.dangomusicgtnh.musicui.Ui
import me.sorihiko.dangomusicgtnh.musicui.scaleCoordinates
import me.sorihiko.dangomusicgtnh.platform.Keys
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.utils.Color

import kotlin.math.min

/**
 * 内置网易云播放器界面（液态玻璃风格，排版参考 MuoniumPlayer）。
 * 这个类只是"外壳"：打开 / 关闭、每帧的准备工作、页面切换、把鼠标键盘分发给各部分。
 * 它不依赖任何 Minecraft 版本：各版本的平台层用一个很薄的游戏界面类把事件转发进来
 * （[opened] / [render] / [mouseClicked] / [keyTyped] …），鼠标和窗口大小从 [DangoClient.platform] 读。
 * 具体的东西在 musicui 包里：
 *   [Sidebar] 左侧栏 · [PlayerBar] 底部播放栏 · [LoginDialog] 扫码登录
 *   [GridPage] 主页 / 排行榜歌单墙 · [TracksPage] 歌单 / 搜索 / 队列 · [SettingsPage] 设置
 *   [Library] 从网易云读来的数据 · [ScreenState] 关掉再打开还保留的界面状态 · [Ui] 绘制工具
 *
 * 菜单字体（Inter）只有拉丁字符，所以中文（歌名、歌手、提示）和图标符号用 Minecraft 自带字体画。
 */
class MusicScreen(
    private val module: NeteaseMusicModule,
    /** 关掉这个界面（由平台层提供） */
    private val closeScreen: () -> Unit,
) {
    private val platform get() = DangoClient.platform

    internal val ui = Ui()
    internal val sidebar = Sidebar(this, module.appleLibraryHidden)
    internal val tracks = TracksPage(this)
    internal val login = LoginDialog(this)
    internal val grid = GridPage(this)
    private val discover = AppleDiscoverPage(this)
    private val artistPage = ArtistPage(this)
    private val groupPage = GroupPage(this)
    private val settings = SettingsPage(this, module)
    internal val songComments = SongComments(ui)
    internal val coverBackdrop = FlowingCoverBackdrop(ui)
    private val lyricsPage = LyricsPage(ui, module, songComments, coverBackdrop)
    private val appleLyricsMenu = AppleLyricsMenu(ui, module.lyricsPageRomanization, module.lyricsPageTranslation)
    private val appleBarMenu = AppleLyricsMenu(ui, module.lyricsPageRomanization, module.lyricsPageTranslation).apply {
        playerPanel = { bounds -> coverBackdrop.drawControlPanel(bounds, 8f) }
    }
    private val appleQueue = AppleQueuePanel(ui)
    private var appleQueueArea = LayoutBounds()
    internal val appleQueueOpen get() = appleQueue.open
    internal val appleBarMenuOpen get() = appleBarMenu.capturesInput
    internal fun toggleAppleQueue() = appleQueue.toggle()
    internal fun toggleAppleBarMenu() = appleBarMenu.toggle()
    private var appleLyricsFrame: AppleLyricsLayout? = null
    private var showAppleLyrics = true
    private var appleLyricsTrack: NeteaseTrack? = null
    private var lyricsOpen = false
    private val lyricsSource: MusicSource?
        get() {
            val playback = PlaybackView.snapshot()
            return if (playback.remote) null else playback.track?.source ?: MusicSources.selected
        }
    private val appleLyricsOpen get() = lyricsOpen && when (lyricsSource) {
        MusicSource.APPLE -> true
        MusicSource.NETEASE -> module.neteaseUsesAppleLayout
        else -> false
    }
    internal val canOpenLyrics get() = when (lyricsSource) {
        MusicSource.NETEASE, MusicSource.APPLE -> true
        else -> false
    }

    /** 平台层根据搜索、筛选和设置输入框的焦点开关系统输入法。 */
    val textInputActive: Boolean
        get() = !lyricsOpen && !login.visible && closingAt == 0L &&
            (sidebar.searchFocused || tracks.filterFocused || settings.editing || groupPage.editing || bar.volumeEditing)
    private val bar = PlayerBar(this, module)

    private var openedAt = 0L
    private var closingAt = 0L
    private var closingAlpha = 1f
    private var closed = false
    private var openKeyHeld = false
    private var lastNanos = 0L
    /** 换页（含换歌单 / 设置换标签 / 新搜索 / 列表↔封面墙）就重新播入场动画 */
    private var pageKey = ""

    /** 界面打开时调用 */
    fun opened() {
        if (MusicSources.selected == MusicSource.APPLE) AppleWebPlayer.restoreNativeIfNeeded()
        if (closingAt != 0L) return // 关闭期间的窗口尺寸变化不重新播放入场。
        closed = false
        openKeyHeld = platform.isKeyDown(module.openKey.value)
        if (ScreenState.context == null) ScreenState.context = DangoClient.gfx.newCanvas()
        openedAt = System.currentTimeMillis()
        Library.ensureSource()
        NativeAccounts.refreshProfile(MusicSources.selected)
        if (Library.playlists.isEmpty()) Library.reloadPlaylists()
        else if (Library.stale) Library.softRefresh()
        Library.loadRecommend()
    }

    private fun close() {
        libraryPull = null
        if (closingAt != 0L || closed) return
        val now = System.currentTimeMillis()
        closingAlpha = opacity(now)
        closingAt = now
        settings.commitEdit()
        settings.stopDrag()
        tracks.scrollbarUp()
        tracks.queueDrag.cancel()
        appleQueue.queueDrag.cancel()
        tracks.resetCoverflow()
    }

    private fun opacity(now: Long): Float = if (closingAt == 0L) {
        ((now - openedAt) / 180f).coerceIn(0f, 1f)
    } else {
        closingAlpha * (1f - (now - closingAt) / 180f).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- 导航

    internal fun selectSource(source: MusicSource) {
        if (source == MusicSources.selected) return
        bar.dismissMenu()
        appleBarMenu.reset()
        appleQueue.close()
        module.selectSource(source)
        if (source == MusicSource.APPLE) AppleWebPlayer.restoreNativeIfNeeded()
        NativeAccounts.refreshProfile(source)
        sidebar.searchText = ""
        sidebar.searchFocused = false
        ScreenState.lastQuery = ""
        ScreenState.opened = null
        Library.ensureSource()
        go(Page.HOME)
    }

    internal fun go(p: Page) {
        libraryPull = null
        libraryPullOffset = 0f
        groupPage.clearFocus()
        closeLyrics()
        tracks.clearFilter()
        ScreenState.page = p
        ScreenState.backPage = null
        ScreenState.trail.clear()
        ScreenState.artist = null
        tracks.resetScroll()
        grid.resetScroll()
        discover.resetScroll()
        when (p) {
            Page.HOME -> Library.loadRecommend()
            Page.CHARTS -> Library.loadCharts()
            Page.QUEUE -> if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
            else -> {}
        }
    }

    internal fun goBack() {
        if (lyricsOpen) {
            if (!appleLyricsMenu.dismiss() && !lyricsPage.dismissMenu()) closeLyrics()
            return
        }
        if (ScreenState.page == Page.SETTINGS) {
            leaveSettings()
            return
        }
        if (ScreenState.page == Page.GROUP) {
            go(Page.HOME)
            return
        }
        val b = ScreenState.backPage
        if (b != null) {
            ScreenState.page = b
            ScreenState.backPage = null
            return
        }
        // 歌手页、从歌手页点进的专辑：退回进来之前的那一层
        val snap = ScreenState.trail.removeLastOrNull()
        if (snap == null) {
            if (ScreenState.page == Page.ARTIST) go(Page.HOME)
            return
        }
        if (ScreenState.opened?.id != snap.opened?.id) tracks.clearFilter()
        ScreenState.page = snap.page
        ScreenState.opened = snap.opened
        ScreenState.backPage = snap.backPage
        ScreenState.artist = snap.artist
        ScreenState.lastQuery = snap.lastQuery
        tracks.resetScroll()
        grid.resetScroll()
    }

    /** 记下现在的界面，之后"返回"能回到这里 */
    private fun pushTrail() {
        val s = ScreenState
        s.trail.addLast(PageSnapshot(s.page, s.opened, s.backPage, s.artist, s.lastQuery))
        while (s.trail.size > 20) s.trail.removeFirst()
    }

    /** 打开歌手页（当前音源的歌手）；已经在看这个歌手就不动 */
    internal fun openArtist(ref: ArtistRef) {
        closeLyrics()
        if (ref.source != MusicSources.selected || !MusicSources.api(ref.source).hasArtists) return
        val cur = ScreenState.artist
        if (ScreenState.page == Page.ARTIST && cur != null && cur.source == ref.source &&
            ((ref.id.isNotEmpty() && cur.id == ref.id) || (ref.id.isEmpty() && cur.name == ref.name))) return
        if (ScreenState.page == Page.SETTINGS) ScreenState.page = ScreenState.settingsBack
        pushTrail()
        tracks.clearFilter()
        ScreenState.page = Page.ARTIST
        ScreenState.artist = ref
        ScreenState.backPage = null
        tracks.resetScroll()
        grid.resetScroll()
    }

    internal fun goBackOrHome() {
        if (ScreenState.backPage != null) goBack() else go(Page.HOME)
    }

    internal fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        // 从歌手页点进专辑：记下歌手页，返回时回到它
        val fromArtist = from == Page.ARTIST
        if (fromArtist) pushTrail()
        if (ScreenState.opened?.id != p.id) tracks.clearFilter()
        ScreenState.opened = p
        ScreenState.page = Page.PLAYLIST
        ScreenState.backPage = if (fromArtist) null else from
        tracks.resetScroll()
        tracks.resetCoverflow()
        Library.loadTracks(p.id)
    }

    internal fun doSearch(live: Boolean = false) {
        appleSearchAt = 0L
        val q = sidebar.searchText.trim()
        if (q.isEmpty()) return
        if (appleNavigation && !live) {
            appleSearchHistory.remove(q)
            appleSearchHistory.add(0, q)
            if (appleSearchHistory.size > 8) appleSearchHistory.removeAt(8)
        }
        if (!live) sidebar.searchFocused = false
        tracks.clearFilter()
        if (ScreenState.page != Page.SEARCH) ScreenState.backPage = ScreenState.page
        ScreenState.page = Page.SEARCH
        tracks.resetScroll()
        tracks.resetCoverflow()
        ScreenState.lastQuery = q
        Library.search(q, appleNavigation && ScreenState.appleLibrarySearch)
    }

    internal val appleNavigation get() = MusicSources.selected == MusicSource.APPLE
    internal val neteaseUsesAppleLayout get() = MusicSources.selected == MusicSource.NETEASE && module.neteaseUsesAppleLayout
    internal val usesAppleLayout get() = appleNavigation || neteaseUsesAppleLayout
    internal val appleSidebarUsesDarkGlass get() = usesAppleLayout && module.appleSidebarUsesDarkGlass
    private val appleSearchHistory = mutableListOf<String>()
    private var appleSearchAt = 0L

    internal fun appleSearchEdited() {
        if (neteaseUsesAppleLayout && ScreenState.page == Page.SEARCH && sidebar.searchText.isBlank()) {
            ScreenState.lastQuery = ""
        }
        if (!appleNavigation || ScreenState.page != Page.SEARCH) return
        if (sidebar.searchText.isBlank()) {
            appleSearchAt = 0L
            ScreenState.lastQuery = ""
            loadAppleSearchCategories()
        } else {
            appleSearchAt = System.nanoTime() + 350_000_000L
        }
    }

    private fun loadAppleSearchCategories() {
        if (ScreenState.appleLibrarySearch) Library.loadApplePage(AppleMusicSource.Section.GENRES)
        else Library.loadCatalogGenres()
    }

    internal fun openAppleSearch() {
        if (ScreenState.page != Page.SEARCH) {
            go(Page.SEARCH)
            ScreenState.lastQuery = ""
            sidebar.searchText = ""
        }
        sidebar.searchFocused = true
        if (appleNavigation) loadAppleSearchCategories()
    }

    internal fun openAppleSection(section: AppleMusicSource.Section) {
        ScreenState.appleSection = section
        go(Page.APPLE_BROWSE)
        Library.loadApplePage(section)
    }

    internal fun openGridCard(playlist: NeteasePlaylist) {
        val station = if (appleNavigation) AppleMusicSource.stationTrack(playlist.id) else null
        if (station != null) ClientMusic.control { MusicPlayer.playQueue(listOf(station), 0) }
        else openPlaylist(playlist, ScreenState.page)
    }

    internal fun openAppleWebsite(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { Library.showStatus(I18n.text("Could not open the browser")) }
    }

    internal fun toggleSettings() {
        closeLyrics()
        settings.commitEdit()
        blurTextFields()
        settings.bindingKey = null
        if (ScreenState.page == Page.SETTINGS) {
            leaveSettings()
        } else {
            ScreenState.settingsBack = ScreenState.page
            ScreenState.page = Page.SETTINGS
            settings.openPage()
        }
    }

    private fun leaveSettings() {
        val page = ScreenState.settingsBack
        if (page == Page.APPLE_BROWSE && !appleNavigation) go(Page.HOME)
        else ScreenState.page = page
    }

    internal fun toggleCoverView() {
        ScreenState.coverView = !ScreenState.coverView
        if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
    }

    internal fun toggleLyrics() {
        if (lyricsOpen) {
            closeLyrics()
        } else if (canOpenLyrics) {
            appleBarMenu.dismiss()
            appleQueue.close()
            settings.commitEdit()
            settings.bindingKey = null
            sidebar.menuOpen = false
            blurTextFields()
            lyricsOpen = true
        }
    }

    internal fun openSongComments() {
        if (!module.songCommentsEnabled.get() || lyricsSource != MusicSource.NETEASE) return
        if (!lyricsOpen) toggleLyrics()
        if (lyricsOpen) lyricsPage.openComments()
    }

    private fun closeLyrics() {
        if (!lyricsOpen) return
        lyricsOpen = false
        lyricsPage.reset()
        appleLyricsMenu.reset()
        appleLyricsFrame = null
        appleLyricsTrack = null
        bar.endAppleLyricsControls()
    }

    /** 取消所有输入框的焦点（搜索、筛选、房间码） */
    internal fun blurTextFields() {
        sidebar.searchFocused = false
        tracks.filterFocused = false
    }

    // ---------------------------------------------------------------- 绘制

    /** 每帧调用 */
    @JvmOverloads
    fun render(menuBackdropReady: Boolean = false) {
        if (closed) return
        val alpha = opacity(System.currentTimeMillis())
        if (closingAt != 0L && alpha <= 0f) {
            closed = true
            if (platform.isKeyDown(module.openKey.value)) module.consumeOpenKey()
            closeScreen()
            return
        }
        val source = MusicSources.selected
        val menu = !lyricsOpen
        val baseScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val scale = if (menu) menuScaleForWindow(baseScale, module.menuTextScale(source),
            platform.windowWidth, platform.windowHeight) else baseScale
        menuTextScale = scale / baseScale
        applyMenuFont(if (menu) module.menuFont(source) else "")
        ui.scale = scale
        ui.lw = platform.windowWidth / scale
        ui.lh = platform.windowHeight / scale
        ui.mx = (platform.mouseX / scale).toInt()
        ui.my = (platform.mouseY / scale).toInt()
        val ctx = ui.ctx
        // 没有世界时原版不会在播放器下面重画背景。先覆盖上一帧，
        // 否则玻璃会反复采样自身，饱和度不断累积，最终变成亮粉色。
        if (platform.player == null && !menuBackdropReady) {
            ctx.begin(false, ui.mx, ui.my)
            try {
                ctx.drawShape(
                    LayoutBounds(0f, 0f, platform.windowWidth.toFloat(), platform.windowHeight.toFloat()),
                    ShapeStyle(Color(0.075f, 0.085f, 0.11f, 1f)),
                )
            } finally {
                ctx.end()
            }
        }
        ctx.begin(true, ui.mx, ui.my)
        try {
            val now = System.nanoTime()
            if (appleSearchAt != 0L && now >= appleSearchAt) {
                appleSearchAt = 0L
                if (appleNavigation && !lyricsOpen && ScreenState.page == Page.SEARCH) doSearch(live = true)
            }
            ui.frameDt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
            lastNanos = now
            grid.tick()
            tracks.tick(platform.mouseX / ui.scale)
            artistPage.tick()
            settings.tick()

            ui.hits.clear()
            ui.beginPopupFrame()
            settings.beginFrame()
            ui.mt.clear()
            tracks.beginFrame()
            appleQueue.queueDrag.beginFrame()
            CoverCache.upload()
            login.uploadQr()

            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.22f)))
            drawWindow(alpha)
            // 播放栏浮在内容上面：先把内容的文字画出来，再把画好的内容截成玻璃背景，
            // 这样列表滚到播放栏下面时会模糊地透出来，而不是被整个盖住
            ctx.popOpacity()
            ctx.popTransform()
            ctx.noScissor()
            ui.mt.flush(scale)
            ctx.recaptureBackdrop()
            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            drawBar()
            ctx.popOpacity()
            ctx.popTransform()
            ctx.noScissor()
            ui.mt.flush(scale)

            if (login.visible) {
                ctx.pushScale(scale, scale, 1f)
                ctx.pushOpacity(alpha)
                login.draw()
                ctx.popOpacity()
                ctx.popTransform()
                ui.mt.flush(scale)
            }
        } finally {
            ctx.end()
        }
    }

    private fun menuScaleForWindow(base: Float, requested: Float, width: Int, height: Int): Float {
        if (requested <= 1f) return base * requested
        val fits = min(width / 420f, height / 240f).coerceAtLeast(base)
        return min(base * requested, fits)
    }

    private var menuTextScale = 1f
    private var loadedMenuFont: String? = null
    private var menuFontCheckedAt = 0L
    private var menuFontAvailable = false

    private fun applyMenuFont(id: String) {
        if (id.isEmpty()) {
            ui.mt.useMenuFont = false
            return
        }
        val now = System.nanoTime()
        if (id != loadedMenuFont || now - menuFontCheckedAt >= 2_000_000_000L) {
            val (font, key) = LyricFonts.load(id)
            DangoClient.gfx.text.font(CjkStyle.MENU, font, key)
            DangoClient.gfx.text.font(CjkStyle.MENU_BOLD, font, key)
            menuFontAvailable = font != null
            loadedMenuFont = id
            menuFontCheckedAt = now
        }
        ui.mt.useMenuFont = menuFontAvailable
    }

    private fun drawWindow(alpha: Float) {
        barLayoutScale = 1f
        val win = LayoutBounds(26f, 20f + (1f - alpha) * 12f, ui.lw - 52f, ui.lh - 40f)
        if (appleLyricsOpen) {
            val netease = lyricsSource == MusicSource.NETEASE
            val viewport = LayoutBounds(0f, 0f, ui.lw, ui.lh)
            val layout = AppleLyricsLayout(viewport, showAppleLyrics)
            barRect = LayoutBounds()
            val playback = PlaybackView.snapshot()
            appleLyricsFrame = layout
            appleLyricsTrack = playback.track
            ui.mt.useNotoSansSc = true
            try {
                val toggle = {
                    showAppleLyrics = !showAppleLyrics
                    if (showAppleLyrics) lyricsPage.resumeAppleLyrics()
                }
                if (netease) {
                    val controls = lyricsPage.drawNeteaseApple(layout, playback, ::closeLyrics, toggle)
                    val fade = lyricsPage.commentFade
                    ui.shifted(0f, 0f, fade) {
                        bar.drawAppleLyrics(controls, playback, appleLyricsMenu::toggle, netease = true, interactive = fade >= 0.99f)
                    }
                } else {
                    lyricsPage.drawApple(layout, playback, ::closeLyrics, toggle)
                    bar.drawAppleLyrics(layout, playback, appleLyricsMenu::toggle)
                }
            } finally {
                ui.mt.useNotoSansSc = false
            }
            return
        }
        if (lyricsOpen && !canOpenLyrics) closeLyrics()
        val pad = 10f
        val preferredSideWidth = if (usesAppleLayout) 224f * 0.9f else 224f
        val sideW = if (menuTextScale == 1f) preferredSideWidth
            else minOf(preferredSideWidth, win.width * 0.32f).coerceAtLeast(0f)
        if (lyricsOpen) {
            val barH = if (appleNavigation) 45f else 88f
            barRect = LayoutBounds(win.x, win.bottom - barH, win.width, barH)
            val content = LayoutBounds(win.x + pad, win.y + pad, win.width - 2f * pad, barRect.y - win.y - pad)
            lyricsPage.draw(content, win, ::closeLyrics)
            return
        }
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val sampleContent = usesAppleLayout && (ScreenState.page == Page.GROUP || ScreenState.page == Page.PLAYLIST)
        if (usesAppleLayout) {
            val song = PlaybackView.snapshot().track?.takeIf { it.source == MusicSources.selected }
            coverBackdrop.prepareMenuFrame(song)
            coverBackdrop.drawMenuPanel(win, Theme.GLASS_WINDOW.radius, Color(0.03f, 0.035f, 0.045f, 0.34f))
            // Room cards and playlist highlights sample the opaque cover backdrop.
            if (sampleContent && !appleSidebarUsesDarkGlass) ui.ctx.recaptureBackdrop()
        } else ui.ctx.drawGlass(win, Theme.GLASS_WINDOW)
        val mainX = side.right + pad + 8f
        appleQueueArea = if (usesAppleLayout) {
            appleQueue.layoutIn(LayoutBounds(mainX, win.y + 6f, win.right - 6f - mainX, win.height - 12f))
        } else LayoutBounds()
        val mainRight = if (appleQueueArea.width > 0f) appleQueueArea.x - 12f else win.right - pad - 8f
        val mainW = mainRight - mainX
        // 播放栏浮在内容上面：左右和底部留出间距；内容延伸到播放栏底边为止，滚动时从播放栏后面经过，
        // 但不会在播放栏下面那道缝里露出来
        val barBottom = win.bottom - pad - 8f
        val barRect = layoutMenuBar(mainX, mainW, barBottom, win.height)
        val content = LayoutBounds(mainX, win.y + pad + 14f, mainW, barRect.bottom - (win.y + pad + 14f))
        if (!usesAppleLayout) {
            appleQueue.close()
            appleBarMenu.reset()
        }

        ui.compactAppleText = usesAppleLayout
        try {
            sidebar.draw(side)
            // Dark glass uses the original game sample before the room or playlist captures the menu.
            if (sampleContent && appleSidebarUsesDarkGlass) ui.ctx.recaptureBackdrop()
            ui.occluder = barRect
            ui.bottomInset = content.bottom - barRect.y + 10f
            drawContent(content)
        } finally {
            ui.occluder = null
            ui.compactAppleText = false
        }
        this.barRect = barRect
    }

    /** 这一帧播放栏的位置（[drawWindow] 里算好，内容画完之后再画播放栏） */
    private var barRect = LayoutBounds()
    private var barLayoutScale = 1f

    private fun layoutMenuBar(left: Float, width: Float, bottom: Float, height: Float): LayoutBounds {
        if (width <= 14f) return LayoutBounds()
        val apple = usesAppleLayout
        val nominalHeight = if (apple) 36f else 88f
        val usableWidth = if (apple) width else width - 6f
        val minimumWidth = when {
            neteaseUsesAppleLayout -> 562f
            apple -> 444f
            else -> 490f
        }
        val playerSize = module.menuPlayerBarScale(MusicSources.selected)
        if (playerSize != 1f || menuTextScale != 1f) {
            barLayoutScale = minOf(playerSize / menuTextScale, usableWidth / minimumWidth,
                (height - 100f).coerceAtLeast(nominalHeight * 0.5f) / nominalHeight).coerceAtLeast(0.25f)
        }
        val k = barLayoutScale
        return if (apple) PlayerBar.appleBarBounds(left / k, width / k, bottom / k, neteaseUsesAppleLayout).scaleCoordinates(k)
        else LayoutBounds(left + 2f, bottom - nominalHeight * k, usableWidth, nominalHeight * k)
    }

    private fun barCoordinate(coordinate: Int) = (coordinate / barLayoutScale).toInt()
    private class LibraryPull(val x: Int, val y: Int, val click: (() -> Unit)?) {
        var moved = false
        var distance = 0f
    }

    private var libraryPull: LibraryPull? = null
    private var libraryPullOffset = 0f
    private val libraryPullThreshold = 48f
    private fun canPullLibrary() = UiStyle.continuous && MusicSources.selected == MusicSource.APPLE &&
        ScreenState.page == Page.HOME && !lyricsOpen && !login.visible && grid.atTop

    private fun drawBar() {
        if (appleLyricsOpen) {
            if (lyricsSource == MusicSource.APPLE) appleLyricsFrame?.let { appleLyricsMenu.draw(it, appleLyricsTrack) }
            return
        }
        // 播放栏画在最上面，点击也要先轮到它（点到的是第一个命中的区域）
        if (usesAppleLayout && !lyricsOpen) {
            ui.compactAppleText = true
            try {
                appleQueue.draw(appleQueueArea, neteaseUsesAppleLayout)
            } finally {
                ui.compactAppleText = false
            }
        }
        if (barRect.width <= 0f) return
        ui.scaledLayer(barLayoutScale) {
            drawBarContents(barRect.scaleCoordinates(1f / barLayoutScale))
        }
    }

    private fun drawBarContents(barRect: LayoutBounds) {
        val before = ui.hits.size
        bar.draw(barRect, lyricsOpen)
        val notice = Library.notice
        val opacity = notice.opacity()
        if (opacity > 0f && !(UiStyle.continuous && notice.text == AppleMusicSource.libraryWarning)) {
            val font = Theme.FONT_SEMIBOLD
            val text = ui.trim(I18n.message(notice.text), barRect.width, 10f, font)
            val playlistResult = Library.playlistNotice
            if (playlistResult != null) {
                val iconSpace = if (playlistResult) 0f else 18f
                val width = (ui.width(text, 10f, font) + 28f + iconSpace).coerceAtMost(barRect.width)
                val toast = LayoutBounds(barRect.x + (barRect.width - width) / 2f, barRect.y - 39f, width, 28f)
                ui.card(toast, me.sorihiko.dangomusicgtnh.utils.Color(0.13f, 0.13f, 0.17f, 0.96f * opacity), 14f)
                if (!playlistResult) {
                    val icon = LayoutBounds(toast.x + 12f, toast.y + 9f, 10f, 10f)
                    ui.card(icon, RED.copy(a = opacity), 5f)
                    PlayerIcons.draw(ui.ctx, AppleLyricsIcons.close, icon.x + 5f, icon.y + 5f, 6f,
                        Theme.TEXT_PRIMARY.copy(a = opacity))
                }
                ui.text(text, toast.x + 14f + iconSpace, toast.y + 9f, 10f, Theme.TEXT_PRIMARY.copy(a = opacity), font)
            } else ui.text(text, barRect.x, barRect.y - 20f, 10f, RED.copy(a = opacity), font)
        }
        val barHits = ui.hits.subList(before, ui.hits.size).toList()
        repeat(barHits.size) { ui.hits.removeAt(ui.hits.size - 1) }
        // 播放栏的空白处也要挡住点击，不然会点到底下那首歌
        ui.hits.addAll(0, barHits + listOf(barRect to {}))
        if (usesAppleLayout && !lyricsOpen) {
            if (appleBarMenu.capturesInput) {
                ui.mt.flush(ui.scale)
                ui.ctx.recaptureBackdrop()
            }
            val viewport = LayoutBounds(0f, 0f, ui.lw, ui.lh)
            appleBarMenu.playerBarTop = barRect.y
            if (neteaseUsesAppleLayout) appleBarMenu.drawNetease(viewport, bar.appleMenuAnchor, bar::openQualityFromMenu)
            else {
                val playback = PlaybackView.snapshot()
                appleBarMenu.draw(AppleLyricsLayout(viewport),
                    playback.track?.takeUnless { playback.remote }, bar.appleMenuAnchor)
            }
        }
    }

    private fun drawContent(area: LayoutBounds) {
        val body = area.copy(height = area.height - 4f)
        // 滚轮区域：哪一页画了，哪一页才有
        grid.area = LayoutBounds()
        discover.hide()
        tracks.area = LayoutBounds()
        settings.area = LayoutBounds()
        artistPage.area = LayoutBounds()
        groupPage.area = LayoutBounds()

        val s = ScreenState
        // 设置页的标签切换有自己的过渡动画，不算换页（否则整页会从透明重新淡入，看起来闪一下）
        // 列表 / 封面墙切换有自己的过渡动画，不算换页（否则整页会从透明重新淡入，看起来闪一下）
        val key = "${s.page}|${s.opened?.id}|${s.lastQuery}|${s.artist?.source}|${s.artist?.id}|${s.artist?.name}"
        if (key != pageKey) {
            pageKey = key
            ui.pageAt = System.currentTimeMillis()
            tracks.onPageChanged()
        }

        when (s.page) {
            Page.HOME -> {
                val rec = Library.recommend
                val items = if (rec.isNullOrEmpty()) Library.playlists else rec
                val label = if (rec.isNullOrEmpty()) I18n.text("Your playlists") else I18n.text("Recommended playlists")
                val apple = MusicSources.selected == MusicSource.APPLE
                val pull = apple && UiStyle.continuous
                if (pull) {
                    val distance = libraryPull?.distance
                    libraryPullOffset = distance ?: ui.approach(libraryPullOffset, 0f, 85f)
                    if (libraryPullOffset < 0.5f) libraryPullOffset = 0f
                }
                if (MusicSources.selected == MusicSource.NETEASE) grid.drawNeteaseHome(body, items, Library.recommendLoading)
                else if (usesAppleLayout) grid.drawAppleHome(body, items, Library.recommendLoading,
                    if (apple) AppleMusicSource.homeWarning else "", if (pull) libraryPullOffset else 0f,
                    neteaseHeading = label.takeUnless { apple })
                else grid.draw(body, I18n.text("Welcome to Dango Music!"), "$label · ${items.size}",
                    items, Library.recommendLoading && items.isEmpty())
                if (pull && libraryPullOffset > 18f) {
                    val label = I18n.text(if (Library.recommendLoading) "Loading..."
                        else if (libraryPullOffset >= libraryPullThreshold) "Release to refresh" else "Pull to refresh")
                    ui.text(label, body.x + (body.width - ui.width(label, 11f)) / 2f,
                        grid.area.y + (libraryPullOffset - 11f) / 2f, 11f, Theme.TEXT_SECONDARY)
                } else if (apple && !pull && body.width > 280f) {
                    val refresh = LayoutBounds(body.right - 110f, body.y, 106f, 28f)
                    ui.card(refresh, Theme.CARD, 9f)
                    ui.text(I18n.text("Refresh library"), refresh.x + 10f, refresh.y + 9f, 11f, Theme.TEXT_PRIMARY)
                    ui.hits += refresh to { Library.refreshAll() }
                }
            }
            Page.CHARTS -> {
                val c = Library.charts ?: emptyList()
                grid.draw(body, I18n.text("Charts"), I18n.format("%d charts", c.size), c, Library.chartsLoading)
            }
            Page.APPLE_BROWSE -> {
                val section = s.appleSection
                val items = Library.applePage(section).orEmpty()
                val loading = Library.applePageLoading(section)
                val editorial = Library.appleEditorial(section)
                if (editorial != null) discover.drawEditorial(body, section.label, editorial)
                else if (section == AppleMusicSource.Section.DISCOVER) discover.draw(body, items, loading)
                else grid.draw(body, I18n.text(section.label), if (items.isEmpty()) I18n.text("No items") else "",
                    items, loading, categoryTiles = section == AppleMusicSource.Section.GENRES,
                    wideCards = section == AppleMusicSource.Section.RADIO)
            }
            Page.SEARCH -> if (usesAppleLayout) drawAppleSearch(body) else tracks.draw(body)
            Page.SETTINGS -> settings.draw(body)
            Page.ARTIST -> artistPage.draw(body)
            Page.GROUP -> groupPage.draw(body)
            else -> tracks.draw(body)
        }
    }

    private fun drawAppleSearch(body: LayoutBounds) {
        if (neteaseUsesAppleLayout) {
            sidebar.drawAppleSearch(LayoutBounds(body.x, body.y, body.width, 36f))
            if (ScreenState.lastQuery.isNotBlank()) tracks.draw(body.copy(y = body.y + 50f, height = body.height - 50f))
            return
        }
        val scopeWidth = minOf(230f, body.width * 0.44f)
        val search = LayoutBounds(body.x, body.y, body.width - scopeWidth - 12f, 36f)
        sidebar.drawAppleSearch(search)
        val scope = LayoutBounds(search.right + 12f, body.y, scopeWidth, search.height)
        ui.card(scope, Theme.CARD, scope.height / 2f)
        for (library in listOf(false, true)) {
            val tab = LayoutBounds(scope.x + (if (library) scope.width / 2f else 0f) + 3f,
                scope.y + 3f, scope.width / 2f - 6f, scope.height - 6f)
            val selected = ScreenState.appleLibrarySearch == library
            if (selected) ui.card(tab, Theme.CARD_HOVER, tab.height / 2f)
            val label = ui.trim(I18n.text(if (library) "Your Library" else "Apple Music"), tab.width - 8f, 12f)
            ui.text(label, tab.x + (tab.width - ui.width(label, 12f)) / 2f,
                tab.y + 9f, 12f, if (selected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.hits += tab to {
                if (ScreenState.appleLibrarySearch != library) {
                    ScreenState.appleLibrarySearch = library
                    if (sidebar.searchText.isNotBlank()) doSearch(live = true)
                    else loadAppleSearchCategories()
                }
            }
        }
        var y = search.bottom + 20f
        if (ScreenState.lastQuery.isNotBlank()) {
            tracks.draw(LayoutBounds(body.x, y, body.width, (body.bottom - y).coerceAtLeast(0f)))
            return
        }
        if (appleSearchHistory.isNotEmpty()) {
            val title = I18n.text("Recent searches")
            ui.text(title, body.x, y, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val clear = I18n.text("Clear search history")
            val clearWidth = ui.width(clear, 11f) + 16f
            val button = LayoutBounds(body.right - clearWidth, y, clearWidth, 22f)
            ui.text(clear, button.x + 8f, button.y + 4f, 11f, RED)
            ui.hits += button to { appleSearchHistory.clear() }
            y += 26f
            val count = minOf(4, appleSearchHistory.size, (body.width / 100f).toInt().coerceAtLeast(1))
            val cardWidth = (body.width - (count - 1) * 8f) / count
            for ((index, query) in appleSearchHistory.take(count).withIndex()) {
                val card = LayoutBounds(body.x + index * (cardWidth + 8f), y, cardWidth, 32f)
                ui.pill(card, if (ui.hover(card)) Theme.CARD_HOVER else Theme.CARD, ui.trim(query, card.width - 16f, 12f)) {
                    sidebar.searchText = query
                    doSearch()
                }
            }
            y += 51f
        }
        val genres = AppleMusicSource.Section.GENRES
        val library = ScreenState.appleLibrarySearch
        grid.draw(LayoutBounds(body.x, y, body.width, (body.bottom - y).coerceAtLeast(0f)),
            I18n.text("Browse categories"), "",
            if (library) Library.applePage(genres).orEmpty() else Library.catalogGenres.orEmpty(),
            if (library) Library.applePageLoading(genres) else Library.catalogGenresLoading,
            categoryTiles = true)
    }

    // ---------------------------------------------------------------- 鼠标

    fun mouseClicked(mouseButton: Int) {
        if (closingAt != 0L || closed) return
        val mx = ui.mx
        val my = ui.my
        if (login.visible) {
            login.mouseClicked(mx, my, mouseButton)
            return
        }
        val barX = barCoordinate(mx)
        val barY = barCoordinate(my)
        if (mouseButton == 0) bar.commitVolumeOutside(barX, barY)
        bar.closeMenuOutside(barX, barY)
        if (mouseButton == 0 && bar.menuContains(barX, barY)) {
            ui.hits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
            return
        }
        if (mouseButton == 0 && !lyricsOpen && (appleBarMenu.capturesInput || appleQueue.contains(mx, my))) {
            val hit = ui.hits.firstOrNull { it.first.contains(mx, my) }
            if (hit != null && (appleBarMenu.capturesInput || !appleQueue.queueDrag.down(mx, my, hit))) hit.second()
            return
        }
        // 快捷键的“重置”按钮可能滚到了播放栏底下：点在播放栏上时先交给播放栏（以前会把音质按钮的点击吃掉）
        if (!lyricsOpen && mouseButton == 0 && !barRect.expand(0f, 8f).contains(mx, my) && settings.resetKeyAt(mx, my)) return
        if (!lyricsOpen && settings.bindMouse(mouseButton)) return
        val mouseKey = Keys.mouse(mouseButton)
        if (module.matchesOpenKey(mouseKey)) {
            if (!openKeyHeld) {
                openKeyHeld = true
                module.consumeOpenKey()
                close()
            }
            return
        }
        if (module.handleHotkey(mouseKey)) return
        // 鼠标侧键 / 右键：返回上一页
        if (mouseButton == 1 || mouseButton == 3) {
            goBack()
            return
        }
        if (mouseButton != 0) return
        if (appleLyricsOpen && appleLyricsMenu.capturesInput) {
            ui.hits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
            return
        }
        if (bar.mouseDown(barX, barY)) return
        if (!lyricsOpen && tracks.scrollbarDown(mx, platform.mouseY / ui.scale)) return
        if (!lyricsOpen && !barRect.contains(mx, my) && tracks.coverDown(mx, my, platform.mouseX / ui.scale)) return
        val hit = ui.hits.firstOrNull { it.first.contains(mx, my) }
        if (!lyricsOpen && ScreenState.page == Page.GROUP) groupPage.clearFocus()
        if (lyricsOpen) {
            if (hit == null) lyricsPage.dismissMenu() else hit.second()
            return
        }
        if (hit != null && !barRect.contains(mx, my) && !sidebar.menuOpen &&
            tracks.queueDrag.down(mx, my, hit)) return
        settings.onClickElsewhere(hit)
        if (hit == null || hit.first !== sidebar.searchRect) sidebar.searchFocused = false
        if (hit == null || !sidebar.isMenuHit(hit.first)) sidebar.menuOpen = false
        if (hit == null || hit.first !== tracks.filterRect) tracks.filterFocused = false
        if (canPullLibrary() && grid.area.contains(mx, my) && !barRect.contains(mx, my)) {
            libraryPull = LibraryPull(mx, my, hit?.second)
        } else hit?.second?.invoke()
    }

    /** 按住鼠标拖动时调用 */
    fun mouseDragged(mouseButton: Int) {
        if (closingAt != 0L || closed) return
        if (mouseButton == 0 && lyricsOpen && lyricsPage.dragFontSlider((platform.mouseY / ui.scale).toInt())) return
        if (mouseButton == 0 && libraryPull != null) {
            updateLibraryPull()
            return
        }
        if (mouseButton == 0 && (tracks.coverPressed || appleQueue.queueDrag.pressed || tracks.queueDrag.pressed)) return
        if (mouseButton == 0 && tracks.sbDragging) {
            tracks.scrollbarDrag(platform.mouseY / ui.scale)
            return
        }
        if (!login.visible) {
            val scale = ui.scale * barLayoutScale
            bar.mouseDrag((platform.mouseX / scale).toInt(), (platform.mouseY / scale).toInt())
        }
    }

    private fun updateLibraryPull() {
        val pull = libraryPull ?: return
        val dx = platform.mouseX / ui.scale - pull.x
        val dy = platform.mouseY / ui.scale - pull.y
        if (kotlin.math.abs(dx) > 5f || kotlin.math.abs(dy) > 5f) pull.moved = true
        pull.distance = if (canPullLibrary() && dy > kotlin.math.abs(dx)) (dy * 0.65f).coerceAtMost(68f) else 0f
    }

    fun mouseReleased(mouseButton: Int) {
        if (module.openKey.value < 0) openKeyHeld = false
        if (mouseButton == 0) {
            val x = (platform.mouseX / ui.scale).toInt()
            val y = (platform.mouseY / ui.scale).toInt()
            if (tracks.sbDragging) {
                tracks.scrollbarDrag(platform.mouseY / ui.scale)
                tracks.scrollbarUp()
                return
            }
            if (appleQueue.queueDrag.up(x, y) || tracks.queueDrag.up(x, y)) return
            lyricsPage.stopFontSliderDrag()
        }
        if (mouseButton == 0 && tracks.coverPressed) tracks.coverUp(platform.mouseX / ui.scale)
        bar.mouseUp()
        settings.stopDrag()
        if (mouseButton == 0) {
            updateLibraryPull()
            val pull = libraryPull ?: return
            libraryPull = null
            if (closingAt != 0L || closed || !canPullLibrary()) return
            if (pull.distance >= libraryPullThreshold && !Library.recommendLoading && !Library.playlistsLoading) {
                Library.refreshAll()
            } else if (!pull.moved) pull.click?.invoke()
        }
    }

    /** 滚轮：[wheel] 正数向上；(x, y) 是滚动时鼠标的窗口像素位置 */
    fun scroll(wheel: Int, x: Int, y: Int) {
        if (closingAt != 0L || closed) return
        if (wheel == 0 || login.visible) return
        val lx = (x / ui.scale).toInt()
        val ly = (y / ui.scale).toInt()
        if (appleBarMenu.scrollPlaylists(wheel)) return
        if (bar.menuContains(barCoordinate(lx), barCoordinate(ly)) || appleBarMenu.capturesInput || appleQueue.scroll(wheel, lx, ly)) return
        if (lyricsOpen) {
            if (appleLyricsMenu.capturesInput) return
            lyricsPage.scroll(wheel, lx, ly)
            return
        }
        // 按住 Ctrl 滚得快十倍
        val step = (if (wheel > 0) -3 else 3) * (if (platform.ctrlDown) 10 else 1)
        when {
            settings.area.contains(lx, ly) -> settings.scroll(wheel)
            sidebar.listArea.contains(lx, ly) -> sidebar.scrollBy(step)
            discover.area.contains(lx, ly) -> discover.scroll(wheel)
            grid.area.contains(lx, ly) -> grid.scroll(wheel)
            artistPage.area.contains(lx, ly) -> artistPage.scroll(wheel)
            groupPage.area.contains(lx, ly) -> groupPage.scrollBy(wheel)
            tracks.area.contains(lx, ly) -> tracks.scroll(wheel, step)
        }
    }

    // ---------------------------------------------------------------- 键盘

    /** 按键：[keyCode] 是 [Keys] 键码 */
    fun keyTyped(typedChar: Char, keyCode: Int, committedSettingsText: Boolean = false) {
        if (closingAt != 0L || closed) {
            if (module.matchesOpenKey(keyCode)) module.consumeOpenKey()
            return
        }
        if (login.visible) {
            login.keyTyped(keyCode)
            return
        }
        val textChar = if (committedSettingsText && keyCode != Keys.NONE) '\u0000' else typedChar
        if (!lyricsOpen && bar.volumeKeyTyped(textChar, keyCode)) return
        if (keyCode == Keys.ESCAPE && (appleQueue.queueDrag.pressed || tracks.queueDrag.pressed)) {
            appleQueue.queueDrag.cancel()
            tracks.queueDrag.cancel()
            return
        }
        if (keyCode == Keys.ESCAPE && (bar.dismissMenu() || appleBarMenu.dismiss() || appleQueue.close())) return
        // 正在输入 / 录按键的地方先拿走按键
        if (!lyricsOpen) {
            // With SDL text input active, text arrives in KEY_NONE events (committed text).
            // Keep physical key codes for editing commands and shortcut recording, but don't insert their
            // characters again: every text field (settings, search, filter, group page) would get each letter twice.
            if (ScreenState.page == Page.GROUP && groupPage.keyTyped(textChar, keyCode)) return
            if (settings.keyTyped(textChar, keyCode)) return
            if (sidebar.keyTyped(textChar, keyCode)) return
            if (tracks.filterKeyTyped(textChar, keyCode)) return
        }
        if (module.matchesOpenKey(keyCode)) {
            if (!openKeyHeld) {
                openKeyHeld = true
                module.consumeOpenKey()
                close()
            }
            return
        }
        // 自己设的快捷键在播放器里也能用
        if (keyCode != Keys.ESCAPE && module.handleHotkey(keyCode)) return
        if (!lyricsOpen && tracks.shortcutBefore(keyCode)) return
        when {
            // 其它页面 Ctrl+F 是全网搜索
            !lyricsOpen && platform.ctrlDown && keyCode == Keys.F -> {
                if (usesAppleLayout) openAppleSearch() else sidebar.searchFocused = true
            }
            keyCode == Keys.SPACE -> PlaybackView.togglePause()
            keyCode == Keys.BACK -> goBack()
            !lyricsOpen && tracks.coverflowKey(keyCode) -> {}
            keyCode == Keys.RIGHT -> PlaybackView.seek(PlaybackView.snapshot().positionMs + 5000)
            keyCode == Keys.LEFT -> PlaybackView.seek(PlaybackView.snapshot().positionMs - 5000)
            keyCode == Keys.ESCAPE -> if (lyricsOpen) goBack() else close()
        }
    }

    /** 松开打开键后，下一次按下才是关闭；长按重复事件不算新按下。 */
    fun keyReleased(keyCode: Int) {
        if (keyCode == module.openKey.value) openKeyHeld = false
    }
}
