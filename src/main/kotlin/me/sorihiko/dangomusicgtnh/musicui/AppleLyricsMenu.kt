package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.music.AppleMusicSource
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color
import java.util.concurrent.CompletableFuture

internal class AppleLyricsMenu(private val ui: Ui) {
    private var trackKey: String? = null
    private var menuOpen = false
    private var creditsOpen = false
    private var copyPending = false
    private var details: CompletableFuture<AppleMusicSource.SongDetails>? = null
    private var notice = TimedNotice("")

    val capturesInput get() = menuOpen || creditsOpen

    fun toggle() {
        menuOpen = !menuOpen
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

    fun draw(frame: AppleLyricsLayout, track: NeteaseTrack?) {
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
            ui.text(notice.text, frame.artwork.x, frame.y(1073f), 13f * frame.scale,
                Theme.TEXT_SECONDARY.copy(a = alpha))
        }
        if (song == null) return

        val hitStart = ui.hits.size
        if (menuOpen) drawMenu(frame, song)
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

    private fun drawMenu(frame: AppleLyricsLayout, song: NeteaseTrack) {
        val scale = frame.scale
        val panel = frame.bounds(984f, 908f, 185f, 64f)
        drawPanel(panel, 6f * scale, scale)
        val credits = panel.copy(height = 32f * scale)
        val copy = credits.copy(y = credits.bottom)
        row(credits, I18n.text("View Credits"), INFO, scale) {
            menuOpen = false
            creditsOpen = true
            load(song)
        }
        ui.card(LayoutBounds(panel.x, credits.bottom, panel.width, scale), Theme.TEXT_PRIMARY.copy(a = 0.11f), 0f)
        row(copy, I18n.text("Copy Link"), LINK, scale) {
            menuOpen = false
            copyPending = true
            load(song)
        }
    }

    private fun row(bounds: LayoutBounds, text: String, icon: VectorIcon, scale: Float, action: () -> Unit) {
        if (ui.hover(bounds)) ui.card(bounds, Color(1f, 1f, 1f, 0.10f), 5f * scale)
        val fontSize = 13f * scale
        ui.text(text, bounds.x + 10f * scale, bounds.y + 9f * scale, fontSize, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        PlayerIcons.draw(ui.ctx, icon, bounds.right - 17f * scale, bounds.y + bounds.height / 2f,
            17f * scale, Theme.TEXT_PRIMARY)
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
        ui.icon("×", close.x + close.width / 2f, close.y + close.height / 2f, 18f * scale, Theme.TEXT_PRIMARY)
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
