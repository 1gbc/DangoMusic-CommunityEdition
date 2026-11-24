package dangomusicce.musicui

import dangomusicce.i18n.I18n
import dangomusicce.music.AppleMusicSource
import dangomusicce.music.AppleMusicSource.HomeFeed

import dangomusicce.music.CoverCache
import dangomusicce.music.MusicScreen
import dangomusicce.music.NeteaseApi
import dangomusicce.music.NeteasePlaylist
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.utils.Color

import kotlin.math.max
import kotlin.math.min

/** 歌单墙（主页推荐、排行榜）：正方形封面 + 下面两行名字，平滑滚动 */
internal class GridPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 本帧画了歌单墙的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素）
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f
    private val shelfStarts = mutableMapOf<HomeFeed, Int>()
    val atTop get() = target == 0f && pos < 1f

    fun tick() {
        pos = ui.approach(pos, target, 70f)
        if (kotlin.math.abs(pos - target) < 0.5f) pos = target
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
        shelfStarts.clear()
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -120f else 120f)).coerceIn(0f, maxScroll)
    }

    fun draw(page: LayoutBounds, title: String, sub: String, items: List<NeteasePlaylist>, loading: Boolean,
             categoryTiles: Boolean = false, wideCards: Boolean = false) {
        ui.headerAnim {
            ui.text(title, page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            if (loading || sub.isNotEmpty())
                ui.text(if (loading) I18n.text("Loading...") else sub, page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            if (loading) ui.progressBar(LayoutBounds(page.x, page.y + 48f, 200f, 3f), null)
        }
        val top = page.y + if (!loading && sub.isEmpty()) 38f else 54f
        area = LayoutBounds(page.x, top, page.width, (page.bottom - top).coerceAtLeast(0f))
        drawGrid(area, items, categoryTiles, wideCards)
    }

    /** 只画封面墙（歌手页的专辑用，标题自己画） */
    fun drawItems(area: LayoutBounds, items: List<NeteasePlaylist>) {
        this.area = area
        drawGrid(area, items)
    }

    fun drawNeteaseHome(page: LayoutBounds, items: List<NeteasePlaylist>, loading: Boolean) {
        ui.text(I18n.text("Home"), page.x, page.y + 3f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val refresh = LayoutBounds(page.right - 25f, page.y, 24f, 24f)
        PlayerIcons.draw(ui.ctx, PlayerIcons.repeat, refresh.x + 12f, refresh.y + 12f, 14f,
            if (ui.hover(refresh)) APPLE_CONTROL_BLUE else Theme.TEXT_SECONDARY)
        ui.hits += refresh to { Library.refreshAll() }
        area = page.copy(y = page.y + 34f, height = (page.height - 34f).coerceAtLeast(0f))
        if (area.width <= 0f || area.height <= 0f) return
        val personal = items.filter { it.id == NeteaseApi.DAILY_ID || it.id == NeteaseApi.FM_ID }
        val liked = Library.playlists.firstOrNull(Library::canPlayHeartMode)
        val featured = personal + listOfNotNull(liked)
        val recommended = items.filterNot { it.id == NeteaseApi.DAILY_ID || it.id == NeteaseApi.FM_ID }
        val columns = ((area.width + 16f) / 138f).toInt().coerceIn(1, 8)
        val width = (area.width - (columns - 1) * 16f) / columns
        var offset = 0f
        ui.beginClip(area)
        try {
            for ((index, playlist) in featured.withIndex()) {
                val x = area.x + (index % columns) * (width + 16f)
                val y = area.y + (index / columns) * (width + 20f) - pos
                val bounds = LayoutBounds(x, y, width, width)
                if (bounds.intersect(area).height <= 0f) continue
                ui.drawAppleCover(bounds, CoverCache.playlistArtwork(playlist), cropSquare = true)
                if (ui.hover(bounds.intersect(area))) ui.card(bounds, Theme.CARD_HOVER, appleCoverRadius(bounds))
                val label = when (playlist.id) {
                    NeteaseApi.DAILY_ID -> "Daily recommendations"
                    NeteaseApi.FM_ID -> "Personal FM"
                    else -> if (Library.heartLoading) "Loading Heart mode…" else "Heart mode"
                }
                val caption = LayoutBounds(x, bounds.bottom - 35f, width, 35f)
                ui.card(caption, Color(0.025f, 0.025f, 0.035f, 0.70f), appleCoverRadius(bounds))
                ui.text(ui.trim(I18n.text(label), width - 42f, 12f, Theme.FONT_SEMIBOLD),
                    x + 10f, caption.y + 11f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                PlayerIcons.draw(ui.ctx, if (playlist === liked) PlayerIcons.heart else PlayerIcons.play,
                    bounds.right - 19f, caption.y + 18f, 14f, Theme.TEXT_PRIMARY)
                ui.hits += bounds.intersect(area) to {
                    if (playlist === liked) Library.playHeartMode(playlist) else screen.openPlaylist(playlist, Page.HOME)
                }
            }
            if (featured.isNotEmpty()) offset = ((featured.size + columns - 1) / columns) * (width + 20f) + 8f
            val headingY = area.y + offset - pos
            if (headingY >= area.y - 20f && headingY < area.bottom)
                ui.text(I18n.text(if (loading) "Loading..." else "Recommended playlists"), area.x, headingY,
                    13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            offset += 28f
            for ((index, playlist) in recommended.withIndex()) {
                val x = area.x + index % columns * (width + 16f)
                val y = area.y + offset + index / columns * (width + 46f) - pos
                val cell = LayoutBounds(x, y, width, width + 40f)
                val hit = cell.intersect(area)
                if (hit.height <= 0f) continue
                val artwork = cell.copy(height = width)
                ui.drawAppleCover(artwork, CoverCache.playlistArtwork(playlist))
                if (ui.hover(hit)) {
                    val button = LayoutBounds(artwork.right - 31f, artwork.bottom - 31f, 24f, 24f)
                    ui.card(button, Color(0f, 0f, 0f, 0.55f), 12f)
                    PlayerIcons.draw(ui.ctx, PlayerIcons.play, button.x + 12f, button.y + 12f, 13f, Theme.TEXT_PRIMARY)
                }
                ui.wrap2(playlist.name, width - 2f, 11f, Theme.FONT_MEDIUM).forEachIndexed { line, text ->
                    ui.text(text, x, artwork.bottom + 7f + line * 14f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                }
                ui.hits += hit to { screen.openPlaylist(playlist, Page.HOME) }
            }
            offset += ((recommended.size + columns - 1) / columns) * (width + 46f)
        } finally { ui.endClip() }
        maxScroll = (offset + ui.bottomInset - area.height).coerceAtLeast(0f)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
    }

    fun drawAppleHome(page: LayoutBounds, items: List<NeteasePlaylist>, loading: Boolean, warning: String, pull: Float,
                      neteaseHeading: String? = null) {
        ui.text(I18n.text(if (neteaseHeading == null) "Home" else "Welcome to Dango Music!"),
            page.x, page.y + 3f, APPLE_MENU_TEXT_SIZE, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        if (loading || warning.isNotEmpty()) {
            val status = if (loading) I18n.text("Loading...") else warning
            ui.text(ui.trim(status, page.width - 120f, 9f), page.x, page.y + 22f, 9f, Theme.TEXT_SECONDARY)
        }
        val top = page.y + if (loading || warning.isNotEmpty()) 39f else 27f
        area = page.copy(y = top, height = (page.bottom - top).coerceAtLeast(0f))
        if (area.width <= 0f || area.height <= 0f) return
        val grouped = if (neteaseHeading == null) items.groupBy { AppleMusicSource.homeFeed(it.id) }
            else mapOf(HomeFeed.RECOMMENDATIONS to items)
        val order = if (neteaseHeading != null) listOf(HomeFeed.RECOMMENDATIONS to neteaseHeading)
            else listOf(HomeFeed.RECOMMENDATIONS to I18n.text("Recommended for you"), HomeFeed.RECENT to I18n.text("Recently played"),
                HomeFeed.HEAVY_ROTATION to I18n.text("Heavy rotation"), HomeFeed.SHORTCUTS to I18n.text("Shortcuts"))
        var offset = 3f
        ui.beginClip(area)
        try {
            for ((feed, heading) in order) {
                val cards = grouped[feed].orEmpty()
                if (cards.isEmpty()) continue
                val featured = feed == HomeFeed.RECOMMENDATIONS
                val columns = minOf(if (featured) 5 else 6, ((area.width + 12f) / (if (featured) 126f else 108f)).toInt().coerceAtLeast(1))
                val width = (area.width - 12f * (columns - 1)) / columns
                val artworkHeight = if (featured) width * 4f / 3f else width
                val rowHeight = 26f + artworkHeight + (if (featured) 0f else 32f) + 22f
                val y = area.y + offset - pos + pull
                offset += rowHeight
                if (y + rowHeight <= area.y || y >= area.bottom) continue
                drawHomeShelf(feed, heading, cards, y, columns, width, artworkHeight, featured)
            }
        } finally {
            ui.endClip()
        }
        maxScroll = (offset + ui.bottomInset - area.height).coerceAtLeast(0f)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
    }

    private fun drawHomeShelf(feed: HomeFeed, heading: String, cards: List<NeteasePlaylist>, y: Float,
                              columns: Int, width: Float, artworkHeight: Float, featured: Boolean) {
        val first = (shelfStarts[feed] ?: 0).coerceIn(0, (cards.size - columns).coerceAtLeast(0))
        ui.text(heading, area.x, y + (22f - APPLE_MENU_TEXT_SIZE) / 2f,
            APPLE_MENU_TEXT_SIZE, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        for (direction in listOf(-1, 1)) {
            val enabled = if (direction < 0) first > 0 else first + columns < cards.size
            if (!enabled) continue
            val button = LayoutBounds(area.right - if (direction < 0) 46f else 22f, y, 22f, 22f)
            val hit = button.intersect(area)
            if (hit.height <= 0f) continue
            ui.card(button, if (ui.hover(hit)) Theme.CARD_HOVER else Theme.CARD, 11f)
            SidebarIcons.draw(ui.ctx, if (direction < 0) "left" else "right", button.x + 11f, button.y + 11f,
                APPLE_CONTROL_BLUE, 10f)
            ui.hits += hit to { shelfStarts[feed] = first + direction * columns }
        }
        for ((index, item) in cards.drop(first).take(columns).withIndex()) {
            val bounds = LayoutBounds(area.x + index * (width + 12f), y + 23f, width, artworkHeight)
            val cell = bounds.copy(height = bounds.height + if (featured) 0f else 28f)
            if (cell.intersect(area).height <= 0f) continue
            val cover = CoverCache.playlistArtwork(item)
            val artist = if (screen.appleNavigation) AppleMusicSource.cardArtist(item.id) else ""
            if (featured) {
                val radius = appleCoverRadius(bounds)
                if (cover != null) ui.ctx.drawCroppedSquareImage(bounds, cover, radius)
                else ui.card(bounds, Theme.TRACK, radius)
                if (ui.hover(cell.intersect(area))) ui.card(bounds, Theme.CARD, radius)
                val textX = bounds.x + 9f
                val textWidth = (bounds.width - 18f).coerceAtLeast(0f)
                val titleY = bounds.bottom - if (artist.isBlank()) 20f else 32f
                ui.text(ui.trim(item.name, textWidth, APPLE_MENU_TEXT_SIZE, Theme.FONT_SEMIBOLD), textX,
                    titleY, APPLE_MENU_TEXT_SIZE, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                if (artist.isNotBlank()) ui.text(ui.trim(artist, textWidth, 9f), textX,
                    bounds.bottom - 17f, 9f, Theme.TEXT_SECONDARY)
            } else {
                ui.drawAppleCover(bounds, cover)
                ui.text(ui.trim(item.name, width, APPLE_MENU_TEXT_SIZE), bounds.x, bounds.bottom + 5f,
                    APPLE_MENU_TEXT_SIZE, Theme.TEXT_PRIMARY)
                if (artist.isNotBlank()) ui.text(ui.trim(artist, width, 9f), bounds.x, bounds.bottom + 19f, 9f, Theme.TEXT_SECONDARY)
            }
            ui.hits += cell.intersect(area) to { screen.openGridCard(item) }
        }
    }

    private fun drawGrid(area: LayoutBounds, items: List<NeteasePlaylist>,
                         categoryTiles: Boolean = false, wideCards: Boolean = false) {
        val gap = if (categoryTiles) 12f else 22f
        val cols = if (wideCards) (if (area.width < 440f) 1 else 2)
            else max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = when {
            categoryTiles -> cw * 0.56f + gap
            wideCards -> cw * 0.57f + 32f
            else -> cw + 46f
        }
        val rows = items.chunked(cols)
        val totalHeight = rowH * rows.size
        maxScroll = max(0f, totalHeight + ui.bottomInset - area.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)

        // 上下多留一点空间给悬停时放大的边框
        val clip = LayoutBounds(area.x - 6f, area.y - 6f, area.width + 12f, area.height + 6f)
        ui.beginClip(clip)
        var visible = 0
        for ((row, cards) in rows.withIndex()) {
            val y = area.y + row * rowH - pos
            if (y + rowH < area.y || y > area.bottom) continue
            for ((c,p) in cards.withIndex()) {
                val x = area.x + c * (cw + gap)
                ui.itemAnim(visible++) {
                    if (categoryTiles) {
                        val tile = LayoutBounds(x, y, cw, rowH - gap)
                        val color = categoryColors[Math.floorMod(p.id, categoryColors.size.toLong()).toInt()]
                        ui.card(tile, if (ui.hover(tile)) color.copy(a = 0.82f) else color, 13f)
                        ui.text(ui.trim(p.name, cw - 24f, 13f), x + 12f, tile.bottom - 24f, 13f,
                            Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                        ui.hits += tile.intersect(area) to { screen.openGridCard(p) }
                        return@itemAnim
                    }
                    if (wideCards) {
                        val tile = LayoutBounds(x, y, cw, rowH - 12f)
                        ui.card(tile, if (ui.hover(tile)) Theme.CARD_HOVER else Theme.CARD, 15f)
                        val coverSize = tile.height - 16f
                        val cover = LayoutBounds(x + 8f, y + 8f, coverSize, coverSize)
                        if (screen.usesAppleLayout) ui.drawAppleCover(cover, CoverCache.playlistArtwork(p), cropSquare = true)
                        else ui.drawCover(cover, CoverCache.playlistRounded(p, 0.10f), 12f)
                        val titleX = cover.right + 13f
                        for ((line, label) in ui.wrap2(p.name, tile.right - titleX - 12f, 15f).withIndex()) {
                            ui.text(label, titleX, y + tile.height / 2f - 10f + line * 18f, 15f,
                                Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                        }
                        ui.hits += tile.intersect(area) to { screen.openGridCard(p) }
                        return@itemAnim
                    }
                    val cover = LayoutBounds(x, y, cw, cw)
                    val cell = LayoutBounds(x, y, cw, rowH - 6f)
                    val hov = ui.hover(cell) && clip.contains(ui.mx, ui.my)
                    if (hov) ui.ctx.drawShape(LayoutBounds(x - 5f, y - 5f, cw + 10f, rowH + 2f), ShapeStyle(Theme.CARD_HOVER, 14f))
                    if (screen.usesAppleLayout) ui.drawAppleCover(cover, CoverCache.playlistArtwork(p))
                    else ui.drawCover(cover, CoverCache.playlistRoundedLarge(p, 0.08f), 12f)
                    if (hov) {
                        // 悬停：右下角一个播放按钮
                        val pb = LayoutBounds(cover.right - 40f, cover.bottom - 40f, 30f, 30f)
                        ui.ctx.drawShape(pb, ShapeStyle(if (screen.neteaseUsesAppleLayout) APPLE_CONTROL_BLUE else RED, 15f))
                        PlayerIcons.draw(ui.ctx, PlayerIcons.play, pb.x + 15f, pb.y + 15f, 17f, Theme.TEXT_PRIMARY)
                    }
                    val lines = ui.wrap2(p.name, cw - 4f, 12f, Theme.FONT_MEDIUM)
                    lines.forEachIndexed { li, line ->
                        ui.text(line, x + 2f, cover.bottom + 8f + li * 15f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    }
                    val from = ScreenState.page
                    ui.hits += cell.intersect(area) to {
                        if (screen.appleNavigation) screen.openGridCard(p) else screen.openPlaylist(p, from)
                    }
                }
            }
        }
        ui.endClip()
    }

    private val categoryColors = listOf("#B64569", "#227B67", "#485AA3", "#704199", "#A14541", "#356D85").map(Color::fromHex)
}
