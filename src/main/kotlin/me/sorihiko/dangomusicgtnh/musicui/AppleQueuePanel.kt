package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.music.AppleWebPlayer
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.VectorIcon

internal class AppleQueuePanel(private val ui: Ui) {
    var open = false
        private set
    private var bounds = LayoutBounds()
    private var offset = 0f
    private var maxOffset = 0f

    fun toggle() {
        if (!close()) open = true
    }

    fun close(): Boolean {
        val wasOpen = open
        open = false
        bounds = LayoutBounds()
        offset = 0f
        return wasOpen
    }

    fun contains(x: Int, y: Int) = open && bounds.contains(x, y)

    fun scroll(wheel: Int, x: Int, y: Int): Boolean {
        if (!contains(x, y)) return false
        offset = (offset + if (wheel > 0) -90f else 90f).coerceIn(0f, maxOffset)
        return true
    }

    fun draw(area: LayoutBounds) {
        if (!open) return
        val width = minOf(306f, area.width * 0.60f)
        val panel = LayoutBounds(area.right - width, area.y, width, area.height)
        if (width < 96f || panel.height < 90f) return
        bounds = panel
        val hits = mutableListOf<Hit>()
        val songs = MusicPlayer.appleUpcoming()
        val controlsReady = MusicPlayer.current?.source == MusicSource.APPLE &&
            MusicPlayer.state != MusicPlayer.State.LOADING && !AppleWebPlayer.stationActive && !MusicPlayer.appleQueueCleared
        val canRepeat = controlsReady && MusicPlayer.queue.isNotEmpty()
        PopupLook.panel(ui.ctx, panel)
        ui.popup(panel) {
            ui.text(I18n.text("Up Next"), panel.x + 17f, panel.y + 17f, 16f,
                Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val repeat = LayoutBounds(panel.right - 43f, panel.y + 9f, 30f, 33f)
            val selecting = canRepeat && MusicPlayer.appleRepeatQueue
            if (canRepeat && ui.hover(repeat)) ui.card(repeat, PopupLook.hover, 8f)
            PlayerIcons.draw(ui.ctx, INFINITY, repeat.x + 15f, repeat.y + 16.5f, 21f,
                if (selecting) APPLE_CONTROL_BLUE else if (canRepeat) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)
            if (canRepeat) hits += repeat to { ClientMusic.control(MusicPlayer::toggleAppleQueueRepeat) }
            if (songs.isNotEmpty()) {
                val label = I18n.text("Clear")
                val labelWidth = ui.width(label, 12f)
                val clear = LayoutBounds(repeat.x - labelWidth - 19f, repeat.y, labelWidth + 14f, repeat.height)
                if (controlsReady && ui.hover(clear)) ui.card(clear, PopupLook.hover, 8f)
                ui.text(label, clear.x + 7f, clear.y + 10f, 12f, if (controlsReady) RED else Theme.TEXT_TERTIARY)
                if (controlsReady) hits += clear to { ClientMusic.control(MusicPlayer::clearAppleUpcoming) }
            }
            val list = LayoutBounds(panel.x + 14f, panel.y + 47f, panel.width - 28f, panel.height - 61f)
            if (songs.isEmpty()) {
                maxOffset = 0f
                offset = 0f
                val empty = I18n.text("No songs up next.")
                ui.text(empty, panel.x + (panel.width - ui.width(empty, 12f)) / 2f,
                    panel.y + panel.height / 2f - 6f, 12f, Theme.TEXT_SECONDARY)
            } else {
                maxOffset = (songs.size * ROW_HEIGHT - list.height).coerceAtLeast(0f)
                offset = offset.coerceIn(0f, maxOffset)
                drawSongs(list, songs, hits, controlsReady)
            }
        }
        ui.hits.addAll(0, hits + listOf(panel to {}))
    }

    private fun drawSongs(list: LayoutBounds, songs: List<NeteaseTrack>,
        hits: MutableList<Hit>, interactive: Boolean) {
        ui.beginClip(list)
        try {
            for (i in (offset / ROW_HEIGHT).toInt() until songs.size) {
                val row = LayoutBounds(list.x, list.y + i * ROW_HEIGHT - offset, list.width - 5f, ROW_HEIGHT)
                if (row.y >= list.bottom) break
                val visible = row.intersect(list)
                val song = songs[i]
                if (interactive && ui.hover(visible)) ui.card(row, PopupLook.hover, 7f)
                val artwork = LayoutBounds(row.x + 4f, row.y + 6f, 36f, 36f)
                ui.drawAppleCover(artwork, CoverCache.track(song))
                val time = if (song.durationMs > 0L) fmtTime(song.durationMs) else "--:--"
                val timeX = row.right - ui.width(time, 11f) - 4f
                val textX = artwork.right + 10f
                val width = (timeX - textX - 10f).coerceAtLeast(0f)
                ui.text(ui.trim(song.name, width, 12f, Theme.FONT_MEDIUM), textX, row.y + 8f,
                    12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                ui.text(ui.trim(song.artists.joinToString(" / "), width, 11f), textX, row.y + 26f,
                    11f, Theme.TEXT_SECONDARY)
                ui.text(time, timeX, row.y + 18f, 11f, Theme.TEXT_SECONDARY)
                ui.card(LayoutBounds(row.x + 4f, row.bottom - 1f, row.width - 8f, 1f), PopupLook.divider, 0f)
                if (interactive) hits += visible to { ClientMusic.control { MusicPlayer.playAppleUpcoming(song) } }
            }
        } finally {
            ui.endClip()
        }
        if (maxOffset > 0f) {
            val height = (list.height * list.height / (songs.size * ROW_HEIGHT)).coerceIn(12f, list.height)
            ui.card(LayoutBounds(list.right - 3f, list.y + (list.height - height) * offset / maxOffset, 3f, height),
                Theme.TEXT_TERTIARY, 1.5f)
        }
    }

    private companion object {
        const val ROW_HEIGHT = 48f
        val INFINITY = VectorIcon.path {
            move(12.0, 12.0)
            curve(9.5, 8.7, 8.2, 7.3, 5.9, 7.3)
            curve(0.5, 7.3, 0.5, 16.7, 5.9, 16.7)
            curve(8.2, 16.7, 9.5, 15.3, 12.0, 12.0)
            curve(14.5, 8.7, 15.8, 7.3, 18.1, 7.3)
            curve(23.5, 7.3, 23.5, 16.7, 18.1, 16.7)
            curve(15.8, 16.7, 14.5, 15.3, 12.0, 12.0)
        }
    }
}
