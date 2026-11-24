package dangomusicce.musicui

import dangomusicce.client.ClientMusic
import dangomusicce.font.MixedText
import dangomusicce.gfx.GuiCanvas
import dangomusicce.lyrics.LyricLine
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.music.CoverCache
import dangomusicce.music.MusicPlayer
import dangomusicce.music.PlaybackView
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.Vec2
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color
import kotlin.math.abs
import kotlin.math.exp

internal class IslandPlayerCard {
    private enum class Control { PREVIOUS, PLAY_PAUSE, NEXT, VOLUME, PROGRESS, LOCK, VOLUME_SLIDER }

    private class Layout(val card: LayoutBounds, val expansion: Float, viewportHeight: Float) {
        private fun between(compact: Float, full: Float) = compact + (full - compact) * expansion

        private val coverSize = between(22f, 76f)
        val cover = LayoutBounds(card.x + 10f, card.y + (card.height - coverSize) / 2f, coverSize, coverSize)
        private val textX = cover.right + between(15f, 12f)
        private val textWidth = (card.right - 14f - textX).coerceAtLeast(0f)
        val lyrics = LayoutBounds(textX, card.y + between(4f, 5f),
            (textWidth - 20f * expansion).coerceAtLeast(0f), between(18f, 16f))
        val lyricSize = between(10.5f, 9.5f)
        val title = LayoutBounds(textX, card.y + 23f, textWidth, 16f)
        val subtitle = LayoutBounds(textX, title.bottom + 1f, textWidth, 13f)
        // Finish moving the seek bar before fading in the adjacent timestamps.
        private val progressExpansion = (expansion / DETAILS_START).coerceAtMost(1f)
        private val progressY = card.y + 25f + 34.25f * progressExpansion
        private val timeInset = 31f * progressExpansion
        val progress = LayoutBounds(textX + timeInset, progressY,
            (textWidth - timeInset * 2f).coerceAtLeast(0f), 2.5f)
        val elapsedTime = LayoutBounds(textX, progressY - 5.25f, 28f, 13f)
        val durationTime = elapsedTime.copy(x = progress.right + 3f)
        val progressHit = progress.expand(0f, 5f)
        val lock = LayoutBounds(card.right - 32f, card.y + 3f, 23f, 20f)
        private val controlsX = progress.x + (progress.width - BUTTON_SIZE * 4) / 2f
        fun button(control: Control) = LayoutBounds(controlsX + control.ordinal * BUTTON_SIZE, card.y + 68f, BUTTON_SIZE, BUTTON_SIZE)
        private val volume = button(Control.VOLUME)
        val popup = LayoutBounds(
            (volume.x - 55f).coerceIn(card.x, card.right - 126f),
            if (card.bottom + 28f <= viewportHeight) card.bottom + 3f else card.y - 27f,
            126f, 24f,
        )
        val volumeTrack = LayoutBounds(popup.x + 36f, popup.y + popup.height / 2f - 1.5f, popup.width - 61f, 3f)
        val mute = LayoutBounds(popup.right - 23f, popup.y, 21f, popup.height)
        val volumeBridge = LayoutBounds(popup.x, minOf(popup.y, volume.y), popup.width,
            maxOf(popup.bottom, volume.bottom) - minOf(popup.y, volume.y))

        fun controlAt(x: Float, y: Float, volumeOpen: Boolean): Control? {
            if (expansion < 0.94f) return null
            if (volumeOpen && mute.has(x, y)) return Control.VOLUME
            if (volumeOpen && volumeTrack.expand(4f, 9f).has(x, y)) return Control.VOLUME_SLIDER
            if (!card.has(x, y)) return null
            if (lock.has(x, y)) return Control.LOCK
            if (progressHit.has(x, y)) return Control.PROGRESS
            return buttons.firstOrNull { button(it).has(x, y) }
        }
    }

    private data class Track(val key: String, val remote: Boolean) {
        fun matches(snap: PlaybackView.Snapshot) = snap.track?.key == key && snap.remote == remote
    }

    private data class Frame(val layout: Layout, val scale: Float, val track: Track) {
        fun contains(x: Int, y: Int, volumeOpen: Boolean) = layout.card.has(x / scale, y / scale) ||
            volumeOpen && layout.volumeBridge.has(x / scale, y / scale)
        fun target(x: Int, y: Int, volumeOpen: Boolean) = layout.controlAt(x / scale, y / scale, volumeOpen)
        fun volumeAt(x: Int) = (x / scale - layout.volumeTrack.x) / layout.volumeTrack.width
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
    private var volumeOpen = false

    fun pointerAt(x: Int, y: Int, available: Boolean) {
        pointer = if (available) Vec2(x.toFloat(), y.toFloat()) else null
        val current = frame
        volumeOpen = available && current != null && (press?.control == Control.VOLUME_SLIDER ||
            current.target(x, y, false) == Control.VOLUME ||
            volumeOpen && current.layout.volumeBridge.has(x / current.scale, y / current.scale))
    }

    fun clearPointer() {
        pointer = null
        press = null
        volumeOpen = false
        buttonHover.fill(0f)
        buttonDepth.fill(0f)
    }

    fun hide() {
        frame = null
        clearPointer()
        track = null
        lyrics = null
        lyricIndex = -1
    }

    fun contains(x: Int, y: Int) = frame?.contains(x, y, volumeOpen) == true

    fun canStartDrag(x: Int, y: Int): Boolean {
        val current = frame ?: return false
        val px = x / current.scale
        val py = y / current.scale
        return current.layout.card.has(px, py) && current.target(x, y, volumeOpen) == null &&
            !current.layout.title.has(px, py) && !current.layout.lyrics.has(px, py)
    }

    fun mouseDown(x: Int, y: Int): Boolean {
        val current = frame ?: return false
        val target = current.target(x, y, volumeOpen) ?: return false
        val snap = PlaybackView.snapshot()
        if (current.track.matches(snap) && enabled(target, snap)) {
            press = Press(target, current.track).also {
                if (target == Control.PROGRESS) it.progress = current.fraction(x)
                if (target == Control.VOLUME_SLIDER) applyVolume(current.volumeAt(x))
            }
        }
        return true
    }

    fun mouseDrag(x: Int) {
        val active = press ?: return
        val current = frame ?: return
        if (active.control == Control.VOLUME_SLIDER && active.track == current.track) applyVolume(current.volumeAt(x))
        if (active.progress != null && active.track == current.track) active.progress = current.fraction(x)
    }

    fun mouseUp(x: Int, y: Int, available: Boolean, toggleLock: () -> Unit) {
        val active = press ?: return
        press = null
        val current = frame ?: return
        if (!available || current.track != active.track) return
        val snap = PlaybackView.snapshot()
        if (!active.track.matches(snap) || !enabled(active.control, snap)) return
        if (active.control == Control.VOLUME_SLIDER) {
            applyVolume(current.volumeAt(x))
            return
        }
        if (active.control == Control.LOCK && current.target(x, y, volumeOpen) == Control.LOCK) {
            toggleLock()
            return
        }
        if (active.control == Control.PROGRESS) {
            val fraction = current.fraction(x)
            songProgress = fraction
            ClientMusic.control {
                val latest = PlaybackView.snapshot()
                if (active.track.matches(latest) && enabled(Control.PROGRESS, latest)) {
                    PlaybackView.seek((latest.durationMs * fraction.toDouble()).toLong())
                }
            }
        } else if (current.target(x, y, volumeOpen) == active.control) {
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
        val target = current.target(x, y, volumeOpen)
        if ((target != Control.VOLUME && target != Control.VOLUME_SLIDER) || !current.track.matches(PlaybackView.snapshot())) return
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
        ctx: GuiCanvas, mt: MixedText, bounds: LayoutBounds, scale: Float, expanded: Boolean, viewportHeight: Float,
        snap: PlaybackView.Snapshot, lyricModule: LyricsModule, now: Long, dt: Float, locked: Boolean,
    ) {
        val song = snap.track ?: return hide()
        if (bounds.width < 126f || bounds.height < 29f) return hide()
        val expansion = ((bounds.height - COMPACT_HEIGHT) / (HEIGHT - COMPACT_HEIGHT)).coerceIn(0f, 1f)
        val interactive = expanded && expansion >= 0.94f
        val layout = Layout(bounds, expansion, viewportHeight)
        val details = ((expansion - DETAILS_START) / (1f - DETAILS_START)).coerceIn(0f, 1f)
        val controls = ((expansion - 0.90f) / 0.09f).coerceIn(0f, 1f)
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
        val radius = layout.cover.width * CoverCache.DEFAULT_CORNER_RATIO
        if (cover != null) ctx.drawRoundedImage(layout.cover, cover, radius)
        else ctx.drawShape(layout.cover, ShapeStyle(Theme.TRACK, radius))

        val sheet = lyricModule.playerLyrics(song)
        val lines = sheet?.lines
        val index = sheet?.indexAt(snap.positionMs) ?: -1
        if (changed || lyrics !== lines || index != lyricIndex) {
            lyricIndex = index
            lyricSince = now
        }
        lyrics = lines
        val line = lines?.getOrNull(index)
        if (changed || title != song.name) {
            title = song.name
            titleSince = now
        }
        if (details > 0f) {
            ctx.pushOpacity(details)
            if (line != null) {
                drawLine(ctx, mt, song.name, layout.title, 10f, Theme.TEXT_PRIMARY,
                    Theme.FONT_SEMIBOLD, (now - titleSince) / 1e9)
            }
            val credits = (song.artists + listOfNotNull(song.albumName)).filter(String::isNotBlank).joinToString(" · ")
            drawLine(ctx, mt, credits, layout.subtitle,
                8.5f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            ctx.popOpacity()
        }
        drawLine(ctx, mt, lyricLabel(song.name, line), layout.lyrics, layout.lyricSize, Theme.TEXT_PRIMARY,
            Theme.FONT_SEMIBOLD, (now - lyricSince) / 1e9)
        drawProgress(ctx, layout.progress, press?.progress ?: songProgress, interactive && enabled(Control.PROGRESS, snap))
        if (details > 0f) {
            ctx.pushOpacity(details)
            val shownPosition = press?.progress?.let { (snap.durationMs * it.toDouble()).toLong() } ?: snap.positionMs
            drawLine(ctx, mt, clock(shownPosition), layout.elapsedTime, 8f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            drawLine(ctx, mt, clock(snap.durationMs), layout.durationTime, 8f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            ctx.popOpacity()
        }
        if (controls > 0f) {
            ctx.pushOpacity(controls)
            drawControls(ctx, layout, snap, dt)
            if (interactive && pointer != null) drawIslandLock(ctx, layout.lock, locked, hovered(Control.LOCK))
            ctx.popOpacity()
        }
    }

    private fun hovered(control: Control): Boolean =
        pointer?.let { frame?.target(it.x.toInt(), it.y.toInt(), volumeOpen) } == control

    private fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    fun drawVolumePopup(ctx: GuiCanvas) {
        if (!volumeOpen) return
        val layout = frame?.layout ?: return
        PopupLook.panel(ctx, layout.popup)
        val volume = MusicPlayer.volume.coerceIn(0f, 1f)
        drawHorizontalVolumeTrack(ctx, layout.volumeTrack, volume)
        val centerY = layout.popup.y + layout.popup.height / 2f
        val label = PlayerIcons.volumePercent(volume).toString() + "%"
        Theme.drawText(ctx, label, layout.popup.x + 7f, centerY, 9f, Theme.FONT_SEMIBOLD, Theme.TEXT_PRIMARY)
        PlayerIcons.speaker(ctx, layout.mute.x + layout.mute.width / 2f, centerY, Theme.TEXT_PRIMARY, 0.60f)
    }

    private fun drawLine(
        ctx: GuiCanvas, mt: MixedText, text: String, bounds: LayoutBounds,
        size: Float, color: Color, font: String, elapsed: Double = 0.0,
    ) {
        if (text.isBlank() || bounds.width <= 0f || bounds.height <= 0f) return
        val overflow = (mt.width(text, size, font) - bounds.width).coerceAtLeast(0f)
        val offset = marquee(overflow, elapsed)
        val previousClip = mt.clip
        val clip = previousClip?.intersect(bounds) ?: bounds
        if (clip.width <= 0f || clip.height <= 0f) return

        // Use one scissor for the whole scrolling line. Adjacent fractional scissors
        // can round onto the same screen column and draw the shadow there twice.
        ctx.pushClip(clip)
        mt.clip = clip
        try {
            val y = bounds.y + bounds.height / 2f - size * 0.39f
            mt.text(ctx, text, bounds.x - offset, y, size, color, font)
        } finally {
            mt.clip = previousClip
            ctx.popClip()
        }
    }

    private fun drawProgress(ctx: GuiCanvas, bar: LayoutBounds, fraction: Float, seekable: Boolean) {
        val r = bar.height / 2f
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, r))
        val fill = bar.width * fraction
        if (fill > 0f) ctx.drawShape(bar.copy(width = fill), ShapeStyle(Theme.TEXT_PRIMARY, minOf(r, fill / 2f)))
        val hover = pointer?.let { frame?.target(it.x.toInt(), it.y.toInt(), volumeOpen) } == Control.PROGRESS
        if (seekable && (press?.progress != null || hover)) {
            ctx.drawShape(LayoutBounds(bar.x + fill - 2.5f, bar.y + r - 2.5f, 5f, 5f),
                ShapeStyle(Theme.TEXT_PRIMARY, 2.5f))
        }
    }

    private fun drawControls(ctx: GuiCanvas, layout: Layout, snap: PlaybackView.Snapshot, dt: Float) {
        val hover = pointer?.let { frame?.target(it.x.toInt(), it.y.toInt(), volumeOpen) }
        for (control in buttons) {
            val i = control.ordinal
            val hit = layout.button(control)
            val enabled = enabled(control, snap)
            buttonHover[i] = approach(buttonHover[i], if (enabled && hover == control) 1f else 0f, dt, 22f)
            buttonDepth[i] = approach(buttonDepth[i], if (press?.control == control && hover == control) 1f else 0f, dt, 31f)
            if (buttonHover[i] > 0.01f) {
                val tint = 0.11f * buttonHover[i] + 0.06f * buttonDepth[i]
                ctx.drawShape(hit.scaled(0.8f).expand(-2f, -2f), ShapeStyle(Theme.TEXT_PRIMARY.copy(a = tint), 8f))
            }
            val x = hit.x + hit.width / 2f
            val y = hit.y + hit.height / 2f
            val color = if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
            val size = (if (control == Control.PLAY_PAUSE) 16f else 14f) * 0.8f * (1f - 0.13f * buttonDepth[i])
            when (control) {
                Control.PREVIOUS -> PlayerIcons.draw(ctx, PlayerIcons.cardPrevious, x, y, size, color)
                Control.PLAY_PAUSE -> PlayerIcons.draw(ctx, if (snap.playing) PlayerIcons.cardPause else PlayerIcons.cardPlay, x, y, size, color)
                Control.NEXT -> PlayerIcons.draw(ctx, PlayerIcons.cardNext, x, y, size, color)
                Control.VOLUME -> speaker.draw(ctx, x, y, MusicPlayer.volume, color, size / 24f, dt)
                else -> Unit
            }
        }
    }

    companion object {
        const val WIDTH = 306f
        const val COMPACT_WIDTH = 218f
        const val HEIGHT = 94f
        const val COMPACT_HEIGHT = 34f
        private const val DETAILS_START = 0.8f

        private fun lyricLabel(title: String, line: LyricLine?) =
            (line?.text ?: title).replace('\r', ' ').replace('\n', ' ')

        private const val BUTTON_SIZE = 22f
        private val buttons = listOf(Control.PREVIOUS, Control.PLAY_PAUSE, Control.NEXT, Control.VOLUME)

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

private fun islandLockIcon(locked: Boolean) = VectorIcon.path(strokeWidth = 1.7f) {
    roundRect(5.5, 10.0, 13.0, 10.0, 2.3)
    move(8.25, 10.0)
    line(8.25, 7.0)
    if (locked) {
        curve(8.25, 2.3, 16.0, 2.3, 16.0, 7.0)
        line(16.0, 10.0)
    } else {
        curve(8.25, 2.2, 14.8, 2.2, 17.0, 5.7)
    }
    move(12.0, 14.0)
    line(12.0, 16.0)
}

private val lockedIslandIcon = islandLockIcon(true)
private val unlockedIslandIcon = islandLockIcon(false)

internal fun drawIslandLock(ctx: GuiCanvas, bounds: LayoutBounds, locked: Boolean, hovered: Boolean) {
    if (hovered) ctx.drawShape(bounds, ShapeStyle(Theme.TEXT_PRIMARY.copy(a = 0.17f), 6f))
    PlayerIcons.draw(ctx, if (locked) lockedIslandIcon else unlockedIslandIcon,
        bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f, 14f,
        if (locked) APPLE_CONTROL_BLUE else Theme.TEXT_PRIMARY)
}

internal fun drawHorizontalVolumeTrack(ctx: GuiCanvas, bounds: LayoutBounds, volume: Float) {
    ctx.drawShape(bounds, ShapeStyle(Theme.TEXT_PRIMARY.copy(a = 0.19f), bounds.height / 2f))
    val fill = bounds.width * volume
    if (fill > 0f) {
        ctx.drawShape(bounds.copy(width = fill), ShapeStyle(Theme.TEXT_PRIMARY, minOf(fill, bounds.height) / 2f))
    }
}
