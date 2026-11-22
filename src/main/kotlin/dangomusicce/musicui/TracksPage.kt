package dangomusicce.musicui

import dangomusicce.client.ClientMusic
import dangomusicce.i18n.I18n

import dangomusicce.DangoClient
import dangomusicce.music.AppleMusicSource
import dangomusicce.music.CoverCache
import dangomusicce.music.CloudDrives
import dangomusicce.music.MusicPlayer
import dangomusicce.music.MusicScreen
import dangomusicce.music.MusicSource
import dangomusicce.music.MusicSources
import dangomusicce.music.NeteaseApi
import dangomusicce.music.NeteasePlaylist
import dangomusicce.music.NeteaseTrack
import dangomusicce.platform.Keys
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.utils.Color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 歌曲列表页（歌单详情、搜索结果、播放队列）：
 *   标题区：返回、歌单封面、播放全部、筛选框、定位、列表 / 封面墙切换
 *   内容：列表（序号 / 小封面 / 歌名 / 歌手 / 时长 + 右侧滚动条），或封面墙（Coverflow）
 */
internal class TracksPage(private val screen: MusicScreen) {
    private val ui = screen.ui
    val queueDrag = QueueDrag()
    private val listSelectableSources = setOf(MusicSource.NETEASE, MusicSource.APPLE)

    /** 本帧画了歌曲列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()

    // 歌单内筛选
    var filterText = ""
    var filterFocused = false
    var filterRect = LayoutBounds()
        private set
    private var filterCacheSrc: List<NeteaseTrack>? = null
    private var filterCacheKey = ""
    private var filterCache: List<Pair<Int, NeteaseTrack>> = emptyList()

    // 列表滚动：目标（行）和平滑后的当前位置；一屏放得下几行
    private var scroll = 0f
    private var listPos = 0f
    var listRows = 10
        private set

    // 右侧滚动条
    private var sbTrack = LayoutBounds()
    private var sbTotal = 0
    var sbDragging = false
        private set
    private var sbGrab = 0f

    // "定位"后那一行闪一下
    private var flashId = ""
    private var flashAt = 0L

    // 封面墙：目标下标和平滑后的当前位置
    private val coverMotion = CoverflowMotion()
    private var cfTarget: Int
        get() = coverMotion.target
        set(value) { coverMotion.moveTo(value) }
    private val cfPos get() = coverMotion.position
    private var coverCount = 0
    private var coverSpacing = 1f
    private var observedTrack: String? = null
    private var observedCoverTracks: List<NeteaseTrack>? = null
    private var observedCoverFromQueue = false
    private var lastCoverPosition = 0f
    private var coverBlur = 0f
    private var coverHits: List<Pair<LayoutBounds, Int>> = emptyList()
    private var pressedCover: Int? = null
    val coverPressed get() = coverMotion.pressed

    private val tracksPage: Boolean
        get() = ScreenState.page.let { it == Page.PLAYLIST || it == Page.SEARCH || it == Page.QUEUE }

    private val applePlaylist: Boolean
        get() = screen.appleNavigation && ScreenState.page == Page.PLAYLIST
    private val applePlaylistLayout: Boolean
        get() = screen.usesAppleLayout && ScreenState.page == Page.PLAYLIST

    // ---------------------------------------------------------------- 每帧 / 切页

    fun tick(pointerX: Float) {
        if (!tracksPage || !ScreenState.coverView) return
        val listing = currentTracks()
        if (coverListChanged(listing)) return
        if (coverMotion.pressed) coverMotion.drag(pointerX, coverCount, System.nanoTime())
        if (MusicPlayer.current?.key != observedTrack || !coverMotion.step(ui.frameDt / 1000f, coverCount)) return

        val all = listing.tracks ?: return
        val (slot, track) = shown(all).getOrNull(cfTarget) ?: return
        if (MusicPlayer.current?.key != track.key || (listing.fromQueue && MusicPlayer.index != slot) ||
            MusicPlayer.state == MusicPlayer.State.PAUSED || MusicPlayer.state == MusicPlayer.State.STOPPED ||
            MusicPlayer.state == MusicPlayer.State.ERROR) playFrom(all, slot, listing.fromQueue)
    }

    fun beginFrame() {
        queueDrag.beginFrame()
        if (!sbDragging) sbTotal = 0   // 没画歌单列表的页面上不响应滚动条
    }

    /** 换了页面：列表直接从当前滚动位置开始画，不从别处滑过来 */
    fun onPageChanged() {
        queueDrag.cancel()
        scrollbarUp()
        listPos = scroll
        selSnap = true
        selectedAppleTrack = null
        resetCoverflow()
    }

    // 正在播放那一行的玻璃高亮：换歌时从原来那行滑到新的一行（单位是"第几行"，滚动时跟着列表走）
    private val selPos = Spring(320f, 26f)
    /** 点歌时高亮轻轻缩一下再弹回来 */
    private val selPress = Spring(420f, 13f)
    /** 换了页面：高亮直接出现在正确的行，不从上一页的位置滑过来 */
    private var selSnap = true
    private var selectedAppleTrack: Pair<Int, String>? = null

    fun resetScroll() {
        scroll = 0f
    }

    fun resetCoverflow() {
        coverMotion.reset()
        observedTrack = null
        observedCoverTracks = null
        observedCoverFromQueue = false
        lastCoverPosition = 0f
        coverBlur = 0f
        pressedCover = null
        coverHits = emptyList()
    }

    fun scroll(wheel: Int, rows: Int) {
        if (ScreenState.coverView) cfTarget += if (wheel > 0) -1 else 1 else scroll += rows
    }

    // ---------------------------------------------------------------- 数据

    private data class TrackListing(val title: String, val tracks: List<NeteaseTrack>?, val fromQueue: Boolean = false)

    private fun currentTracks(): TrackListing = when (ScreenState.page) {
        Page.SEARCH -> {
            val catalog = Library.searchResults
            val live = if (ScreenState.coverView) catalog?.let(MusicPlayer::queueFromCatalog) else null
            TrackListing("\"" + ScreenState.lastQuery + "\"", live ?: catalog, fromQueue = live != null)
        }
        Page.QUEUE -> TrackListing(I18n.text("Now Playing"), MusicPlayer.queue, fromQueue = true)
        Page.PLAYLIST -> {
            val p = ScreenState.opened
            if (p == null) TrackListing("", null)
            else {
                val catalog = Library.tracks(p.id)
                val live = when {
                    showingHeartQueue() || showingFmQueue() -> MusicPlayer.queue
                    ScreenState.coverView -> MusicPlayer.queueForPlaylist(MusicSources.selected, p.id, catalog)
                    else -> null
                }
                TrackListing(p.name, live ?: catalog, fromQueue = live != null)
            }
        }
        else -> TrackListing("", null)
    }

    private fun coverListChanged(listing: TrackListing): Boolean =
        listing.fromQueue != observedCoverFromQueue || listing.tracks !== observedCoverTracks

    private fun norm(s: String) = s.lowercase().replace(" ", "")

    /** 当前页显示的歌（筛选后），带原来的序号；结果缓存起来，两千多首也不卡 */
    private fun shown(all: List<NeteaseTrack>): List<Pair<Int, NeteaseTrack>> {
        val key = if (showingHeartQueue()) "" else norm(filterText)
        if (all === filterCacheSrc && key == filterCacheKey) return filterCache
        filterCacheSrc = all
        filterCacheKey = key
        filterCache = if (key.isEmpty()) {
            all.mapIndexed { i, t -> i to t }
        } else {
            all.mapIndexedNotNull { i, t ->
                if (norm(t.name).contains(key) || t.artists.any { norm(it).contains(key) }) i to t else null
            }
        }
        return filterCache
    }

    fun clearFilter() {
        filterText = ""
        filterFocused = false
    }

    private fun showingHeartQueue(): Boolean = ScreenState.coverView && ScreenState.page == Page.PLAYLIST &&
        MusicSources.selected == MusicSource.NETEASE && MusicPlayer.heartModeActive &&
        ScreenState.opened?.let(Library::canPlayHeartMode) == true

    private fun showingFmQueue(): Boolean = radio() != null && MusicPlayer.radio != null

    /** Play within a displayed queue, or start a queue from the complete library list. */
    private fun playFrom(
        all: List<NeteaseTrack>,
        index: Int,
        fromQueue: Boolean = ScreenState.page == Page.QUEUE || showingFmQueue(),
    ) {
        if (fromQueue) ClientMusic.control {
            if (MusicPlayer.queue !== all) return@control
            if (dangomusicce.music.AppleWebPlayer.stationActive) {
                when (index - MusicPlayer.index) {
                    -1 -> MusicPlayer.prev()
                    1 -> MusicPlayer.next()
                }
            } else MusicPlayer.playQueueSlot(all, index)
        }
        else {
            val continuation = radio()
            val playlistId = openedPlaylistId()
            ClientMusic.control { MusicPlayer.playQueue(all, index, continuation, playlistId) }
        }
    }

    private fun openedPlaylistId(): Long? = if (ScreenState.page == Page.PLAYLIST) ScreenState.opened?.id else null

    /** 私人 FM 的页面：放完接着要新的歌 */
    private fun radio(): (() -> List<NeteaseTrack>)? =
        if (ScreenState.page == Page.PLAYLIST && ScreenState.opened?.id == NeteaseApi.FM_ID && MusicSources.selected == MusicSource.NETEASE)
            NeteaseApi::personalFm else null

    /** 跳到正在播放的那首：列表滚到它居中，封面墙转到它，并闪一下 */
    private fun locateCurrent() {
        val listing = currentTracks()
        val all = listing.tracks ?: return
        val cur = MusicPlayer.current ?: return
        var list = shown(all)
        var idx = currentRow(list, cur.key, listing.fromQueue)
        if (idx < 0 && filterText.isNotEmpty()) {
            // 被筛选掉了：清掉筛选再找
            clearFilter()
            list = shown(all)
            idx = currentRow(list, cur.key, listing.fromQueue)
        }
        if (idx < 0) return
        scroll = (idx - listRows / 2).coerceAtLeast(0).toFloat()
        coverMotion.reset(idx)
        flashId = cur.key
        flashAt = System.currentTimeMillis()
    }

    private fun currentInList(): Boolean {
        val cur = MusicPlayer.current ?: return false
        val listing = currentTracks()
        val all = listing.tracks ?: return false
        return if (listing.fromQueue) all.getOrNull(MusicPlayer.index)?.key == cur.key else all.any { it.key == cur.key }
    }

    /** 封面墙转到正在播放的那首（没有就第一首） */
    fun focusCurrentInCoverflow() {
        val listing = currentTracks()
        val all = listing.tracks ?: return
        val cur = MusicPlayer.current
        val idx = currentRow(shown(all), cur?.key, listing.fromQueue)
        coverMotion.reset(if (idx >= 0) idx else 0)
        coverHits = emptyList()
        pressedCover = null
        observedTrack = cur?.key
        observedCoverTracks = all
        observedCoverFromQueue = listing.fromQueue
    }

    private fun currentRow(rows: List<Pair<Int, NeteaseTrack>>, key: String?, fromQueue: Boolean): Int =
        if (key == null) -1 else if (fromQueue) {
            val slot = MusicPlayer.index
            rows.indexOfFirst { it.first == slot && it.second.key == key }
        } else rows.indexOfFirst { it.second.key == key }

    // ---------------------------------------------------------------- 绘制

    /** 只画歌曲列表（歌手页的热门歌曲用，标题自己画） */
    fun drawList(area: LayoutBounds, all: List<NeteaseTrack>) {
        this.area = area
        if (embeddedSrc !== all) {
            embeddedSrc = all
            embedded = all.mapIndexed { i, t -> i to t }
        }
        drawTrackList(area, all, embedded)
    }

    private var embeddedSrc: List<NeteaseTrack>? = null
    private var embedded: List<Pair<Int, NeteaseTrack>> = emptyList()

    fun draw(page: LayoutBounds) {
        if (applePlaylist) {
            clearFilter()
            filterRect = LayoutBounds()
        }
        val (title, tracks, fromQueue) = currentTracks()
        val y = page.y
        var headerH = 62f
        val playlist = ScreenState.opened?.takeIf { applePlaylistLayout }
        ui.headerAnim {
            headerH = when {
                playlist != null -> drawApplePlaylistHeader(page, playlist, tracks)
                screen.neteaseUsesAppleLayout && ScreenState.page == Page.QUEUE -> drawNeteaseAppleQueueHeader(page, title, tracks)
                else -> drawHeader(page, title, tracks)
            }
        }
        area = LayoutBounds(page.x, y + headerH, page.width, page.bottom - (y + headerH))
        if (tracks == null) return
        val list = shown(tracks)
        if (list.isEmpty() && filterText.isNotEmpty()) {
            ui.text(I18n.format("No songs match \"%s\"", filterText), area.x + 12f, area.y + 20f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        if (list.isEmpty() && ScreenState.page == Page.PLAYLIST &&
            ScreenState.opened?.id == CloudDrives.id(MusicSources.selected) &&
            !partiallyLoaded() && Library.notice.opacity() <= 0f) {
            ui.text(I18n.text("No cloud songs to display"), area.x + 12f, area.y + 20f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        drawViews(area, tracks, list, fromQueue)
    }

    // 列表 / 封面墙切换：红色选中块用弹簧滑过去（滑动时被拉长一点），内容左右滑入滑出（和设置页的标签切换一样）
    private val viewPill = Spring(300f, 23f, if (ScreenState.coverView) 1f else 0f)
    private val appleViewSlide = Spring(300f, 26f, if (ScreenState.coverView) 1f else 0f)
    private val appleLocateHover = Spring(300f, 26f)
    private val appleListHover = Spring(300f, 26f)
    private val appleCoverWallHover = Spring(300f, 26f)
    private val appleSelectedIcon = Color.fromHex("#A4DDFF")
    private val applePlayInk = Color.fromHex("#311725")
    private val applePlayHovered = Color.fromHex("#EFE9ED")
    private val viewFade = Spring(210f, 24f, 1f)
    private var lastCover = ScreenState.coverView
    private var viewFrom: Boolean? = null

    private fun drawViews(area: LayoutBounds, tracks: List<NeteaseTrack>,
                          list: List<Pair<Int, NeteaseTrack>>, fromQueue: Boolean) {
        val cover = ScreenState.coverView
        if (cover != lastCover) {
            viewFrom = lastCover
            lastCover = cover
            viewFade.x = 0f
            viewFade.v = 0f
        }
        val t = viewFade.tick(1f)
        val from = viewFrom
        if (from == null || t >= 0.999f) {
            viewFrom = null
            if (cover) ui.itemAnim(0) { drawCoverflow(area, tracks, list, fromQueue) } else drawTrackList(area, tracks, list)
            return
        }
        // 封面墙在右边：切到封面墙时内容往左走，切回列表时往右走
        val dir = if (cover) 1f else -1f
        // 两种视图各自会裁剪到 area（裁剪不能嵌套），这里不再套一层
        val out = (1f - t * 1.6f).coerceIn(0f, 1f)
        if (out > 0.01f) {
            // 旧内容只是画出来，点击区域不保留
            val keep = ui.hits.size
            ui.shifted(-dir * 36f * t.coerceIn(0f, 1f), 0f, out) { drawView(from, area, tracks, list, fromQueue) }
            while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
        }
        ui.shifted(dir * 36f * (1f - t), 0f, t.coerceIn(0f, 1f)) { drawView(cover, area, tracks, list, fromQueue) }
    }

    private fun drawView(cover: Boolean, area: LayoutBounds, tracks: List<NeteaseTrack>,
                         list: List<Pair<Int, NeteaseTrack>>, fromQueue: Boolean) {
        if (cover) drawCoverflow(area, tracks, list, fromQueue) else drawTrackList(area, tracks, list)
    }

    /** 当前打开的歌单已经显示了一部分，后面还在加载 */
    private fun partiallyLoaded(): Boolean =
        ScreenState.page == Page.PLAYLIST && ScreenState.opened?.id?.let { Library.loadingTracks(it) } == true

    /** 当前打开的歌单的加载进度（[已拿到, 总数]） */
    private fun loadingProgress(): IntArray? = ScreenState.opened?.id?.let { Library.trackProgress(it) }

    // 加载进度条的平滑动画（换了歌单就重新开始）
    private var loadKey = -1L
    private var loadBar = Spring(160f, 24f)

    private fun drawApplePlaylistHeader(page: LayoutBounds, playlist: NeteasePlaylist, tracks: List<NeteaseTrack>?): Float {
        val backW = if (drawBackButton(page.x, page.y)) 36f else 0f
        val textSize = APPLE_MENU_TEXT_SIZE
        val coverSize = minOf(152f, page.width * 0.35f, page.height * 0.36f).coerceAtLeast(1f)
        val netease = screen.neteaseUsesAppleLayout
        val filterOnSecondLine = drawAppleTrackToolbar(page, backW, netease && tracks != null)
        val cover = LayoutBounds(page.x, page.y + if (filterOnSecondLine) 70f else 38f, coverSize, coverSize)
        ui.drawAppleCover(cover, CoverCache.playlistArtwork(playlist))
        val x = cover.right + minOf(20f, page.width * 0.05f)
        val width = page.right - x - 8f
        val titleSize = if (width >= 180f) 23f else 18f
        val titleY = cover.y + ((cover.height - 112f) * 0.55f).coerceAtLeast(0f)
        ui.text(ui.trim(playlist.name, width, titleSize, Theme.FONT_SEMIBOLD), x, titleY, titleSize,
            Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

        val artist = if (screen.appleNavigation) AppleMusicSource.cardArtist(playlist.id) else ""
        val subtitle = listOf(artist, headerSubtitle(tracks)).filter { it.isNotBlank() }.joinToString(" · ")
        val metaY = titleY + titleSize + 4f
        ui.text(ui.trim(subtitle, width, APPLE_PLAYLIST_SECONDARY_SIZE), x, metaY, APPLE_PLAYLIST_SECONDARY_SIZE, Theme.TEXT_SECONDARY)
        var nextY = metaY + 17f
        if ((tracks == null || partiallyLoaded()) && width >= 40f) {
            ui.progressBar(LayoutBounds(x, nextY - 3f, minOf(160f, width), 3f), loadFraction())
            nextY += 7f
        }
        val description = if (playlist.id == CloudDrives.id(MusicSources.selected)) I18n.text(CloudDrives.note(MusicSources.selected))
            else playlist.description
        if (description.isNotBlank() && width >= 32f) {
            for (line in ui.wrap2(description, minOf(width, 330f), APPLE_PLAYLIST_SECONDARY_SIZE)) {
                ui.text(line, x, nextY, APPLE_PLAYLIST_SECONDARY_SIZE, Theme.TEXT_SECONDARY)
                nextY += 13f
            }
            nextY += 6f
        }
        if (!tracks.isNullOrEmpty()) {
            val label = I18n.text("Play all")
            val playW = minOf(ui.width(label, textSize, Theme.FONT_SEMIBOLD) + 40f, width)
            val play = LayoutBounds(x, maxOf(nextY + 2f, cover.bottom - 26f), playW, 26f)
            drawPlayAll(play, label, tracks, apple = true)
            nextY = play.bottom
            if (netease && Library.canPlayHeartMode(playlist)) {
                val heartLabel = I18n.text(if (Library.heartLoading) "Loading Heart mode…" else "Heart mode")
                val heartWidth = (ui.width(heartLabel, textSize, Theme.FONT_SEMIBOLD) + 38f).coerceAtMost(width)
                val beside = play.right + 8f + heartWidth <= page.right - 8f
                val heart = LayoutBounds(if (beside) play.right + 8f else x,
                    if (beside) play.y else play.bottom + 6f, heartWidth, 26f)
                ui.card(heart, if (ui.hover(heart)) Theme.CARD_HOVER else Theme.CARD, heart.height / 2f)
                PlayerIcons.draw(ui.ctx, PlayerIcons.heartOutline, heart.x + 14f, heart.y + 13f, 13f, APPLE_CONTROL_BLUE)
                ui.text(ui.trim(heartLabel, heart.width - 31f, textSize), heart.x + 26f, heart.y + 8f,
                    textSize, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                ui.hits += heart to { Library.playHeartMode(playlist) }
                nextY = heart.bottom
            }
        }
        return maxOf(cover.bottom, nextY) - page.y + 20f
    }

    private fun drawBackButton(x: Float, y: Float): Boolean {
        if (ScreenState.backPage == null && ScreenState.trail.isEmpty()) return false
        val back = LayoutBounds(x, y + 2f, 28f, 28f)
        ui.card(back, if (ui.hover(back)) Theme.CARD_HOVER else Theme.CARD, 14f)
        if (screen.usesAppleLayout) SidebarIcons.draw(ui.ctx, "left", back.x + 14f, back.y + 14f, Theme.TEXT_PRIMARY, 14f)
        else ui.icon("◀", back.x + 13.5f, back.y + 14f, 10f, Theme.TEXT_PRIMARY)
        ui.hits += back to { screen.goBack() }
        return true
    }

    private fun headerSubtitle(tracks: List<NeteaseTrack>?): String = when {
        ScreenState.page == Page.SEARCH && Library.searching -> I18n.text("Searching...")
        ScreenState.page == Page.SEARCH && tracks.isNullOrEmpty() ->
            if (Library.searchError.isNotEmpty()) I18n.text("Search failed: ") + I18n.message(Library.searchError) else I18n.text("No results")
        ScreenState.page == Page.QUEUE && dangomusicce.music.AppleWebPlayer.stationActive -> I18n.text("Apple Music radio · Updates as it plays")
        ScreenState.page == Page.QUEUE && tracks.isNullOrEmpty() -> I18n.text("Nothing in the queue yet")
        tracks == null || partiallyLoaded() -> loadingProgress()?.takeIf { it[1] > 0 }?.let { I18n.format("Loaded %d of %d songs", it[0], it[1]) } ?: I18n.text("Loading...")
        filterText.isNotEmpty() -> I18n.format("%d of %d songs", shown(tracks).size, tracks.size)
        else -> I18n.format("%d songs", tracks.size)
    }

    private fun loadFraction(): Float? {
        val key = ScreenState.opened?.id ?: 0L
        if (key != loadKey) {
            loadKey = key
            loadBar = Spring(160f, 24f)
        }
        return loadingProgress()?.takeIf { it[1] > 0 }?.let { loadBar.tick((it[0].toFloat() / it[1]).coerceIn(0f, 1f)) }
    }

    private fun drawPlayAll(bounds: LayoutBounds, label: String, tracks: List<NeteaseTrack>, apple: Boolean = false) {
        val centerY = bounds.y + bounds.height / 2f
        val hovered = ui.hover(bounds)
        val color = Theme.TEXT_PRIMARY
        if (apple) {
            val radius = bounds.height / 2f
            ui.card(bounds, if (hovered) applePlayHovered else Color.WHITE, radius)
            val size = APPLE_MENU_TEXT_SIZE
            val iconSize = 12f
            val gap = 5f
            val text = ui.trim(label, (bounds.width - 31f).coerceAtLeast(0f), size, Theme.FONT_SEMIBOLD)
            val textWidth = ui.width(text, size, Theme.FONT_SEMIBOLD)
            val groupWidth = iconSize + if (text.isEmpty()) 0f else gap + textWidth
            val x = bounds.x + (bounds.width - groupWidth) / 2f
            PlayerIcons.draw(ui.ctx, AppleLyricsIcons.play, x + iconSize / 2f, centerY, iconSize, applePlayInk)
            ui.text(text, x + iconSize + gap, centerY - size / 2f, size, applePlayInk, Theme.FONT_SEMIBOLD)
        } else {
            ui.card(bounds, if (hovered) RED.copy(a = 0.85f) else RED, bounds.height / 2f)
            PlayerIcons.draw(ui.ctx, PlayerIcons.play, bounds.x + 18f, centerY, 13f, color)
            ui.text(label, bounds.x + 30f, bounds.y + 9f, 12f, color, Theme.FONT_SEMIBOLD)
        }
        ui.hits += bounds to {
            val continuation = radio()
            val playlistId = openedPlaylistId()
            val startTracks = if (showingHeartQueue() || showingFmQueue()) tracks
                else playlistId?.let(Library::tracks) ?: tracks
            ClientMusic.control { MusicPlayer.playAll(startTracks, continuation, playlistId) }
        }
    }

    /** 标题区，返回它的高度 */
    private fun drawHeader(page: LayoutBounds, title: String, tracks: List<NeteaseTrack>?): Float {
        val ctx = ui.ctx
        val state = ScreenState
        var x = page.x
        val y = page.y
        var headerH = 62f

        // 返回
        if (drawBackButton(x, y)) x += 40f

        // 歌单封面
        val p = state.opened
        if (state.page == Page.PLAYLIST && p != null) {
            val cv = LayoutBounds(x, y, 96f, 96f)
            ui.drawCover(cv, CoverCache.playlistRoundedLarge(p, 0.1f), 12f)
            x = cv.right + 18f
            headerH = 112f
        }

        val header = when (state.page) {
            Page.SEARCH -> I18n.format("Search  \"%s\"", state.lastQuery)
            Page.QUEUE -> I18n.text("Now Playing")
            else -> title
        }
        val segW = 132f
        // 标题最后再画：要等右边的按钮和筛选框排好，才知道标题最多能有多宽（窗口拉窄时不压到它们）
        val titleX = x
        val sub = headerSubtitle(tracks)
        ui.text(sub, x, y + 34f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        // 歌单还在加载：知道总数就按比例走（平滑地追上去），不知道就来回滑，表示没卡住
        // 已经先显示了一部分时（下面已经有"播放全部"和"心动模式"按钮），进度条放到"已加载 x / y 首"这行字的右边
        val partial = tracks != null && partiallyLoaded()
        if ((tracks == null || partial) && state.page != Page.SEARCH && state.page != Page.QUEUE) {
            val shownFraction = loadFraction()
            val barX = if (partial) x + ui.width(sub, 11f, Theme.FONT_SEMIBOLD) + 12f else x
            val barY = if (partial) y + 38f else y + 54f
            // 窗口窄时别伸到右上角的"列表 / 封面墙"切换下面
            val barW = minOf(if (partial) 140f else 180f, page.right - segW - 12f - barX)
            if (barW >= 40f) ui.progressBar(LayoutBounds(barX, barY, barW, 4f), shownFraction)
        }

        // 播放全部
        if (!tracks.isNullOrEmpty() && state.page != Page.QUEUE) {
            val label = I18n.text("Play all")
            val pb = LayoutBounds(x, y + (if (headerH > 100f) 64f else 52f) - 6f, ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 44f, 30f)
            drawPlayAll(pb, label, tracks)
            val liked = state.opened?.takeIf {
                state.page == Page.PLAYLIST && Library.canPlayHeartMode(it)
            }
            if (liked != null) {
                val heartLabel = I18n.text(if (Library.heartLoading) "Loading Heart mode…" else "Heart mode")
                val heart = LayoutBounds(pb.right + 8f, pb.y, ui.width(heartLabel, 12f, Theme.FONT_SEMIBOLD) + 46f, pb.height)
                ctx.drawShape(heart, ShapeStyle(if (ui.hover(heart)) RED.copy(a = 0.85f) else RED, heart.height / 2f))
                PlayerIcons.draw(ctx, PlayerIcons.heartOutline, heart.x + 19f, heart.y + heart.height / 2f, 15f, Theme.TEXT_PRIMARY)
                ui.text(heartLabel, heart.x + 32f, heart.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                ui.hits += heart to { Library.playHeartMode(liked) }
            }
            if (headerH < 100f) headerH = 92f
        }

        // 右上角：列表 / 封面墙
        val seg = LayoutBounds(page.right - segW, y + 4f, segW, 26f)
        drawViewSwitch(seg, 12f)
        var rightX = drawLocate(seg.x - 10f, y, 11f)

        // 歌单内筛选框（按歌名 / 歌手，输入即筛选）
        if (tracks != null && state.page != Page.SEARCH && !applePlaylist) {
            // 窄的时候筛选框跟着变短，给标题至少留 160
            drawFilter(rightX, y, (rightX - titleX - 160f).coerceIn(110f, 190f))
            rightX = filterRect.x - 10f
        } else filterRect = LayoutBounds()
        ui.text(ui.trim(header, rightX - 14f - titleX, 22f, Theme.FONT_DISPLAY), titleX, y + 4f, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        if (state.page == Page.PLAYLIST && p?.id == CloudDrives.id(MusicSources.selected)) {
            val note = CloudDrives.note(MusicSources.selected)
            if (note.isNotEmpty()) {
                ui.text(ui.trim(I18n.text(note), page.right - x - 8f, 10f), x, y + 99f, 10f, Theme.TEXT_SECONDARY)
                headerH = max(headerH, 126f)
            }
        }
        return headerH
    }

    private fun drawNeteaseAppleQueueHeader(page: LayoutBounds, title: String, tracks: List<NeteaseTrack>?): Float {
        val backW = if (drawBackButton(page.x, page.y)) 36f else 0f
        val secondLine = drawAppleTrackToolbar(page, backW, tracks != null)
        val titleY = page.y + if (secondLine) 70f else 38f
        ui.text(ui.trim(title, page.width, 18f, Theme.FONT_SEMIBOLD), page.x, titleY,
            18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(headerSubtitle(tracks), page.width, APPLE_PLAYLIST_SECONDARY_SIZE), page.x, titleY + 23f,
            APPLE_PLAYLIST_SECONDARY_SIZE, Theme.TEXT_SECONDARY)
        return titleY - page.y + 47f
    }

    private fun drawAppleTrackToolbar(page: LayoutBounds, backW: Float, filter: Boolean): Boolean {
        drawApplePlaylistControls(page, backW)
        val filterWidth = (page.width - backW - 102f).coerceAtMost(190f)
        val secondLine = filter && filterWidth < 100f
        when {
            secondLine -> drawFilter(page.right, page.y + 32f, page.width.coerceAtMost(190f))
            filter -> drawFilter(page.x + backW + filterWidth, page.y, filterWidth)
            else -> filterRect = LayoutBounds()
        }
        return secondLine
    }

    private fun drawApplePlaylistControls(page: LayoutBounds, backW: Float) {
        val height = 26f
        val gap = 5f
        val available = page.width - backW - height - gap
        val viewWidth = (available / 2f).coerceIn(16f, 28f)
        val y = page.y + 4f
        val slot = LayoutBounds(page.right - 2f * viewWidth, y, 2f * viewWidth, height)
        val listBounds = slot.copy(width = viewWidth)
        val coverBounds = listBounds.copy(x = listBounds.right)
        val locate = LayoutBounds(slot.x - gap - height, y, height, height)
        val coverView = ScreenState.coverView
        val radius = height / 2f
        ui.card(slot, Color(1f, 1f, 1f, 0.045f), radius)
        val position = appleViewSlide.tick(if (coverView) 1f else 0f).coerceIn(0f, 1f)
        ui.ctx.drawShape(listBounds.copy(x = slot.x + position * viewWidth),
            ShapeStyle(Color(1f, 1f, 1f, 0.10f), clipBounds = slot, clipRadius = radius))
        ui.ctx.drawShape(slot, ShapeStyle(radius = radius, borderWidth = 0.75f,
            borderColor = Color(1f, 1f, 1f, 0.24f)))

        val canLocate = currentInList()
        drawApplePlaylistIcon(locate, "locateTrack", appleLocateHover,
            if (canLocate) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY.copy(a = 0.40f), canLocate)
        drawApplePlaylistIcon(listBounds, "trackList", appleListHover, if (coverView) Theme.TEXT_PRIMARY else appleSelectedIcon)
        drawApplePlaylistIcon(coverBounds, "coverWall", appleCoverWallHover, if (coverView) appleSelectedIcon else Theme.TEXT_PRIMARY)
        if (canLocate) ui.hits += locate to { locateCurrent() }
        ui.hits += listBounds to { if (ScreenState.coverView) screen.toggleCoverView() }
        ui.hits += coverBounds to { if (!ScreenState.coverView) screen.toggleCoverView() }
    }

    private fun drawApplePlaylistIcon(bounds: LayoutBounds, icon: String, hover: Spring, color: Color, enabled: Boolean = true) {
        val scale = ui.appleHoverScale(hover, enabled && ui.hover(bounds))
        SidebarIcons.drawApple(ui.ctx, icon, bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f,
            color, 14f * scale)
    }

    private fun drawViewSwitch(seg: LayoutBounds, textSize: Float) {
        ui.card(seg, Theme.CARD, 13f)
        val half = seg.copy(width = seg.width / 2f)
        val other = half.copy(x = half.right)
        val coverView = ScreenState.coverView
        if (ui.hover(if (coverView) half else other)) ui.card(if (coverView) half else other, Theme.CARD_HOVER, 13f)
        val pill = viewPill.tick(if (coverView) 1f else 0f)
        val stretch = min(14f, abs(viewPill.v) * 5f)
        val px = seg.x + pill.coerceIn(-0.06f, 1.06f) * half.width - (if (viewPill.v > 0f) stretch else 0f)
        ui.card(LayoutBounds(px, seg.y, half.width + stretch, seg.height), RED, 13f)
        val on = pill.coerceIn(0f, 1f)
        val list = ui.trim(I18n.text("List"), half.width - 8f, textSize, Theme.FONT_MEDIUM)
        val covers = ui.trim(I18n.text("Covers"), other.width - 8f, textSize, Theme.FONT_MEDIUM)
        ui.text(list, half.x + (half.width - ui.width(list, textSize, Theme.FONT_MEDIUM)) / 2f, half.y + 8f, textSize,
            mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, 1f - on), Theme.FONT_MEDIUM)
        ui.text(covers, other.x + (other.width - ui.width(covers, textSize, Theme.FONT_MEDIUM)) / 2f, other.y + 8f, textSize,
            mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, on), Theme.FONT_MEDIUM)
        ui.hits += half to { if (ScreenState.coverView) screen.toggleCoverView() }
        ui.hits += other to { if (!ScreenState.coverView) screen.toggleCoverView() }
    }

    private fun drawLocate(rightX: Float, y: Float, textSize: Float): Float {
        if (!currentInList()) return rightX
        val label = I18n.text("Locate")
        val width = ui.width(label, textSize, Theme.FONT_MEDIUM) + 34f
        val bounds = LayoutBounds(rightX - width, y + 4f, width, 26f)
        ui.card(bounds, if (ui.hover(bounds)) Theme.CARD_HOVER else Theme.CARD, 13f)
        ui.icon("◎", bounds.x + 14f, bounds.y + 13f, 11f, RED)
        ui.text(label, bounds.x + 25f, bounds.y + 8f, textSize, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        ui.hits += bounds to { locateCurrent() }
        return bounds.x - 10f
    }

    private fun drawFilter(rightX: Float, y: Float, fw: Float) {
        val ctx = ui.ctx
        filterRect = LayoutBounds(rightX - fw, y + 4f, fw, 26f)
        val maxRight = filterRect.right - 4f - if (filterText.isNotEmpty()) 18f else 0f
        if (screen.neteaseUsesAppleLayout) {
            ui.drawNeteaseAppleSearchField(filterRect, filterText, if (filterFocused) "" else I18n.text("Filter this list..."),
                filterFocused, filterRect.x + 14f, 13f, filterRect.x + 26f, maxRight, 11f)
        } else {
            ui.card(filterRect, if (filterFocused) Theme.CARD_HOVER else Theme.CARD, 13f)
            if (filterFocused) ctx.drawShape(filterRect, ShapeStyle(Color.TRANSPARENT, 13f, 1f, RED))
            ctx.drawShape(LayoutBounds(filterRect.x + 10f, filterRect.y + 8f, 8f, 8f),
                ShapeStyle(Color.TRANSPARENT, 4f, 1.3f, Theme.TEXT_SECONDARY))
            ctx.drawShape(LayoutBounds(filterRect.x + 16.5f, filterRect.y + 15f, 4f, 1.5f), ShapeStyle(Theme.TEXT_SECONDARY, 0.75f))
            val caret = if (filterFocused && caretVisible()) "|" else ""
            val placeholder = filterText.isEmpty() && !filterFocused
            var shownText = if (placeholder) I18n.text("Filter this list...") else filterText + caret
            val maxW = maxRight - filterRect.x - 26f
            while (shownText.length > 1 && ui.width(shownText, 11f) > maxW) shownText = shownText.substring(1)
            ui.text(shownText, filterRect.x + 26f, filterRect.y + 8f, 11f, if (placeholder) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY)
        }
        ui.hits += filterRect to {
            filterFocused = true
            screen.sidebar.searchFocused = false
        }
        if (filterText.isNotEmpty()) {
            val xb = LayoutBounds(filterRect.right - 22f, filterRect.y + 4f, 18f, 18f)
            if (ui.hover(xb)) ctx.drawShape(xb, ShapeStyle(Theme.CARD_HOVER, 9f))
            if (screen.neteaseUsesAppleLayout) SidebarIcons.draw(ctx, "clear", xb.x + 9f, xb.y + 9f, Theme.TEXT_SECONDARY, 12f)
            else ui.icon("×", xb.x + 9f, xb.y + 9f, 11f, Theme.TEXT_SECONDARY)
            ui.hits.add(0, xb to { clearFilter() })     // 优先于筛选框本身
        }
    }

    // ---------------------------------------------------------------- 列表 + 滚动条

    private fun thumbOf(track: LayoutBounds, total: Int, rows: Int): LayoutBounds {
        val proportion = rows.toFloat() / total
        val h = (track.height * proportion).coerceIn(minOf(28f, track.height * 0.5f), track.height - 1f)
        val y = track.y + (track.height - h) * (listPos / (total - rows)).coerceIn(0f, 1f)
        return LayoutBounds(track.x, y, track.width, h)
    }

    private fun drawScrollbar(track: LayoutBounds, total: Int, rows: Int) {
        if (total <= rows || track.height <= 2f) {
            sbTotal = 0
            sbTrack = LayoutBounds()
            scrollbarUp()
            return
        }
        sbTrack = track
        sbTotal = total
        val hot = sbDragging || track.expand(8f, 0f).contains(ui.mx, ui.my)
        ui.ctx.drawShape(track, ShapeStyle(Color(1f, 1f, 1f, if (hot) 0.10f else 0.05f), track.width / 2f))
        val th = thumbOf(track, total, rows)
        val thumb = if (hot) th.expand(1.5f, 0f) else th
        if (sbDragging && screen.usesAppleLayout) {
            val stroke = 1.15f
            val inner = thumb.expand(-stroke, -stroke)
            ui.ctx.drawShape(inner, ShapeStyle(Color.TRANSPARENT, inner.width / 2f, stroke, APPLE_CONTROL_BLUE))
        } else {
            val color = if (sbDragging) RED else Color(1f, 1f, 1f, if (hot) 0.55f else 0.30f)
            ui.ctx.drawShape(thumb, ShapeStyle(color, thumb.width / 2f))
        }
        if (sbDragging) {
            val label = "${listPos.toInt() + 1} / $total"
            val width = ui.width(label, 11f, Theme.FONT_SEMIBOLD)
            ui.text(label, track.x - width - 9f, th.y + (th.height - 11f) / 2f,
                11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        }
    }

    private fun scrollbarTo(y: Float) {
        if (sbTotal <= listRows || sbTrack.height <= 2f) return
        val th = thumbOf(sbTrack, sbTotal, listRows)
        val frac = (y - sbGrab - sbTrack.y) / (sbTrack.height - th.height)
        scroll = frac.coerceIn(0f, 1f) * (sbTotal - listRows)
        listPos = scroll
    }

    /** Drag from the current thumb position, or jump to the point clicked on the track. */
    fun scrollbarDown(mx: Int, y: Float): Boolean {
        if (ScreenState.coverView || sbTotal <= listRows || sbTrack.height <= 2f ||
            !sbTrack.expand(8f, 0f).contains(mx, y.toInt())) return false
        val th = thumbOf(sbTrack, sbTotal, listRows)
        sbGrab = if (y >= th.y && y <= th.bottom) y - th.y else th.height / 2f
        sbDragging = true
        scrollbarTo(y)
        return true
    }

    fun scrollbarDrag(y: Float) = scrollbarTo(y)

    fun scrollbarUp() {
        sbDragging = false
    }

    /** 列表：序号 / 小封面 / 歌名 / 歌手 / 时长 */
    private fun drawTrackList(list: LayoutBounds, all: List<NeteaseTrack>, tracks: List<Pair<Int, NeteaseTrack>>) {
        val ctx = ui.ctx
        val sbW = 16f   // 右边留给滚动条
        val counter = if (sbDragging) {
            val total = tracks.size
            ui.width("$total / $total", 11f, Theme.FONT_SEMIBOLD) + 10f
        } else 0f
        val body = LayoutBounds(list.x, list.y + 22f, (list.width - sbW - counter).coerceAtLeast(0f), list.height - 22f)
        val columns = if (applePlaylistLayout || screen.neteaseUsesAppleLayout) AppleTrackColumns(body) else null
        if (columns != null) drawAppleTrackHeadings(list.y, columns) else {
            ui.text("#", list.x + 12f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            ui.text(I18n.text("TITLE"), list.x + 84f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            ui.text(I18n.text("TIME"), body.right - 12f - ui.width(I18n.text("TIME"), 10f, Theme.FONT_SEMIBOLD),
                list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        }
        val dividerY = list.y + if (columns != null) 18f else 16f
        ctx.drawShape(LayoutBounds(list.x, dividerY, body.width, 1f), ShapeStyle(Theme.SEPARATOR))

        val rowH = if (columns != null) 32f else 44f
        val rowGap = if (columns != null) 1f else 4f
        // 能完整看到的行只算播放栏上面的部分；播放栏底下也接着画几行，滚动时从它后面经过
        val rows = max(1, ((body.height - ui.bottomInset) / rowH).toInt())
        val drawnRows = rows + kotlin.math.ceil(ui.bottomInset / rowH).toInt() + 1
        listRows = rows
        scroll = scroll.coerceIn(0f, max(0, tracks.size - rows).toFloat())
        val queuePage = ScreenState.page == Page.QUEUE
        val selectOnly = queuePage || ScreenState.page == Page.PLAYLIST && MusicSources.selected in listSelectableSources
        val canReorder = queuePage && !ScreenState.coverView &&
            !dangomusicce.music.AppleWebPlayer.stationActive
        val maxScroll = max(0, tracks.size - rows)
        val dragScroll = if (canReorder) queueDrag.edgeScroll(ui.frameDt, ui.mx, ui.my) else 0f
        if (dragScroll != 0f) {
            listPos = (listPos + dragScroll).coerceIn(0f, maxScroll.toFloat())
            scroll = listPos
        } else {
            listPos = if (sbDragging) scroll else ui.easeScroll(listPos, scroll, rows * 3)
        }
        drawScrollbar(LayoutBounds(list.right - 9f, body.y + 2f, 5f, rows * rowH - 8f), tracks.size, rows)
        val flash = (1f - (System.currentTimeMillis() - flashAt) / 1400f).coerceIn(0f, 1f)
        val playing = MusicPlayer.current
        val first = kotlin.math.floor(listPos).toInt()
        val frac = listPos - first
        val clipArea = LayoutBounds(body.x - 4f, body.y - 2f, body.width + 8f, body.height + 2f)
        val reorderArea = LayoutBounds(body.x, body.y, body.width, (body.height - ui.bottomInset).coerceAtLeast(0f))
        if (canReorder) queueDrag.offer(all, reorderArea, body.y - listPos * rowH, rowH, ui, rows = tracks)
        ui.beginClip(clipArea)
        val curRow = if (columns == null) currentRow(tracks, playing?.key, queuePage || showingFmQueue()) else -1
        if (curRow >= 0 && curRow != queueDrag.liftedRow) {
            if (selSnap) { selPos.x = curRow.toFloat(); selPos.v = 0f; selSnap = false }
            // 跨很多行（比如从第 3 首跳到第 400 首）时不从原位置一路飞过去，那样一闪而过很乱：
            // 直接放到目标旁边几行，再顺着方向滑进来
            val gap = curRow - selPos.x
            if (abs(gap) > SEL_MAX_SLIDE) {
                selPos.x = curRow - Math.signum(gap) * SEL_ENTRY
                selPos.v = 0f
            }
            val at = selPos.tick(curRow.toFloat())
            val y = body.y + (at - listPos) * rowH + queueDrag.shift(curRow)
            if (y > body.y - rowH && y < body.bottom) {
                val sel = LayoutBounds(body.x, y, body.width, rowH - rowGap).scaled(1f + selPress.tick(0f) * 0.02f)
                ctx.drawGlass(sel, Theme.GLASS_SELECTION)
            }
        }
        val extraRow = if (queueDrag.liftedRow >= 0) 1 else 0
        for (r in -extraRow..(drawnRows + extraRow)) {
            val vi = first + r
            if (vi < 0) continue
            if (vi >= tracks.size) break
            if (vi == queueDrag.liftedRow) continue
            val (i, t) = tracks[vi]
            ui.itemAnim(r.coerceAtLeast(0)) {
                val row = LayoutBounds(body.x, body.y + (r - frac) * rowH + queueDrag.shift(vi), body.width, rowH - rowGap)
                val isCur = playing?.key == t.key && (!queuePage || i == MusicPlayer.index)
                val hit = row.intersect(clipArea)
                val hovered = ui.hover(hit)
                val radius = if (columns != null) 6f else 10f
                val selection = i to t.key
                val playCover = {
                    selectedAppleTrack = selection
                    if (queuePage) ClientMusic.control { MusicPlayer.activateQueueCover(all, i) }
                    else when (MusicPlayer.state) {
                        MusicPlayer.State.PLAYING, MusicPlayer.State.PAUSED -> if (isCur) {
                            ClientMusic.control { if (MusicPlayer.current?.key == t.key) MusicPlayer.togglePause() }
                        } else playFrom(all, i)
                        MusicPlayer.State.LOADING -> if (!isCur) playFrom(all, i)
                        else -> playFrom(all, i)
                    }
                }
                if (columns != null) {
                    if (isCur || selection == selectedAppleTrack || hovered) {
                        ui.card(row, if (isCur || selection == selectedAppleTrack) Theme.CARD_HOVER else Theme.CARD, radius)
                    }
                    drawAppleTrackRow(row, t, columns, isCur, hovered, clipArea, playCover)
                    ui.hits += hit to {
                        selectedAppleTrack = selection
                        if (!selectOnly) playFrom(all, i)
                    }
                } else {
                    if (selectOnly && selection == selectedAppleTrack) ui.card(row, Theme.CARD_HOVER, radius)
                    else if (!isCur && hovered) ui.card(row, Theme.CARD_HOVER, radius)
                    drawTrackRow(row, t, i, isCur, clipArea)
                    if (selectOnly) {
                        val thumb = trackThumbnail(row)
                        val coverHit = thumb.intersect(clipArea)
                        val showControl = if (queuePage) ui.hover(coverHit) && queueDrag.liftedRow < 0 else hovered
                        if (showControl) ui.drawTrackCoverControl(thumb, 6f, 16f,
                            isCur && MusicPlayer.state == MusicPlayer.State.PLAYING)
                        if (!queuePage || showControl) ui.hits += coverHit to playCover
                    }
                    ui.hits += hit to {
                        selectedAppleTrack = selection
                        if (!selectOnly) {
                            selPress.v -= 6f
                            playFrom(all, i)
                        }
                    }
                }
                if (flash > 0f && t.key == flashId) {
                    ctx.drawShape(row, ShapeStyle(Color.TRANSPARENT, radius, 1.5f, RED.copy(a = flash)))
                }
            }
        }
        if (canReorder) {
            queueDrag.markerY()?.let { y ->
                val accent = if (screen.usesAppleLayout) APPLE_CONTROL_BLUE else RED
                ui.card(LayoutBounds(body.x + 5f, y, body.width - 10f, 2f), accent, 1f)
            }
            val lifted = queueDrag.liftedRow
            if (lifted in tracks.indices) {
                val (index, track) = tracks[lifted]
                val row = LayoutBounds(body.x, queueDrag.liftedY(ui.my), body.width, rowH - rowGap)
                val isCurrent = playing?.key == track.key && index == MusicPlayer.index
                val hitCount = ui.hits.size
                ui.drawLiftedQueueRow(row, classic = !screen.usesAppleLayout)
                if (columns == null) drawTrackRow(row, track, index, isCurrent, clipArea)
                else drawAppleTrackRow(row, track, columns, isCurrent, false, clipArea, play = null)
                while (ui.hits.size > hitCount) ui.hits.removeAt(ui.hits.lastIndex)
            }
        }
        ui.endClip()
    }

    private fun trackThumbnail(row: LayoutBounds) =
        LayoutBounds(row.x + 46f, row.y + 4f, row.height - 8f, row.height - 8f)

    private fun drawTrackRow(row: LayoutBounds, track: NeteaseTrack, index: Int, isCur: Boolean, clip: LayoutBounds) {
        if (isCur && MusicPlayer.state == MusicPlayer.State.PLAYING) {
            ui.icon("♫", row.x + 18f, row.y + 20f, 12f, RED)
        } else {
            ui.text("${index + 1}", row.x + 12f, row.y + 15f, 11f, Theme.TEXT_TERTIARY)
        }
        val thumb = trackThumbnail(row)
        if (screen.usesAppleLayout) ui.drawAppleCover(thumb, CoverCache.track(track))
        else ui.drawCover(thumb, CoverCache.trackRounded(track, 0.15f), 6f)
        val x = thumb.right + 12f
        val width = row.right - 56f - x
        ui.text(ui.trim(track.name, width, 13f), x, row.y + 7f, 13f, if (isCur) RED else Theme.TEXT_PRIMARY)
        if (ScreenState.page == Page.QUEUE) {
            ui.text(ui.trim(track.artists.joinToString(" / "), width, 11f), x, row.y + 23f, 11f, Theme.TEXT_SECONDARY)
        } else drawArtistLinks(screen, track, x, row.y + 23f, width, 11f, Theme.TEXT_SECONDARY, clip)
        val time = fmtTime(track.durationMs)
        ui.text(time, row.right - 12f - ui.width(time, 11f), row.y + 15f, 11f, Theme.TEXT_TERTIARY)
    }

    private class AppleTrackColumns(body: LayoutBounds) {
        val song = body.x + 7f
        val time = body.right - 10f
        val timeStart = time - 43f
        private val width = timeStart - song
        val artist = when {
            body.width >= 420f -> song + width * 0.40f
            body.width >= 260f -> song + width * 0.57f
            else -> null
        }
        val album = if (body.width >= 420f) song + width * 0.68f else null
    }

    private fun drawAppleTrackHeadings(y: Float, columns: AppleTrackColumns) {
        val size = APPLE_PLAYLIST_SECONDARY_SIZE
        val color = Theme.TEXT_SECONDARY
        val font = Theme.FONT_SEMIBOLD
        ui.text(I18n.text("Song"), columns.song, y, size, color, font)
        columns.artist?.let { ui.text(I18n.text("Artist"), it, y, size, color, font) }
        columns.album?.let { ui.text(I18n.text("Album"), it, y, size, color, font) }
        val time = I18n.text("Time")
        ui.text(time, columns.time - ui.width(time, size, font), y, size, color, font)
    }

    private fun drawAppleTrackRow(row: LayoutBounds, track: NeteaseTrack, columns: AppleTrackColumns,
                                  isCur: Boolean, hovered: Boolean, clip: LayoutBounds, play: (() -> Unit)?) {
        val thumb = LayoutBounds(columns.song, row.y + (row.height - 24f) / 2f, 24f, 24f)
        val coverHit = thumb.intersect(clip)
        val coverRadius = 3f
        val compact = columns.artist == null
        val titleSize = APPLE_MENU_TEXT_SIZE
        val centerY = row.y + (row.height - titleSize) / 2f
        val titleY = if (compact) row.y + 4f else centerY
        ui.drawAppleCover(thumb, CoverCache.track(track), radius = coverRadius)
        val playing = isCur && MusicPlayer.state == MusicPlayer.State.PLAYING
        val queueRow = ScreenState.page == Page.QUEUE
        val coverHovered = ui.hover(coverHit) && (!queueRow || queueDrag.liftedRow < 0)
        if (play != null) {
            if (queueRow && coverHovered) ui.drawTrackCoverControl(thumb, coverRadius, 14f, playing)
            else if (playing || !queueRow && hovered) {
                ui.card(thumb, Color.BLACK.copy(a = if (hovered) 0.34f else 0.46f), coverRadius)
                val cx = thumb.x + 12f
                if (playing && !coverHovered) drawAppleTrackWave(cx, titleY + titleSize / 2f)
                else PlayerIcons.draw(ui.ctx, if (playing) AppleLyricsIcons.pause else AppleLyricsIcons.play,
                    cx, thumb.y + 12f, 14f, Theme.TEXT_PRIMARY)
            }
            if (coverHit.height > 0f && (!queueRow || coverHovered)) ui.hits += coverHit to play
        }
        val x = thumb.right + 9f
        val songW = (columns.artist ?: columns.timeStart) - x - 8f
        val secondarySize = APPLE_PLAYLIST_SECONDARY_SIZE
        val titleColor = if (!isCur) Theme.TEXT_PRIMARY else if (screen.neteaseUsesAppleLayout) APPLE_CONTROL_BLUE else RED
        if (songW > 12f) ui.text(ui.trim(track.name, songW, titleSize, Theme.FONT_MEDIUM), x,
            titleY, titleSize, titleColor, Theme.FONT_MEDIUM)

        // 窗口变窄时，先把专辑并到歌手列，再把两者移到歌名下方。
        val artistX = columns.artist ?: x
        val albumX = columns.album ?: if (compact) artistX + (columns.timeStart - artistX) * 0.50f else artistX
        val artistEnd = if (albumX > artistX) albumX else columns.timeStart
        val artistY = when {
            compact -> row.y + 17f
            columns.album == null && track.albumName.isNotBlank() -> row.y + 4f
            else -> centerY
        }
        val artistWidth = artistEnd - artistX - 8f
        if (artistWidth > 8f) {
            if (screen.neteaseUsesAppleLayout && !queueRow)
                drawArtistLinks(screen, track, artistX, artistY, artistWidth, secondarySize, Theme.TEXT_SECONDARY, clip)
            else ui.text(ui.trim(track.artists.joinToString(" / "), artistWidth, secondarySize),
                artistX, artistY, secondarySize, Theme.TEXT_SECONDARY)
        }
        val albumW = columns.timeStart - albumX - 8f
        if (track.albumName.isNotBlank() && albumW > 12f) {
            val albumY = if (columns.album == null) row.y + 17f else centerY
            ui.text(ui.trim(track.albumName, albumW, secondarySize), albumX, albumY, secondarySize, Theme.TEXT_SECONDARY)
        }
        if (track.durationMs > 0L) {
            val time = fmtTime(track.durationMs)
            ui.text(time, columns.time - ui.width(time, secondarySize), centerY, secondarySize, Theme.TEXT_SECONDARY)
        }
        ui.card(LayoutBounds(row.x + 7f, row.bottom, row.width - 14f, 1f), Theme.SEPARATOR, 0f)
    }

    private fun drawAppleTrackWave(cx: Float, cy: Float) {
        val time = System.nanoTime() / 1e9
        val width = 2.25f
        val gap = 0.65f
        val left = cx - (4f * width + 3f * gap) / 2f
        val baseline = cy + 3.5f
        for (i in 0..3) {
            val phase = time * (5.2 + i * 0.83) + i * 1.75
            val pulse = 0.50 + 0.28 * kotlin.math.sin(phase) + 0.18 * kotlin.math.sin(phase * 1.7)
            val height = (3 + 9 * pulse).toFloat() * 0.7f
            ui.card(LayoutBounds(left + i * (width + gap), baseline - height, width, height), Theme.TEXT_PRIMARY, 0.6f)
        }
    }

    // ---------------------------------------------------------------- 封面墙（Coverflow）

    /**
     * 中间一张大封面，两边的依次变小、变淡、往后叠。
     * 滚轮 / ←→ / 点两侧切换，点中间那张（或回车）播放。
     */
    private fun drawCoverflow(full: LayoutBounds, all: List<NeteaseTrack>,
                              shownList: List<Pair<Int, NeteaseTrack>>, fromQueue: Boolean) {
        // 封面墙不滚动，整个放在播放栏上面
        val area = LayoutBounds(full.x, full.y, full.width, full.height - ui.bottomInset)
        coverCount = shownList.size
        if (coverCount == 0 || area.width <= 0f || area.height <= 0f) { resetCoverflow(); return }
        coverMotion.constrain(coverCount)
        val playingKey = MusicPlayer.current?.key
        val queueChanged = fromQueue != observedCoverFromQueue || (fromQueue && all !== observedCoverTracks)
        observedCoverTracks = all
        observedCoverFromQueue = fromQueue
        if (playingKey != observedTrack || queueChanged) {
            observedTrack = playingKey
            val target = currentRow(shownList, playingKey, fromQueue).takeIf { it >= 0 } ?: cfTarget
            if (queueChanged) {
                coverMotion.reset(target)
                lastCoverPosition = cfPos
                coverBlur = 0f
                pressedCover = null
            } else coverMotion.moveTo(target)
        }

        val size = min(area.height * 0.66f, 240f)
        val cx = area.x + area.width / 2f
        // 封面 + 下面三行字作为一个整体，在区域里竖直居中（稍微偏上一点点）
        coverSpacing = size * 0.62f
        val blockH = size + 48f
        val cy = area.y + max(8f, (area.height - blockH) * 0.45f) + size / 2f
        val distance = abs(cfPos - lastCoverPosition)
        lastCoverPosition = cfPos
        val speed = distance * coverSpacing * 1000f / ui.frameDt.coerceAtLeast(1f)
        coverBlur = ui.approach(coverBlur, (speed * 0.0016f).coerceIn(0f, 3.5f), 45f)
        val center = cfPos.toInt()
        val first = max(0, center - 6)
        val last = min(coverCount - 1, center + 6)

        class Item(val i: Int, val d: Float, val b: LayoutBounds)
        val items = (first..last).map { i ->
            val d = i - cfPos
            val ad = abs(d)
            val sign = if (d < 0) -1f else 1f
            val offset = if (ad < 1f) ad * size * 0.62f else size * 0.62f + (ad - 1f) * size * 0.30f
            val scale = 1f - min(ad, 3f) * 0.15f
            val s = size * scale
            Item(i, d, LayoutBounds(cx + sign * offset - s / 2f, cy - s / 2f, s, s))
        }.filter { it.b.right > area.x && it.b.x < area.right }

        // 左右两边：越靠近区域边缘越透明，超出区域的部分裁掉
        val fadeW = area.width * 0.22f
        fun edgeFade(b: LayoutBounds): Float {
            val mid = b.x + b.width / 2f
            val e = (min(mid - area.x, area.right - mid) / fadeW).coerceIn(0f, 1f)
            return e * e * (3f - 2f * e)
        }
        val clip = LayoutBounds(area.x, area.y - 12f, area.width, area.height + 12f)
        ui.beginClip(clip)
        val frontToBack = items.sortedBy { abs(it.d) }
        val occluders = ArrayList<LayoutBounds>(4)
        for ((depth, item) in frontToBack.withIndex()) {
            val ad = abs(item.d)
            val alpha = (1f - min(ad, 4f) * 0.2f).coerceAtLeast(0.15f) * edgeFade(item.b)
            if (alpha < 0.01f) continue
            val track = shownList[item.i].second
            val texture = if (ad < 2.5f) CoverCache.coverflowArtwork(track, coverMotion.dragging || coverBlur > 1.2f)
                else CoverCache.track(track)
            val radius = if (screen.usesAppleLayout) appleCoverRadius(item.b) else item.b.width * 0.08f
            occluders.clear()
            for (index in 0 until depth) {
                val front = frontToBack[index].b
                if (front.x < item.b.right && front.right > item.b.x) occluders.add(front)
            }
            ui.ctx.pushOpacity(alpha)
            try {
                ui.ctx.drawOccludedImage(item.b, texture, radius, occluders, 1f - min(0.45f, ad * 0.18f), coverBlur)
            } finally { ui.ctx.popOpacity() }
        }
        ui.endClip()
        coverHits = frontToBack.filter { edgeFade(it.b) >= 0.2f }.map {
            it.b.intersect(clip) to shownList[it.i].first
        }

        val t = shownList[cfTarget].second
        val isCur = currentRow(shownList, MusicPlayer.current?.key, fromQueue) == cfTarget
        val ty = cy + size / 2f + 14f
        val name = ui.trim(t.name, area.width - 40f, 16f, Theme.FONT_SEMIBOLD)
        val nameColor = if (isCur && !screen.usesAppleLayout) RED else Theme.TEXT_PRIMARY
        ui.text(name, cx - ui.width(name, 16f, Theme.FONT_SEMIBOLD) / 2f, ty, 16f, nameColor, Theme.FONT_SEMIBOLD)
        val artist = ui.trim(t.artists.joinToString(" / "), area.width - 40f, 12f)
        ui.text(artist, cx - ui.width(artist, 12f) / 2f, ty + 21f, 12f, Theme.TEXT_SECONDARY)
    }

    fun coverDown(x: Int, y: Int, pointerX: Float): Boolean {
        if (!tracksPage || !ScreenState.coverView || !area.contains(x, y) || coverListChanged(currentTracks())) return false
        val hit = coverHits.firstOrNull { it.first.contains(x, y) } ?: return false
        pressedCover = hit.second
        coverMotion.down(pointerX, coverSpacing, System.nanoTime())
        return true
    }

    fun coverUp(x: Float) {
        val clicked = coverMotion.up(x, coverCount, System.nanoTime())
        val slot = pressedCover
        pressedCover = null
        if (!clicked || slot == null) return
        val listing = currentTracks()
        if (coverListChanged(listing)) return
        val all = listing.tracks ?: return
        val list = shown(all)
        val index = list.indexOfFirst { it.first == slot }
        if (index < 0) return
        coverMotion.moveTo(index)
        playFrom(all, list[index].first, listing.fromQueue)
    }

    // ---------------------------------------------------------------- 按键

    /** 筛选框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun filterKeyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!filterFocused || applePlaylist) return false
        when {
            keyCode == Keys.ESCAPE -> clearFilter()
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> filterFocused = false
            keyCode == Keys.BACK -> if (filterText.isNotEmpty()) filterText = filterText.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> {
                val clip = DangoClient.platform.clipboard.replace(Regex("[\\r\\n]"), " ")
                filterText = (filterText + clip).take(60)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> filterText = ""
            Keys.isAllowedChar(typedChar) -> filterText = (filterText + typedChar).take(60)
        }
        resetScroll()
        resetCoverflow()
        return true
    }

    /**
     * 歌曲列表页的快捷键（在通用快捷键之前处理）：
     *   Ctrl+F 在歌单里筛选，L 定位到正在播放，Home / End / PgUp / PgDn 翻列表
     */
    fun shortcutBefore(keyCode: Int): Boolean {
        if (!tracksPage) return false
        val listView = !ScreenState.coverView
        when {
            ScreenState.page != Page.SEARCH && !applePlaylist && DangoClient.platform.ctrlDown && keyCode == Keys.F -> filterFocused = true
            keyCode == Keys.L -> locateCurrent()
            listView && keyCode == Keys.HOME -> scroll = 0f
            listView && keyCode == Keys.END -> scroll = Float.MAX_VALUE
            listView && keyCode == Keys.PRIOR -> scroll -= listRows
            listView && keyCode == Keys.NEXT -> scroll += listRows
            else -> return false
        }
        return true
    }

    /** 封面墙：←→ 切换，回车播放（在空格 / 退格之后、←→ 快进快退之前处理） */
    fun coverflowKey(keyCode: Int): Boolean {
        if (!tracksPage || !ScreenState.coverView) return false
        when (keyCode) {
            Keys.RIGHT -> cfTarget++
            Keys.LEFT -> cfTarget--
            Keys.RETURN, Keys.NUMPADENTER -> {
                val listing = currentTracks()
                val all = listing.tracks
                if (all != null && !coverListChanged(listing)) {
                    val list = shown(all)
                    if (cfTarget in list.indices) playFrom(all, list[cfTarget].first, listing.fromQueue)
                }
            }
            else -> return false
        }
        return true
    }
}

/** 高亮最多滑过几行，再远就从目标旁边 [SEL_ENTRY] 行开始滑 */
private const val SEL_MAX_SLIDE = 8f
private const val SEL_ENTRY = 3f
private const val APPLE_PLAYLIST_SECONDARY_SIZE = 9.75f
