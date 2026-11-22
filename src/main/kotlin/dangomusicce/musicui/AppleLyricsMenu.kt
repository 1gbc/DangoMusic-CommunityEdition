package dangomusicce.musicui

import dangomusicce.DangoClient
import dangomusicce.client.ClientMusic
import dangomusicce.i18n.I18n
import dangomusicce.module.values.BooleanValue
import dangomusicce.music.AppleMusicSource
import dangomusicce.music.AppleWebPlayer
import dangomusicce.music.CoverCache
import dangomusicce.music.MusicPlayer
import dangomusicce.music.MusicSource
import dangomusicce.music.NeteaseTrack
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShadowStyle
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.VectorIcon
import dangomusicce.ui.Vec2
import dangomusicce.utils.Color
import java.util.concurrent.CompletableFuture

internal class AppleLyricsMenu(
    private val ui: Ui,
    private val romanization: BooleanValue,
    private val translation: BooleanValue,
) {
    var playerPanel: ((LayoutBounds) -> Unit)? = null
    private var playlistOpen = false
    private var playlistScroll = 0
    private val playlistMotion = PopupMotion()
    var playerBarTop = 0f
    private var playlistMaxScroll = 0
    private var trackKey: String? = null
    private var menuOpen = false
    private var menuX = 0f
    private var menuY = 0f
    private var creditsOpen = false
    private var copyPending = false
    private var details: CompletableFuture<AppleMusicSource.SongDetails>? = null
    private var notice = TimedNotice("")

    private data class Action(
        val label: String, val icon: VectorIcon, val enabled: Boolean = true,
        val highlighted: Boolean = false, val checked: Boolean = false, val run: () -> Unit,
    )

    val capturesInput get() = menuOpen || creditsOpen || playlistOpen

    fun toggle() {
        menuOpen = !menuOpen
        if (menuOpen) {
            menuX = ui.mx.toFloat()
            menuY = ui.my.toFloat()
        }
    }

    fun dismiss(): Boolean {
        if (!capturesInput) return false
        menuOpen = false
        creditsOpen = false
        playlistOpen = false
        return true
    }

    fun reset() {
        dismiss()
        playlistMotion.snap(false)
        trackKey = null
        copyPending = false
        details = null
        notice = TimedNotice("")
    }

    @JvmOverloads
    fun draw(frame: AppleLyricsLayout, track: NeteaseTrack?, bottomAnchor: LayoutBounds? = null) {
        if (frame.scale <= 0f) return
        val song = track?.takeIf { it.source == MusicSource.APPLE }
        if (trackKey != song?.key) {
            reset()
            trackKey = song?.key
        }

        val request = details
        val complete = request?.isDone == true
        val metadata = if (complete) runCatching { request?.join() }.getOrNull() else null
        if (copyPending && complete) {
            copyPending = false
            val link = metadata?.link.orEmpty()
            val copied = link.isNotEmpty() && runCatching { DangoClient.platform.clipboard = link }.isSuccess
            notice = TimedNotice(I18n.text(if (copied) "Link copied" else "Apple Music link unavailable"))
        }

        val alpha = notice.opacity()
        if (alpha > 0f) {
            ui.text(notice.text, bottomAnchor?.let { (it.right - ui.width(notice.text, 11f)).coerceAtLeast(16f) } ?: frame.artwork.x,
                bottomAnchor?.let { it.y - 47f } ?: frame.y(1073f), if (bottomAnchor != null) 11f else 13f * frame.scale,
                Theme.TEXT_SECONDARY.copy(a = alpha))
        }
        if (song == null) return

        val hitStart = ui.hits.size
        if (menuOpen) drawMenu(frame.viewport, if (bottomAnchor != null) bottomActions(song) else lyricActions(song),
            if (bottomAnchor != null) "Apple Player Menu" else "Apple Lyrics Menu")
        if (bottomAnchor != null) drawPlaylistMenu(frame.viewport, bottomAnchor, song)
        if (creditsOpen) drawCredits(frame, song, metadata, complete)
        prioritizeHits(frame.viewport, hitStart)
    }

    fun drawNetease(viewport: LayoutBounds, anchor: LayoutBounds, openQuality: (LayoutBounds) -> Unit) {
        if (!menuOpen && !playlistOpen && !playlistMotion.visible) return
        val hitStart = ui.hits.size
        val song = MusicPlayer.current?.takeIf { it.source == MusicSource.NETEASE }
        if (menuOpen) drawMenu(viewport, listOf(
            Action("Quality", QUALITY) {
                menuOpen = false
                openQuality(anchor)
            },
            Action("Stop playback", PlayerIcons.stop, MusicPlayer.current?.source == MusicSource.NETEASE) {
                menuOpen = false
                ClientMusic.control { if (MusicPlayer.current?.source == MusicSource.NETEASE) Library.stopPlayback() }
            },
            saveAction(song),
        ), "NetEase Player Menu")
        if (song != null) drawPlaylistMenu(viewport, anchor, song)
        else if (playlistOpen || playlistMotion.visible) {
            dismiss()
            playlistMotion.snap(false)
        }
        prioritizeHits(viewport, hitStart)
    }

    private fun prioritizeHits(viewport: LayoutBounds, hitStart: Int) {
        if (capturesInput) {
            ui.hits += viewport to { dismiss() }
            val overlayHits = ui.hits.subList(hitStart, ui.hits.size).toList()
            ui.hits.subList(hitStart, ui.hits.size).clear()
            ui.hits.addAll(0, overlayHits)
        }
    }

    private fun load(track: NeteaseTrack) {
        if (details == null || details?.isCompletedExceptionally == true) {
            details = Library.appleSongDetails(track)
        }
    }

    private fun menuBounds(viewport: LayoutBounds, width: Float, height: Float, scale: Float): LayoutBounds {
        val gap = 2f * scale
        val padding = 4f * scale
        val left = viewport.x + padding
        val top = viewport.y + padding
        val maxX = (viewport.right - padding - width).coerceAtLeast(left)
        val maxY = (viewport.bottom - padding - height).coerceAtLeast(top)
        val x = if (menuX + gap <= maxX) menuX + gap else menuX - gap - width
        val y = if (menuY + gap <= maxY) menuY + gap else menuY - gap - height
        return LayoutBounds(x.coerceIn(left, maxX), y.coerceIn(top, maxY), width, height)
    }

    private fun drawMenu(viewport: LayoutBounds, actions: List<Action>, context: String) {
        // Menus share GUI coordinates even when the full lyrics layout uses its own reference scale.
        val scale = minOf(MENU_SCALE, viewport.width / (MENU_WIDTH + 8f), viewport.height / (ROW_HEIGHT * 5f + 16f))
        val rowHeight = ROW_HEIGHT * scale
        val inset = 4f * scale
        val panel = menuBounds(viewport, MENU_WIDTH * scale, rowHeight * actions.size + 2f * inset, scale)
        if (context.endsWith("Player Menu") && playerPanel != null) playerPanel!!.invoke(panel)
        else {
        ui.ctx.pushOpacity(0.94f)
        try {
            val glass = MENU_GLASS
            ui.ctx.drawGlass(panel, glass.copy(radius = glass.radius * scale,
                refraction = glass.refraction * scale, bevel = glass.bevel * scale,
                shadow = glass.shadow.copy(size = glass.shadow.size * scale,
                    offset = Vec2(0f, 2f * scale))))
        } finally {
            ui.ctx.popOpacity()
        }
        }
        ui.popup(panel) {
            actions.forEachIndexed { index, action ->
                val bounds = LayoutBounds(panel.x + inset, panel.y + inset + index * rowHeight,
                    panel.width - 2f * inset, rowHeight)
                if (index > 0) ui.card(bounds.copy(height = scale), Theme.HAIRLINE, 0f)
                drawRow(bounds, action, context, scale)
            }
        }
    }

    private fun bottomActions(song: NeteaseTrack): List<Action> {
        val canQueue = !AppleWebPlayer.stationActive && MusicPlayer.state != MusicPlayer.State.LOADING
        val favorite = Library.favoriteState(song)
        return listOf(
            Action("Play Next", PLAY_NEXT, canQueue) {
                ClientMusic.control { MusicPlayer.enqueueApple(song, next = true) }
                menuOpen = false
            },
            Action("Play Last", PLAY_LAST, canQueue) {
                ClientMusic.control { MusicPlayer.enqueueApple(song, next = false) }
                menuOpen = false
            },
            Action("Personal Favorite", STAR, favorite?.selected != null && !favorite.pending,
                highlighted = favorite?.selected == true) { Library.toggleAppleFavorite(song) },
            creditsAction(song), linkAction(song), saveAction(song),
            Action("Stop playback", PlayerIcons.stop) { menuOpen = false; Library.stopPlayback() },
        )
    }

    private fun saveAction(song: NeteaseTrack?) = Action("Add to Playlist", SAVE, song != null && !Library.addingToPlaylist) {
        menuOpen = false
        playlistScroll = 0
        playlistOpen = true
    }

    fun scrollPlaylists(wheel: Int): Boolean {
        if (!playlistOpen) return false
        playlistScroll = (playlistScroll + if (wheel > 0) -3 else 3).coerceIn(0, playlistMaxScroll)
        return true
    }

    private fun drawPlaylistMenu(viewport: LayoutBounds, anchor: LayoutBounds, song: NeteaseTrack) {
        playlistMotion.step(playlistOpen, ui.frameDt / 1000f)
        if (!playlistMotion.visible) return
        val lists = Library.userPlaylists()
        val rowHeight = 30f
        val width = minOf(260f, (viewport.width - 16f).coerceAtLeast(1f))
        val bottom = playerBarTop - CONTROL_POPUP_GAP
        val available = bottom - viewport.y - 8f
        if (available <= 48f) return
        val rows = ((available - 48f) / rowHeight).toInt().coerceIn(1, 10)
        val visible = minOf(rows, lists.size.coerceAtLeast(1))
        val height = minOf(available, 48f + rowHeight * visible)
        val target = LayoutBounds((anchor.right - width).coerceIn(viewport.x + 8f,
            (viewport.right - width - 8f).coerceAtLeast(viewport.x + 8f)), bottom - height, width, height)
        playlistMaxScroll = (lists.size - visible).coerceAtLeast(0)
        playlistScroll = playlistScroll.coerceIn(0, playlistMaxScroll)

        playlistMotion.draw(ui, anchor, target, playlistOpen, panel = { bounds ->
            playerPanel?.invoke(bounds) ?: PopupLook.panel(ui.ctx, bounds)
        }) { panel, interactive, clip ->
            ui.text(I18n.text("Add to Playlist"), panel.x + 14f, panel.y + 10f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(song.name, width - 28f, 9f), panel.x + 14f, panel.y + 27f, 9f, Theme.TEXT_SECONDARY)
            val rowsArea = panel.copy(y = panel.y + 45f, height = panel.height - 48f)
            val rowsClip = rowsArea.intersect(clip)
            if (rowsClip.height > 0f) {
                ui.beginClip(rowsClip)
                try {
                    ui.popup(rowsClip) {
                        if (lists.isEmpty()) ui.text(I18n.text("No playlists"), panel.x + 14f, rowsArea.y + 8f, 11f, Theme.TEXT_SECONDARY)
                        for (index in playlistScroll until minOf(lists.size, playlistScroll + visible)) {
                            val playlist = lists[index]
                            val row = LayoutBounds(panel.x + 8f, rowsArea.y + (index - playlistScroll) * rowHeight,
                                width - 16f, rowHeight - 2f)
                            val hit = row.intersect(rowsClip)
                            if (hit.height <= 0f) continue
                            val enabled = interactive && !Library.addingToPlaylist
                            if (enabled && ui.hover(hit)) ui.card(row, PopupLook.hover, 6f)
                            val art = LayoutBounds(row.x + 6f, row.y + 4f, 20f, 20f)
                            val color = if (Library.addingToPlaylist) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY
                            val texture = CoverCache.playlist(playlist)
                            if (texture != null) ui.drawAppleCover(art, texture, radius = 3f)
                            else SidebarIcons.draw(ui.ctx, if (playlist.likedPlaylist) "♥" else "≡",
                                art.x + 10f, art.y + 10f, color, 13f)
                            ui.text(ui.trim(playlist.name, row.width - 42f, 12f), row.x + 34f, row.y + 7f, 12f, color)
                            if (enabled) ui.hits += hit to {
                                if (MusicPlayer.current?.key == song.key) {
                                    Library.addToPlaylist(playlist, song)
                                    dismiss()
                                }
                            }
                        }
                    }
                } finally { ui.endClip() }
            }
        }
    }

    private fun lyricActions(song: NeteaseTrack) = listOf(
        creditsAction(song), linkAction(song),
        Action("Romanization", AppleLyricsIcons.romanization, checked = romanization.get(), run = romanization::toggle),
        Action("Translation", AppleLyricsIcons.translation, checked = translation.get(), run = translation::toggle),
    )

    private fun creditsAction(song: NeteaseTrack) = Action("View Credits", INFO) {
        menuOpen = false
        creditsOpen = true
        load(song)
    }

    private fun linkAction(song: NeteaseTrack) = Action("Copy Link", LINK) {
        menuOpen = false
        copyPending = true
        load(song)
    }

    private fun drawRow(bounds: LayoutBounds, action: Action, context: String, scale: Float) {
        val hovered = action.enabled && ui.hover(bounds)
        if (hovered) ui.card(bounds, Theme.CARD_HOVER, 3f * scale)
        val fontSize = 11f * scale
        val color = if (action.enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
        val font = Theme.FONT_MEDIUM
        val text = ui.trim(I18n.text(action.label, context), bounds.width - (if (action.checked) 48f else 32f) * scale,
            fontSize, font)
        ui.text(text, bounds.x + 7f * scale, bounds.y + (bounds.height - fontSize) / 2f, fontSize, color, font)
        val centerY = bounds.y + bounds.height / 2f
        PlayerIcons.draw(ui.ctx, action.icon, bounds.right - 13f * scale, centerY, (if (hovered) 14f else 12.5f) * scale,
            if (action.highlighted && action.enabled) APPLE_CONTROL_BLUE else color)
        if (action.checked) PlayerIcons.draw(ui.ctx, PlayerIcons.check, bounds.right - 31f * scale,
            centerY, 10f * scale, APPLE_CONTROL_BLUE)
        if (action.enabled) ui.hits += bounds to action.run
    }

    private fun drawCredits(
        frame: AppleLyricsLayout,
        song: NeteaseTrack,
        metadata: AppleMusicSource.SongDetails?,
        complete: Boolean,
    ) {
        val scale = frame.scale
        val panel = frame.bounds(932f, 479f, 600f, 334f)
        ui.card(frame.viewport, Color(0f, 0f, 0f, 0.38f), 0f)
        drawPanel(panel, 14f * scale, scale)
        val font = Theme.FONT_SEMIBOLD
        val x = panel.x + 28f * scale
        val close = frame.bounds(1474f, 491f, 44f, 44f)
        ui.text(I18n.text("View Credits"), x, panel.y + 24f * scale, 22f * scale, Theme.TEXT_PRIMARY, font)
        PlayerIcons.draw(ui.ctx, AppleLyricsIcons.close, close.x + close.width / 2f,
            close.y + close.height / 2f, (if (ui.hover(close)) 15f else 13.5f) * scale, Theme.TEXT_PRIMARY)
        ui.hits += close to { dismiss() }
        ui.hits += panel to {}
        ui.text(ui.trim(song.name, 540f * scale, 17f * scale, font), x, panel.y + 72f * scale, 17f * scale, Theme.TEXT_PRIMARY, font)

        val rows = buildList {
            val artist = metadata?.artist.orEmpty().ifBlank { song.artists.joinToString(" / ") }
            if (artist.isNotBlank()) add(I18n.text("Performing Artist") to artist)
            val composer = metadata?.composer.orEmpty()
            if (composer.isNotBlank()) add(I18n.text("Composer") to composer)
        }
        var y = panel.y + 113f * scale
        for ((label, value) in rows) {
            ui.text(label, x, y, 14f * scale, Theme.TEXT_SECONDARY)
            ui.text(ui.trim(value, 540f * scale, 16f * scale), x, y + 24f * scale, 16f * scale, Theme.TEXT_PRIMARY)
            y += 65f * scale
        }
        val message = when {
            !complete -> I18n.text("Loading...")
            metadata?.composer.isNullOrBlank() -> I18n.text("Additional Apple Music credits unavailable")
            else -> ""
        }
        if (message.isNotEmpty()) ui.text(message, x, y, 13f * scale, Theme.TEXT_SECONDARY)
    }

    private fun drawPanel(bounds: LayoutBounds, radius: Float, scale: Float) {
        ui.ctx.drawGlass(bounds, Theme.GLASS_SIDEBAR.copy(radius = radius, maxLuma = 0.17f,
            bevel = 6f * scale, refraction = 4f * scale))
        ui.ctx.drawShape(bounds, ShapeStyle(radius = radius, borderWidth = scale,
            borderColor = Color(1f, 1f, 1f, 0.19f)))
    }

    private companion object {
        val SAVE = VectorIcon.path {
            move(3.0, 5.0); line(16.0, 5.0)
            move(3.0, 10.0); line(13.0, 10.0)
            move(3.0, 15.0); line(10.0, 15.0)
            move(17.0, 12.0); line(17.0, 22.0)
            move(12.0, 17.0); line(22.0, 17.0)
        }
        val QUALITY = VectorIcon.path {
            move(4.0, 5.0); line(20.0, 5.0)
            move(4.0, 12.0); line(20.0, 12.0)
            move(4.0, 19.0); line(20.0, 19.0)
            move(8.0, 2.5); line(8.0, 7.5)
            move(15.0, 9.5); line(15.0, 14.5)
            move(10.0, 16.5); line(10.0, 21.5)
        }
        const val MENU_SCALE = 0.85f
        const val MENU_WIDTH = 186f
        const val ROW_HEIGHT = 24f
        val MENU_GLASS = Theme.GLASS_SIDEBAR.copy(
            radius = 5f, tint = Color(0.09f, 0.09f, 0.12f, 0.24f), maxLuma = 0.23f,
            saturation = 1.12f, frost = 0.64f, refraction = 3f, bevel = 3f, highlight = 0.65f,
            shadow = ShadowStyle(Color(0f, 0f, 0f, 0.25f), 10f, Vec2(0f, 2f), outside = true),
        )
        val PLAY_NEXT = VectorIcon.path {
            move(3.0, 4.0); line(9.0, 8.0); line(3.0, 12.0); close()
            move(13.0, 8.0); line(21.0, 8.0)
            move(3.0, 16.0); line(21.0, 16.0)
            move(3.0, 21.0); line(21.0, 21.0)
        }
        val PLAY_LAST = VectorIcon.path {
            move(3.0, 4.0); line(21.0, 4.0)
            move(3.0, 9.0); line(21.0, 9.0)
            move(3.0, 13.0); line(9.0, 17.0); line(3.0, 21.0); close()
            move(13.0, 17.0); line(21.0, 17.0)
        }
        val STAR = VectorIcon.path {
            move(12.0, 2.5); line(15.0, 8.7); line(21.8, 9.7)
            line(16.9, 14.5); line(18.0, 21.5); line(12.0, 18.2)
            line(6.0, 21.5); line(7.1, 14.5); line(2.2, 9.7); line(9.0, 8.7); close()
        }
        val INFO = VectorIcon.path {
            circle(12.0, 12.0, 9.0)
            move(12.0, 10.0); line(12.0, 16.0)
            circle(12.0, 7.0, 0.45)
        }
        val LINK = VectorIcon.path {
            move(9.0, 9.0); line(13.0, 5.0); curve(17.0, 1.0, 23.0, 7.0, 19.0, 11.0); line(15.0, 15.0)
            move(15.0, 15.0); line(11.0, 19.0); curve(7.0, 23.0, 1.0, 17.0, 5.0, 13.0); line(9.0, 9.0)
            move(9.0, 15.0); line(15.0, 9.0)
        }
    }
}
