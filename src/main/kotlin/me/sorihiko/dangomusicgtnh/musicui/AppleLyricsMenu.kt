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
        if (menuOpen) {
            if (bottomAnchor == null) drawMenu(frame, song) else drawBottomMenu(frame.viewport, song)
        }
        if (creditsOpen) drawCredits(frame, song, metadata, complete)
        if (capturesInput) {
            ui.hits += frame.viewport to { dismiss() }
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

    private fun menuBounds(viewport: LayoutBounds, width: Float, height: Float): LayoutBounds {
        val gap = 2f
        val padding = 4f
        val left = viewport.x + padding
        val top = viewport.y + padding
        val maxX = (viewport.right - padding - width).coerceAtLeast(left)
        val maxY = (viewport.bottom - padding - height).coerceAtLeast(top)
        val x = if (menuX + gap <= maxX) menuX + gap else menuX - gap - width
        val y = if (menuY + gap <= maxY) menuY + gap else menuY - gap - height
        return LayoutBounds(x.coerceIn(left, maxX), y.coerceIn(top, maxY), width, height)
    }

    private fun drawWhiteMenu(panel: LayoutBounds, scale: Float = 1f) {
        ui.ctx.drawShape(panel, ShapeStyle(MENU_WHITE, 5f * scale, shadow =
            ShadowStyle(Color(0f, 0f, 0f, 0.23f), 10f * scale, Vec2(0f, 2f * scale))))
    }

    private fun drawBottomMenu(viewport: LayoutBounds, song: NeteaseTrack) {
        val width = minOf(186f, viewport.width - 16f)
        val rowHeight = minOf(24f, (viewport.height - 16f) / 5f)
        if (width < 80f || rowHeight < 14f) return
        val panel = menuBounds(viewport, width, rowHeight * 5f + 8f)
        drawWhiteMenu(panel)
        val canQueue = !AppleWebPlayer.stationActive && MusicPlayer.state != MusicPlayer.State.LOADING
        val favorite = Library.favoriteState(song)
        ui.popup(panel) {
            fun item(index: Int, label: String, icon: VectorIcon, enabled: Boolean = true, selected: Boolean = false,
                action: () -> Unit) {
                val box = LayoutBounds(panel.x + 4f, panel.y + 4f + index * rowHeight, panel.width - 8f, rowHeight)
                val hovered = enabled && ui.hover(box)
                if (hovered) ui.card(box, MENU_HOVER, 3f)
                if (index > 0) ui.card(box.copy(height = 1f), MENU_DIVIDER, 0f)
                val color = if (enabled) MENU_TEXT else MENU_TEXT.copy(a = 0.40f)
                ui.text(I18n.text(label, "Apple Player Menu"), box.x + 7f, box.y + (rowHeight - 11f) / 2f,
                    11f, color, Theme.FONT_MEDIUM)
                PlayerIcons.draw(ui.ctx, icon, box.right - 13f, box.y + rowHeight / 2f, if (hovered) 14f else 12.5f,
                    if (selected && enabled) MENU_ACCENT else color)
                if (enabled) ui.hits += box to action
            }
            item(0, "Play Next", PLAY_NEXT, canQueue) {
                ClientMusic.control { MusicPlayer.enqueueApple(song, next = true) }
                menuOpen = false
            }
            item(1, "Play Last", PLAY_LAST, canQueue) {
                ClientMusic.control { MusicPlayer.enqueueApple(song, next = false) }
                menuOpen = false
            }
            item(2, "Personal Favorite", STAR, favorite?.selected != null && !favorite.pending,
                favorite?.selected == true) { Library.toggleAppleFavorite(song) }
            item(3, "View Credits", INFO) {
                menuOpen = false
                creditsOpen = true
                load(song)
            }
            item(4, "Copy Link", LINK) {
                menuOpen = false
                copyPending = true
                load(song)
            }
        }
    }

    private fun drawMenu(frame: AppleLyricsLayout, song: NeteaseTrack) {
        val scale = frame.scale
        val rowHeight = 26f * scale
        val panel = menuBounds(frame.viewport, 169f * scale, 104f * scale)
        drawWhiteMenu(panel, scale)
        fun item(index: Int, label: String, icon: VectorIcon, checked: Boolean = false, action: () -> Unit) {
            val bounds = panel.copy(y = panel.y + index * rowHeight, height = rowHeight)
            if (index > 0) {
                ui.card(LayoutBounds(bounds.x, bounds.y, bounds.width, scale), MENU_DIVIDER, 0f)
            }
            row(bounds, I18n.text(label, "Apple Lyrics Menu"), icon, scale, checked, action)
        }
        item(0, "View Credits", INFO) {
            menuOpen = false
            creditsOpen = true
            load(song)
        }
        item(1, "Copy Link", LINK) {
            menuOpen = false
            copyPending = true
            load(song)
        }
        item(2, "Romanization", AppleLyricsIcons.romanization, romanization.get(), romanization::toggle)
        item(3, "Translation", AppleLyricsIcons.translation, translation.get(), translation::toggle)
    }

    private fun row(
        bounds: LayoutBounds, text: String, icon: VectorIcon, scale: Float, checked: Boolean, action: () -> Unit,
    ) {
        if (ui.hover(bounds)) ui.card(bounds, MENU_HOVER, 3f * scale)
        val fontSize = 11.5f * scale
        ui.text(text, bounds.x + 9f * scale, bounds.y + (bounds.height - fontSize) / 2f, fontSize,
            MENU_TEXT, Theme.FONT_MEDIUM)
        val centerY = bounds.y + bounds.height / 2f
        PlayerIcons.draw(ui.ctx, icon, bounds.right - 15f * scale, centerY, 13.5f * scale, MENU_TEXT)
        if (checked) PlayerIcons.draw(ui.ctx, PlayerIcons.check, bounds.right - 33f * scale,
            centerY, 10f * scale, MENU_ACCENT)
        ui.hits += bounds to action
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
        val MENU_WHITE = Color(1f, 1f, 1f, 0.96f)
        val MENU_TEXT = Color.fromHex("#242329")
        val MENU_ACCENT = Color.fromHex("#0068D4")
        val MENU_HOVER = Color(0f, 0f, 0f, 0.06f)
        val MENU_DIVIDER = Color(0f, 0f, 0f, 0.12f)
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
