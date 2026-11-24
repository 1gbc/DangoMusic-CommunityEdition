package dangomusicce.musicui

import dangomusicce.client.ClientMusic
import dangomusicce.i18n.I18n
import dangomusicce.music.AppleWebPlayer
import dangomusicce.music.CoverCache
import dangomusicce.music.MusicPlayer
import dangomusicce.music.MusicSource
import dangomusicce.music.NeteaseTrack
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.Theme
import dangomusicce.ui.VectorIcon

internal class AppleQueuePanel(private val ui: Ui) {
    val queueDrag = QueueDrag()
    var open = false
        private set
    private var bounds = LayoutBounds()
    private var offset = 0f
    private var maxOffset = 0f
    private var selectedSlot = -1
    private var selectedQueue: List<NeteaseTrack>? = null

    fun toggle() {
        if (!close()) open = true
    }

    fun close(): Boolean {
        val wasOpen = open
        open = false
        queueDrag.cancel()
        bounds = LayoutBounds()
        offset = 0f
        selectedSlot = -1
        selectedQueue = null
        return wasOpen
    }

    fun contains(x: Int, y: Int) = open && bounds.contains(x, y)

    fun scroll(wheel: Int, x: Int, y: Int): Boolean {
        if (!contains(x, y)) return false
        queueDrag.resetClick()
        offset = (offset + if (wheel > 0) -90f else 90f).coerceIn(0f, maxOffset)
        return true
    }

    fun layoutIn(area: LayoutBounds): LayoutBounds {
        val width = minOf(226f, area.width * 0.38f)
        if (!open || width < 96f || area.height < 90f) return LayoutBounds()
        return LayoutBounds(area.right - width, area.y, width, area.height)
    }

    @JvmOverloads
    fun draw(panel: LayoutBounds, netease: Boolean = false) {
        bounds = panel
        if (!open || panel.width <= 0f || panel.height <= 0f) return
        val hits = mutableListOf<Hit>()
        val queue = MusicPlayer.queue
        if (selectedQueue !== queue) {
            selectedQueue = null
            selectedSlot = -1
        }
        val first = if (netease) 0 else MusicPlayer.appleUpcomingStart(queue) ?: queue.size
        val count = queue.size - first
        val controlsReady = !netease && MusicPlayer.current?.source == MusicSource.APPLE &&
            MusicPlayer.state != MusicPlayer.State.LOADING && !AppleWebPlayer.stationActive && !MusicPlayer.appleQueueCleared
        val canRepeat = controlsReady && MusicPlayer.queue.isNotEmpty()
        PopupLook.panel(ui.ctx, panel)
        ui.popup(panel) {
            ui.text(I18n.text(if (netease) "Now Playing" else "Up Next"), panel.x + 14f, panel.y + 13f, 14f,
                Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val repeat = LayoutBounds(panel.right - 33f, panel.y + 7f, 23f, 25f)
            val selecting = canRepeat && MusicPlayer.appleRepeatQueue
            if (!netease) PlayerIcons.draw(ui.ctx, INFINITY, repeat.x + 11.5f, repeat.y + 12.5f,
                if (canRepeat && ui.hover(repeat)) 18f else 16f,
                if (selecting) APPLE_CONTROL_BLUE else if (canRepeat) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)
            if (canRepeat) hits += repeat to { ClientMusic.control(MusicPlayer::toggleAppleQueueRepeat) }
            if (!netease && count > 0) {
                val label = I18n.text("Clear")
                val labelWidth = ui.width(label, 10.5f)
                val clear = LayoutBounds(repeat.x - labelWidth - 15f, repeat.y, labelWidth + 12f, repeat.height)
                val size = if (controlsReady && ui.hover(clear)) 11.4f else 10.5f
                ui.text(label, clear.x + (clear.width - ui.width(label, size)) / 2f,
                    clear.y + (clear.height - size) / 2f, size, if (controlsReady) RED else Theme.TEXT_TERTIARY)
                if (controlsReady) hits += clear to { ClientMusic.control(MusicPlayer::clearAppleUpcoming) }
            }
            val list = LayoutBounds(panel.x + 12f, panel.y + 38f, panel.width - 24f, panel.height - 50f)
            if (count == 0) {
                maxOffset = 0f
                offset = 0f
                val empty = I18n.text("No songs up next.")
                ui.text(empty, panel.x + (panel.width - ui.width(empty, 12f)) / 2f,
                    panel.y + panel.height / 2f - 6f, 12f, Theme.TEXT_SECONDARY)
            } else {
                maxOffset = (count * ROW_HEIGHT - list.height).coerceAtLeast(0f)
                val dragScroll = queueDrag.edgeScroll(ui.frameDt, ui.mx, ui.my) * ROW_HEIGHT
                offset = (offset + dragScroll).coerceIn(0f, maxOffset)
                val canPlay = if (netease) MusicPlayer.current?.source == MusicSource.NETEASE else controlsReady
                drawSongs(list, queue, first, hits, canPlay, netease)
            }
        }
        ui.hits.addAll(0, hits + listOf(panel to {}))
    }

    private fun drawSongs(list: LayoutBounds, queue: List<NeteaseTrack>, first: Int,
        hits: MutableList<Hit>, interactive: Boolean, netease: Boolean) {
        val count = queue.size - first
        val rowArea = list.copy(width = list.width - 5f)
        if (interactive) queueDrag.offer(queue, rowArea, list.y - offset, ROW_HEIGHT, ui, first = first, upcoming = !netease) { slot ->
            ClientMusic.control { MusicPlayer.playQueueSlot(queue, slot, upcomingOnly = !netease) }
        }
        ui.beginClip(list)
        try {
            for (i in ((offset / ROW_HEIGHT).toInt() - 1).coerceAtLeast(0) until count) {
                val y = list.y + i * ROW_HEIGHT - offset
                if (y >= list.bottom + ROW_HEIGHT) break
                if (i == queueDrag.liftedRow) continue
                val row = LayoutBounds(list.x, y + queueDrag.shift(i), rowArea.width, ROW_HEIGHT)
                val visible = row.intersect(list)
                val slot = i + first
                val current = netease && slot == MusicPlayer.index
                if (current || slot == selectedSlot || ui.hover(visible)) ui.card(row, PopupLook.hover, 7f)
                val artwork = artworkBounds(row)
                drawSong(row, artwork, queue[slot], current)
                val select = {
                    selectedQueue = queue
                    selectedSlot = slot
                }
                val coverHit = artwork.intersect(list)
                if (interactive && queueDrag.liftedRow < 0 && ui.hover(coverHit)) {
                    ui.drawTrackCoverControl(artwork, appleCoverRadius(artwork), 14f,
                        current && MusicPlayer.state == MusicPlayer.State.PLAYING)
                    hits += coverHit to {
                        select()
                        ClientMusic.control { MusicPlayer.activateQueueCover(queue, slot, upcomingOnly = !netease) }
                    }
                }
                hits += if (interactive) queueDrag.rowHit(visible, select) else visible to select
            }
            queueDrag.markerY()?.let { y ->
                ui.card(LayoutBounds(list.x + 4f, y, rowArea.width - 8f, 2f), APPLE_CONTROL_BLUE, 1f)
            }
            val lifted = queueDrag.liftedRow
            if (lifted in 0 until count) {
                val row = LayoutBounds(list.x, queueDrag.liftedY(ui.my), rowArea.width, ROW_HEIGHT)
                ui.drawLiftedQueueRow(row, classic = false)
                drawSong(row, artworkBounds(row), queue[first + lifted], netease && first + lifted == MusicPlayer.index)
            }
        } finally {
            ui.endClip()
        }
        if (maxOffset > 0f) {
            val height = (list.height * list.height / (count * ROW_HEIGHT)).coerceIn(12f, list.height)
            ui.card(LayoutBounds(list.right - 3f, list.y + (list.height - height) * offset / maxOffset, 3f, height),
                Theme.TEXT_TERTIARY, 1.5f)
        }
    }

    private fun artworkBounds(row: LayoutBounds) = LayoutBounds(row.x + 4f, row.y + 6f, 30f, 30f)

    private fun drawSong(row: LayoutBounds, artwork: LayoutBounds, song: NeteaseTrack, current: Boolean) {
        ui.drawAppleCover(artwork, CoverCache.track(song))
        val time = if (song.durationMs > 0L) fmtTime(song.durationMs) else "--:--"
        val timeX = row.right - ui.width(time, 11f) - 4f
        val textX = artwork.right + 10f
        val width = (timeX - textX - 10f).coerceAtLeast(0f)
        ui.text(ui.trim(song.name, width, 11f, Theme.FONT_MEDIUM), textX, row.y + 7f,
            11f, if (current) APPLE_CONTROL_BLUE else Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        ui.text(ui.trim(song.artists.joinToString(" / "), width, 10f), textX, row.y + 22f, 10f, Theme.TEXT_SECONDARY)
        ui.text(time, timeX, row.y + 15f, 11f, Theme.TEXT_SECONDARY)
        ui.card(LayoutBounds(row.x + 4f, row.bottom - 1f, row.width - 8f, 1f), PopupLook.divider, 0f)
    }

    private companion object {
        const val ROW_HEIGHT = 42f
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
