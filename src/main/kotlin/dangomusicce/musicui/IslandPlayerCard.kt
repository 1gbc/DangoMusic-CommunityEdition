package dangomusicce.musicui

import dangomusicce.client.ClientMusic
import dangomusicce.font.MixedText
import dangomusicce.gfx.GuiCanvas
import dangomusicce.i18n.I18n
import dangomusicce.lyrics.LyricLine
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.music.CoverCache
import dangomusicce.music.MusicPlayer
import dangomusicce.music.PlaybackView
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.Vec2
import dangomusicce.utils.Color
import kotlin.math.abs
import kotlin.math.exp

internal class IslandPlayerCard {
    private enum class Control { PREVIOUS, PLAY_PAUSE, NEXT, VOLUME, PROGRESS }

    private class Layout(val card: LayoutBounds, rows: Int) {
        private val coverSize = (card.height - 14f).coerceIn(0f, 57f)
        val cover = LayoutBounds(card.x + 7f, card.y + (card.height - coverSize) / 2f, coverSize, coverSize)
        private val textX = cover.right + 9f
        private val controlX = card.right - 7f - BUTTON_SIZE * 4f
        private val controlY = card.y + (card.height - BUTTON_SIZE - 8f) / 2f
        private val textWidth = (controlX - 6f - textX).coerceAtLeast(0f)
        val progress = LayoutBounds(textX, card.bottom - 9f, (card.right - 10f - textX).coerceAtLeast(0f), 2.5f)
        val progressHit = progress.expand(0f, 4f)
        val title = LayoutBounds(textX, card.y + 5f, textWidth, ROW_HEIGHT)
        val lyrics = LayoutBounds(textX, title.bottom + 2f, textWidth,
            (progressHit.y - title.bottom - 2f).coerceIn(0f, rows * ROW_HEIGHT))

        fun button(control: Control) = LayoutBounds(
            controlX + control.ordinal * BUTTON_SIZE, controlY, BUTTON_SIZE, BUTTON_SIZE,
        )

        fun controlAt(x: Float, y: Float): Control? {
            if (!card.has(x, y)) return null
            if (progressHit.has(x, y)) return Control.PROGRESS
            return buttons.firstOrNull { button(it).has(x, y) }
        }
    }

    private data class Track(val key: String, val remote: Boolean) {
        fun matches(snap: PlaybackView.Snapshot) = snap.track?.key == key && snap.remote == remote
    }

    private data class Frame(val layout: Layout, val scale: Float, val track: Track) {
        fun contains(x: Int, y: Int) = layout.card.has(x / scale, y / scale)
        fun target(x: Int, y: Int) = layout.controlAt(x / scale, y / scale)
        fun fraction(x: Int) = ((x / scale - layout.progress.x) / layout.progress.width).coerceIn(0f, 1f)
    }

    private data class Press(val control: Control, val track: Track) {
        var progress: Float? = null
    }

    private var frame: Frame? = null
    private var pointer: Vec2? = null
    private var press: Press? = null
    private val buttonHover = FloatArray(buttons.size)
    private val buttonDepth = FloatArray(buttons.size)
    private val speaker = PlayerIcons.VolumeSpeaker()
    private var lastVolume = MusicPlayer.DEFAULT_VOLUME
    private var track: Track? = null
    private var title: String? = null
    private var titleSince = 0L
    private var songProgress = 0f
    private var lyrics: List<LyricLine>? = null
    private var lyricIndex = -1
    private var lyricSince = 0L
    private var lyricShift = 0f

    fun pointerAt(x: Int, y: Int, available: Boolean) {
        pointer = if (available) Vec2(x.toFloat(), y.toFloat()) else null
    }

    fun clearPointer() {
        pointer = null
        press = null
        buttonHover.fill(0f)
        buttonDepth.fill(0f)
    }

    fun hide() {
        frame = null
        clearPointer()
        track = null
        lyrics = null
        lyricIndex = -1
        lyricShift = 0f
    }

    fun contains(x: Int, y: Int) = frame?.contains(x, y) == true

    fun canStartDrag(x: Int, y: Int): Boolean {
        val current = frame ?: return false
        val px = x / current.scale
        val py = y / current.scale
        return current.layout.card.has(px, py) && current.target(x, y) == null &&
            !current.layout.title.has(px, py) && !current.layout.lyrics.has(px, py)
    }

    fun mouseDown(x: Int, y: Int): Boolean {
        val current = frame ?: return false
        val target = current.target(x, y) ?: return false
        val snap = PlaybackView.snapshot()
        if (current.track.matches(snap) && enabled(target, snap)) {
            press = Press(target, current.track).also {
                if (target == Control.PROGRESS) it.progress = current.fraction(x)
            }
        }
        return true
    }

    fun mouseDrag(x: Int) {
        val active = press ?: return
        val current = frame ?: return
        if (active.progress != null && active.track == current.track) active.progress = current.fraction(x)
    }

    fun mouseUp(x: Int, y: Int, available: Boolean) {
        val active = press ?: return
        press = null
        val current = frame ?: return
        if (!available || current.track != active.track) return
        val snap = PlaybackView.snapshot()
        if (!active.track.matches(snap) || !enabled(active.control, snap)) return
        if (active.control == Control.PROGRESS) {
            val fraction = current.fraction(x)
            songProgress = fraction
            ClientMusic.control {
                val latest = PlaybackView.snapshot()
                if (active.track.matches(latest) && enabled(Control.PROGRESS, latest)) {
                    PlaybackView.seek((latest.durationMs * fraction.toDouble()).toLong())
                }
            }
        } else if (current.target(x, y) == active.control) {
            if (active.control == Control.VOLUME) {
                val volume = MusicPlayer.volume
                applyVolume(if (volume > 0f) 0f else lastVolume)
                return
            }
            ClientMusic.control {
                val latest = PlaybackView.snapshot()
                if (active.track.matches(latest) && enabled(active.control, latest)) {
                    when (active.control) {
                        Control.PREVIOUS -> PlaybackView.previous()
                        Control.PLAY_PAUSE -> PlaybackView.togglePause()
                        Control.NEXT -> PlaybackView.next()
                        else -> Unit
                    }
                }
            }
        }
    }

    fun scroll(x: Int, y: Int, wheel: Int) {
        val current = frame ?: return
        if (current.target(x, y) != Control.VOLUME || !current.track.matches(PlaybackView.snapshot())) return
        val steps = (wheel / 120f).let { if (abs(it) < 1f) Math.signum(it) else it }
        applyVolume(MusicPlayer.volume + 0.05f * steps)
    }

    private fun applyVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        if (volume == 0f && MusicPlayer.volume > 0f) lastVolume = MusicPlayer.volume
        ClientMusic.setVolume(volume)
        PlaybackView.setVolume(volume)
    }

    fun draw(
        ctx: GuiCanvas, mt: MixedText, bounds: LayoutBounds, scale: Float, rows: Int,
        snap: PlaybackView.Snapshot, lyricModule: LyricsModule, now: Long, dt: Float, interactive: Boolean,
    ) {
        val song = snap.track ?: return hide()
        if (bounds.width < 190f || bounds.height < 32f) return hide()
        val layout = Layout(bounds, rows)
        val identity = Track(song.key, snap.remote)
        frame = if (interactive) Frame(layout, scale, identity) else null
        if (!interactive || press?.track != identity) press = null
        val changed = track != identity
        val fraction = if (snap.durationMs <= 0L) 0f else
            (snap.positionMs.toDouble() / snap.durationMs).toFloat().coerceIn(0f, 1f)
        songProgress = if (changed) fraction else approach(songProgress, fraction, dt, 18f)
        track = identity

        val cover = CoverCache.track(song)
        CoverCache.upload()
        val radius = minOf(8f, layout.cover.width / 2f)
        if (cover != null) ctx.drawRoundedImage(layout.cover, cover, radius)
        else ctx.drawShape(layout.cover, ShapeStyle(Theme.TRACK, radius))

        val metadata = listOf(song.name, song.artists.joinToString(" / ")).filter { it.isNotBlank() }
            .joinToString(" · ").replace('\r', ' ').replace('\n', ' ')
        if (changed || title != metadata) {
            title = metadata
            titleSince = now
        }
        drawLine(ctx, mt, metadata, layout.title, layout.title.y + 1f, 11f, Theme.TEXT_PRIMARY,
            Theme.FONT_SEMIBOLD, (now - titleSince) / 1e9)

        val sheet = lyricModule.playerLyrics(song)
        val lines = sheet?.lines
        val index = sheet?.indexAt(snap.positionMs) ?: -1
        if (changed || lyrics !== lines) {
            lyricIndex = -1
            lyricShift = 0f
        }
        lyrics = lines
        drawLyrics(ctx, mt, layout.lyrics, rows, lines, index, lyricModule.isLoadingLyrics(song), now, dt)
        drawProgress(ctx, layout.progress, press?.progress ?: songProgress, enabled(Control.PROGRESS, snap))
        drawControls(ctx, layout, snap, dt)
    }

    private fun drawLyrics(
        ctx: GuiCanvas, mt: MixedText, area: LayoutBounds, rows: Int, lines: List<LyricLine>?,
        index: Int, loading: Boolean, now: Long, dt: Float,
    ) {
        lyricShift = approach(lyricShift, 0f, dt, 15f)
        if (index != lyricIndex) {
            val difference = index - lyricIndex
            lyricShift = if (rows > 1 && index >= 0 && lyricIndex >= 0 && abs(difference) == 1) {
                (lyricShift + difference * ROW_HEIGHT).coerceIn(-ROW_HEIGHT, ROW_HEIGHT)
            } else 0f
            lyricIndex = index
            lyricSince = now
        }
        if (area.height <= 0f || area.width <= 0f) return
        if (lines.isNullOrEmpty()) {
            val label = I18n.text(if (loading) "Loading lyrics..." else "No lyrics available")
            drawLine(ctx, mt, label, area, area.y, 10f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            return
        }
        if (index < 0) return
        if (rows == 1) {
            drawLine(ctx, mt, lines[index].text, area, area.y, 10.5f, Theme.TEXT_PRIMARY,
                Theme.FONT_SEMIBOLD, (now - lyricSince) / 1e9)
            return
        }
        val first = index - if (rows == 3) 1 else 0
        for (i in (first - 1)..(first + rows)) {
            val line = lines.getOrNull(i) ?: continue
            val lineY = area.y + (i - first) * ROW_HEIGHT + lyricShift
            val visible = area.intersect(LayoutBounds(area.x, lineY, area.width, ROW_HEIGHT))
            val focus = (1f - abs(i - index + lyricShift / ROW_HEIGHT)).coerceIn(0f, 1f)
            drawLine(ctx, mt, line.text, visible, lineY, 10.5f, Theme.TEXT_PRIMARY.copy(a = 0.43f + 0.57f * focus),
                if (i == index) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
        }
    }

    private fun drawLine(
        ctx: GuiCanvas, mt: MixedText, text: String, bounds: LayoutBounds, y: Float,
        size: Float, color: Color, font: String, elapsed: Double = 0.0,
    ) {
        if (text.isBlank() || bounds.width <= 0f || bounds.height <= 0f) return
        val overflow = (mt.width(text, size, font) - bounds.width).coerceAtLeast(0f)
        val offset = marquee(overflow, elapsed)
        val fade = minOf(9f, bounds.width / 3f)
        val leftFade = minOf(fade, offset)
        val rightFade = minOf(fade, overflow - offset)
        val previousClip = mt.clip

        fun part(left: Float, right: Float, alpha: Float) {
            val segment = LayoutBounds(left, bounds.y, right - left, bounds.height)
            val clip = previousClip?.intersect(segment) ?: segment
            if (clip.width <= 0f || clip.height <= 0f) return
            ctx.pushClip(clip)
            mt.clip = clip
            try {
                mt.text(ctx, text, bounds.x - offset, y, size, color.copy(a = color.a * alpha), font)
            } finally {
                mt.clip = previousClip
                ctx.popClip()
            }
        }

        fun edge(start: Float, length: Float, rising: Boolean) {
            if (length <= 0f) return
            for (step in 0 until 4) {
                val alpha = (step + 0.5f) / 4f
                part(start + length * step / 4f, start + length * (step + 1) / 4f, if (rising) alpha else 1f - alpha)
            }
        }

        edge(bounds.x, leftFade, true)
        part(bounds.x + leftFade, bounds.right - rightFade, 1f)
        edge(bounds.right - rightFade, rightFade, false)
    }

    private fun drawProgress(ctx: GuiCanvas, bar: LayoutBounds, fraction: Float, seekable: Boolean) {
        val r = bar.height / 2f
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, r))
        val fill = bar.width * fraction
        if (fill > 0f) ctx.drawShape(bar.copy(width = fill), ShapeStyle(Theme.TEXT_PRIMARY, minOf(r, fill / 2f)))
        val hover = pointer?.let { frame?.target(it.x.toInt(), it.y.toInt()) } == Control.PROGRESS
        if (seekable && (press?.progress != null || hover)) {
            ctx.drawShape(LayoutBounds(bar.x + fill - 2.5f, bar.y + r - 2.5f, 5f, 5f),
                ShapeStyle(Theme.TEXT_PRIMARY, 2.5f))
        }
    }

    private fun drawControls(ctx: GuiCanvas, layout: Layout, snap: PlaybackView.Snapshot, dt: Float) {
        val hover = pointer?.let { frame?.target(it.x.toInt(), it.y.toInt()) }
        for (control in buttons) {
            val i = control.ordinal
            val hit = layout.button(control)
            val enabled = enabled(control, snap)
            buttonHover[i] = approach(buttonHover[i], if (enabled && hover == control) 1f else 0f, dt, 22f)
            buttonDepth[i] = approach(buttonDepth[i], if (press?.control == control && hover == control) 1f else 0f, dt, 31f)
            if (buttonHover[i] > 0.01f) {
                val tint = 0.11f * buttonHover[i] + 0.06f * buttonDepth[i]
                ctx.drawShape(hit.expand(-2f, -2f), ShapeStyle(Theme.TEXT_PRIMARY.copy(a = tint), 8f))
            }
            val x = hit.x + hit.width / 2f
            val y = hit.y + hit.height / 2f
            val color = if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
            val size = (if (control == Control.PLAY_PAUSE) 16f else 14f) * (1f - 0.13f * buttonDepth[i])
            when (control) {
                Control.PREVIOUS -> PlayerIcons.draw(ctx, PlayerIcons.cardPrevious, x, y, size, color)
                Control.PLAY_PAUSE -> PlayerIcons.draw(ctx, if (snap.playing) PlayerIcons.cardPause else PlayerIcons.cardPlay, x, y, size, color)
                Control.NEXT -> PlayerIcons.draw(ctx, PlayerIcons.cardNext, x, y, size, color)
                Control.VOLUME -> speaker.draw(ctx, x, y, MusicPlayer.volume, color, size / 24f, dt)
                Control.PROGRESS -> Unit
            }
        }
    }

    companion object {
        const val WIDTH = 332f
        const val HEIGHT = 47f
        private const val ROW_HEIGHT = 12f
        private const val BUTTON_SIZE = 22f
        private val buttons = Control.entries.filter { it != Control.PROGRESS }

        fun height(rows: Int) = HEIGHT + (rows - 1) * ROW_HEIGHT

        private fun LayoutBounds.has(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

        private fun approach(value: Float, target: Float, dt: Float, rate: Float) =
            value + (target - value) * (1f - exp(-rate * dt))

        private fun enabled(control: Control, snap: PlaybackView.Snapshot): Boolean = when (control) {
            Control.PREVIOUS, Control.NEXT -> !snap.remote
            Control.PROGRESS -> !snap.remote && !snap.loading && snap.durationMs > 0L
            else -> true
        }

        private fun marquee(overflow: Float, elapsed: Double): Float {
            if (overflow <= 0f) return 0f
            val travel = overflow / 18.0
            val leg = travel + 1.2
            val phase = elapsed.coerceAtLeast(0.0) % (2 * leg)
            return if (phase < leg) ((phase - 1.2) * 18.0).toFloat().coerceIn(0f, overflow)
            else (overflow - ((phase - leg - 1.2) * 18.0).toFloat().coerceIn(0f, overflow))
        }
    }
}
