package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.music.AppleMusicSource
import me.sorihiko.dangomusicgtnh.music.AppleWebPlayer
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color
import java.util.concurrent.CompletableFuture

internal class AppleLyricsMenu(
    private val ui: Ui,
    private val romanization: BooleanValue,
    private val translation: BooleanValue,
) {
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

    val capturesInput get() = menuOpen || creditsOpen

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
        return true
    }

    fun reset() {
        dismiss()
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
        if (creditsOpen) drawCredits(frame, song, metadata, complete)
        prioritizeHits(frame.viewport, hitStart)
    }

    fun drawNetease(viewport: LayoutBounds, anchor: LayoutBounds, openQuality: (LayoutBounds) -> Unit) {
        if (!menuOpen) return
        val hitStart = ui.hits.size
        drawMenu(viewport, listOf(
            Action("Quality", QUALITY) {
                menuOpen = false
                openQuality(anchor)
            },
            Action("Stop playback", PlayerIcons.stop, MusicPlayer.current?.source == MusicSource.NETEASE) {
                menuOpen = false
                ClientMusic.control { if (MusicPlayer.current?.source == MusicSource.NETEASE) Library.stopPlayback() }
            },
        ), "NetEase Player Menu")
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
            creditsAction(song), linkAction(song),
        )
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
