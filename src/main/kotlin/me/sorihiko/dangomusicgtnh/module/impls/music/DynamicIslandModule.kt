package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.event.EventHandler
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.font.MixedText
import me.sorihiko.dangomusicgtnh.gfx.GuiCanvas
import me.sorihiko.dangomusicgtnh.gfx.Texture
import me.sorihiko.dangomusicgtnh.module.Category
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleMeta
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.CoverArt
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.musicui.AppleLyricsLayout
import me.sorihiko.dangomusicgtnh.musicui.PlayerIcons
import me.sorihiko.dangomusicgtnh.musicui.fmtTime
import me.sorihiko.dangomusicgtnh.musicui.mixColor
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color

import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.sin

/** 播放 HUD：共用播放进度、封面和尺寸动画，空闲时显示游戏信息。 */
@ModuleMeta(Category.MUSIC, "DynamicIsland")
class DynamicIslandModule : Module() {

    @ModuleValue("Liquid glass", "Liquid glass background; off is a solid black pill")
    private val glass = BooleanValue(true)

    @ModuleValue("Island layout", "Choose how much song information the island shows")
    private val islandLayout = IntValue(0, 1, 1)

    @ModuleValue("Island animations", "Show the playback waveform and volume hotkey feedback")
    private val islandAnimations = BooleanValue(false)

    @ModuleValue("Pos X", "Island horizontal position")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Pos Y", "Island vertical position")
    private val posY = FloatValue(0f, 1f, 0.05f, 3)

    @ModuleValue("Scale", "Island size")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    private enum class Kind(val height: Float, val coverSize: Float) {
        IDLE(32f, 0f),
        FULL(68f, 48f),
        MINI(32f, COMPACT_COVER_SIZE),
    }

    private var context: GuiCanvas? = null

    private var shown = Kind.IDLE
    private var contentAlpha = 0f

    // 弹簧动画（单位：逻辑像素）
    private var w = 0f
    private var h = 0f
    private var vw = 0f
    private var vh = 0f
    private var lastNanos = 0L
    private var volumeUntil = 0L
    private var targetVolume = 0f
    private var animatedVolume = 0f
    private var volumeReveal = 0f
    private var waveActivity = 0f
    private var wavePhase = 0f
    private var waveImage: BufferedImage? = null
    private var waveColors = emptyList<Int>()

    internal fun showVolume(value: Float) {
        if (!enabled || islandLayout.value == 1 || !islandAnimations.get()) return
        targetVolume = value.coerceIn(0f, 1f)
        if (volumeReveal < 0.01f) animatedVolume = targetVolume
        volumeUntil = System.nanoTime() + 2_000_000_000L
    }

    private var dragOffset: Vec2? = null
    private var mouseWasDown = false
    private var lastTrackKey: String? = null
    private var lastTitle: String? = null
    private var titleScrollWidth = 0f
    private var titleScrollStartedAt = 0L

    private val clock = SimpleDateFormat("HH:mm")

    private val mt = MixedText()

    override fun onEnable() {
        w = 0f; h = 0f; vw = 0f; vh = 0f
        shown = Kind.IDLE
        contentAlpha = 0f
        lastNanos = 0L
        volumeUntil = 0L
        volumeReveal = 0f
        waveActivity = 0f
        wavePhase = 0f
        waveImage = null
        waveColors = emptyList()
        dragOffset = null
        mouseWasDown = false
        lastTrackKey = null
        lastTitle = null
        titleScrollWidth = 0f
        titleScrollStartedAt = 0L
    }

    private fun widthOf(kind: Kind, snap: PlaybackView.Snapshot, available: Float, titleSize: Float): Float {
        val title = snap.track?.name.orEmpty()
        val preferred = when {
            kind == Kind.IDLE -> 44f + idleWidth()
            kind == Kind.MINI -> (COMPACT_TITLE_LEFT + COMPACT_END_PADDING +
                titleWidth(title, titleSize)).coerceIn(COMPACT_MIN_WIDTH, COMPACT_MAX_WIDTH)
            UiStyle.continuous && kind == Kind.FULL -> 120f + maxOf(150f,
                titleWidth(title, titleSize).coerceAtMost(260f),
                width(snap.track?.artists.orEmpty().joinToString(" / "), 11f).coerceAtMost(220f))
            else -> kind.coverSize + (if (islandAnimations.get()) 58f else 34f) +
                max(titleWidth(title, titleSize), 128f)
        }
        return min(preferred, widthLimit(kind, available))
    }

    private fun widthLimit(kind: Kind, available: Float): Float = when (kind) {
        Kind.IDLE -> available
        Kind.MINI -> min(COMPACT_MAX_WIDTH, available)
        Kind.FULL -> min(FULL_MAX_WIDTH, available)
    }

    private fun idleParts(): List<Pair<String, Color>> {
        val parts = mutableListOf<Pair<String, Color>>()
        val platform = DangoClient.platform
        parts += platform.playerName to Theme.TEXT_PRIMARY
        parts += "${platform.fps} fps" to Theme.TEXT_SECONDARY
        val ping = platform.pingMs
        if (ping > 0) parts += "$ping ms" to Theme.TEXT_SECONDARY
        parts += clock.format(Date()) to Theme.TEXT_SECONDARY
        return parts
    }

    private fun idleWidth(): Float {
        val parts = idleParts()
        var x = 0f
        parts.forEachIndexed { i, (s, _) ->
            if (i > 0) x += width("  ·  ", 12f)
            x += width(s, 12f, if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
        }
        return x
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: HudRenderEvent) = renderIsland(e)

    internal fun renderIsland(e: HudRenderEvent? = null) {
        if (!enabled) return
        val snap = PlaybackView.snapshot()
        val now = System.nanoTime()
        val continuous = UiStyle.continuous
        val trackKey = snap.track?.key
        val title = snap.track?.name
        if (trackKey != lastTrackKey || title != lastTitle) {
            titleScrollStartedAt = now
            lastTrackKey = trackKey
            lastTitle = title
        }
        val compact = islandLayout.value == 1
        val animations = !compact && islandAnimations.get()
        if (!animations) {
            volumeUntil = 0L
            volumeReveal = 0f
            waveActivity = 0f
        }
        if (e == null && snap.track == null && now >= volumeUntil && volumeReveal < 0.01f) return
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) return
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        val volumeTarget = if (now < volumeUntil) 1f else 0f
        volumeReveal += (volumeTarget - volumeReveal) * (1f - exp(-dt / 0.075f))
        animatedVolume += (targetVolume - animatedVolume) * (1f - exp(-dt / 0.095f))
        if (animations) {
            val playing = if (snap.playing) 1f else 0f
            waveActivity += (playing - waveActivity) * (1f - exp(-dt / 0.12f))
            wavePhase = (wavePhase + dt * 8f * waveActivity) % (Math.PI * 2).toFloat()
        }
        val extension = if (volumeReveal > 0.01f) 22f * volumeReveal else 0f

        val want = when {
            snap.track == null -> Kind.IDLE
            compact -> Kind.MINI
            else -> Kind.FULL
        }
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val requestedScale = uiScale * scale.value
        val titleSizePx = AppleLyricsLayout.titleSizeFor(platform.windowWidth.toFloat(), platform.windowHeight.toFloat()) * scale.value
        // 小窗口先缩放 HUD，长歌名在剩余宽度内滚动。
        val baseScale = min(requestedScale, platform.windowWidth / 190f)
        val available = platform.windowWidth / baseScale - 16f

        // 内容切换：先淡出旧内容，再换成新内容淡入
        if (want == Kind.IDLE && shown != Kind.IDLE) {
            shown = Kind.IDLE
            contentAlpha = 1f
        } else if (shown != want) {
            contentAlpha -= dt / 0.09f
            if (contentAlpha <= 0f) {
                contentAlpha = 0f
                shown = want
            }
        } else {
            contentAlpha = min(1f, contentAlpha + dt / 0.16f)
        }

        // 弹簧：略带回弹，接近 iOS 灵动岛的手感
        val tw = widthOf(want, snap, available, titleSizePx / baseScale)
        val th = want.height + extension
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        val nextWidth = w + vw * dt
        w = min(nextWidth, widthLimit(want, available))
        if (w < nextWidth) vw = 0f
        h += vh * dt
        if (w < 1f || h < 1f) return

        val k = minOf(baseScale, platform.windowWidth / w, platform.windowHeight / h)
        val titleSize = titleSizePx / k
        val travelX = (platform.windowWidth / k - w).coerceAtLeast(0f)
        val travelY = (platform.windowHeight / k - h).coerceAtLeast(0f)
        val initial = LayoutBounds(posX.value * travelX, posY.value * travelY, w, h)
        if (e?.overlapsPlayerList(initial, k) == true) {
            dragOffset = null
            return
        }
        val down = e != null && platform.chatOpen && platform.isMouseDown(0)
        val mouseX = platform.mouseX / k
        val mouseY = platform.mouseY / k
        if (!down) dragOffset = null
        else if (!mouseWasDown && mouseX in initial.x..initial.right && mouseY in initial.y..initial.bottom) {
            dragOffset = Vec2(mouseX - initial.x, mouseY - initial.y)
        }
        mouseWasDown = down
        val pill = dragOffset?.let { offset ->
            val x = (mouseX - offset.x).coerceIn(0f, travelX)
            val y = (mouseY - offset.y).coerceIn(0f, travelY)
            if (travelX > 0f) posX.setValue(x / travelX)
            if (travelY > 0f) posY.setValue(y / travelY)
            LayoutBounds(x, y, w, h)
        } ?: initial

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), 0, 0)
        try {
            // A full-height radius makes the outline a capsule throughout the size transition.
            val r = h / 2f

            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(pill, Theme.GLASS_WINDOW.copy(radius = r, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f))))
            } else {
                ctx.drawShape(
                    pill,
                    ShapeStyle(
                        Color(0.02f, 0.02f, 0.03f, 0.9f), r, 1f, Color(1f, 1f, 1f, 0.07f),
                        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                    )
                )
            }

            // 内容按目标尺寸排版，在当前胶囊里居中，超出部分裁掉
            val cw = widthOf(shown, snap, available, titleSize)
            val ch = shown.height
            val visibleExtension = extension.coerceAtMost((h - ch).coerceAtLeast(0f))
            val contentX = if (shown == Kind.MINI) pill.x else pill.x + (w - cw) / 2f
            val cb = LayoutBounds(contentX, pill.y + (h - ch - visibleExtension) / 2f, cw, ch)
            ctx.pushClip(pill)

            mt.clear()
            mt.clip = pill
            ctx.pushOpacity(contentAlpha)
            when {
                shown == Kind.IDLE -> drawIdle(ctx, cb)
                shown == Kind.MINI -> drawMini(ctx, cb, snap, titleSize)
                continuous && shown == Kind.FULL -> drawDev6Song(ctx, cb, snap, titleSize)
                else -> drawSong(ctx, cb, snap, titleSize)
            }
            if (animations && shown == Kind.FULL) {
                val cover = snap.track?.let(CoverCache::trackImage)
                drawWave(ctx, cb.right - 24f, cb.y + cb.height / 2f, cover)
            }
            ctx.popOpacity()
            if (animations && shown != Kind.MINI && visibleExtension > 8f) {
                ctx.pushOpacity(((visibleExtension - 8f) / 14f).coerceIn(0f, 1f))
                val x = cb.x + if (shown == Kind.IDLE) 10f else shown.coverSize + 20f
                drawVolume(ctx, x, cb.bottom + 9f)
                ctx.popOpacity()
            }
            ctx.popTransform()
            mt.flush(k)
            ctx.popClip()
        } finally {
            ctx.end()
        }
    }

    private fun drawIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val cy = b.y + b.height / 2f
        ctx.drawShape(LayoutBounds(b.x + 14f, cy - 3f, 6f, 6f), ShapeStyle(Theme.GREEN, 3f))
        var x = b.x + 28f
        val ty = cy - 12f * 0.39f
        idleParts().forEachIndexed { i, (s, c) ->
            if (i > 0) {
                text(ctx, "  ·  ", x, ty, 12f, Theme.TEXT_TERTIARY)
                x += width("  ·  ", 12f)
            }
            val font = if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            text(ctx, s, x, ty, 12f, c, font)
            x += width(s, 12f, font)
        }
    }

    private fun islandSourceLabel(snap: PlaybackView.Snapshot): String =
        if (!snap.remote && snap.track?.source == MusicSource.APPLE) "" else snap.sourceLabel

    private fun drawMini(ctx: GuiCanvas, b: LayoutBounds, snap: PlaybackView.Snapshot, titleSize: Float) {
        val track = snap.track ?: return
        val cover = LayoutBounds(b.x + COMPACT_COVER_LEFT, b.y + (b.height - COMPACT_COVER_SIZE) / 2f,
            COMPACT_COVER_SIZE, COMPACT_COVER_SIZE)
        drawCover(ctx, cover, CoverCache.track(track), mask = true)
        drawTrackTitle(ctx, track.name, b.x + COMPACT_TITLE_LEFT, b.right - COMPACT_END_PADDING,
            b.y + (b.height - titleSize) / 2f + 1f, titleSize,
            if (snap.playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
    }

    private inline fun <T> withTitleFont(draw: () -> T): T {
        val previous = mt.useNotoSansSc
        mt.useNotoSansSc = true
        return try { draw() } finally { mt.useNotoSansSc = previous }
    }

    private fun titleWidth(title: String, size: Float): Float = withTitleFont {
        width(title, size, AppleLyricsLayout.TITLE_FONT)
    }

    private fun drawTrackTitle(ctx: GuiCanvas, title: String, left: Float, right: Float, y: Float, size: Float, color: Color) = withTitleFont {
        val titleArea = LayoutBounds(left, y - 2f, (right - left).coerceAtLeast(0f), size + 6f)
        if (titleArea.width <= 0f || title.isEmpty()) return
        if (abs(titleScrollWidth - titleArea.width) > 0.1f) {
            titleScrollWidth = titleArea.width
            titleScrollStartedAt = lastNanos
        }
        val overflow = width(title, size, AppleLyricsLayout.TITLE_FONT) - titleArea.width
        val offset = titleOffset(overflow, (lastNanos - titleScrollStartedAt) / 1e9)
        val previousClip = mt.clip
        val clip = previousClip?.intersect(titleArea) ?: titleArea
        if (clip.width <= 0f || clip.height <= 0f) return
        ctx.pushClip(clip)
        mt.clip = clip
        try {
            text(ctx, title, left - offset, y, size, color, AppleLyricsLayout.TITLE_FONT)
        } finally {
            mt.clip = previousClip
            ctx.popClip()
        }
    }

    private fun titleOffset(overflow: Float, seconds: Double): Float {
        if (overflow <= 0f || seconds <= 0.0) return 0f
        val travelSeconds = overflow / TITLE_SCROLL_SPEED.toDouble()
        val leg = TITLE_HOLD_SECONDS + travelSeconds
        val phase = seconds % (2 * leg)
        return if (phase < leg) ((phase - TITLE_HOLD_SECONDS).coerceAtLeast(0.0) * TITLE_SCROLL_SPEED).toFloat()
        else (overflow - (phase - leg - TITLE_HOLD_SECONDS).coerceAtLeast(0.0) * TITLE_SCROLL_SPEED).toFloat()
            .coerceAtLeast(0f)
    }

    private fun drawDev6Song(ctx: GuiCanvas, b: LayoutBounds, snap: PlaybackView.Snapshot, titleSize: Float) {
        val track = snap.track ?: return
        val cover = LayoutBounds(b.x + 10f, b.y + 10f, 48f, 48f)
        drawCover(ctx, cover, CoverCache.track(track))
        val x = cover.right + 10f
        val right = songContentRight(b)
        val textWidth = right - x
        val source = trim(islandSourceLabel(snap), minOf(110f, textWidth * 0.55f), 9f)
        val sourceWidth = width(source, 9f)
        drawTrackTitle(ctx, track.name, x, right - 4f, b.y + 12f, titleSize, Theme.TEXT_PRIMARY)
        text(ctx, trim(track.artists.joinToString(" / "), (textWidth - sourceWidth - 6f).coerceAtLeast(0f), 11f),
            x, b.y + 31f, 11f, Theme.TEXT_SECONDARY)
        if (source.isNotEmpty()) text(ctx, source, right - sourceWidth, b.y + 33f, 9f, Theme.TEXT_TERTIARY)

        val duration = snap.durationMs.coerceAtLeast(0L)
        val position = snap.positionMs.coerceIn(0L, duration)
        val elapsed = fmtTime(position)
        val remaining = if (duration > 0L) "-${fmtTime(duration - position)}" else "--:--"
        val timeX = right - width(remaining, 9f)
        text(ctx, elapsed, x, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
        text(ctx, remaining, timeX, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
        val barX = x + width(elapsed, 9f) + 7f
        drawSongProgress(ctx, barX, b.y + 52f, timeX - 7f - barX, position, duration)
    }

    private fun drawSong(ctx: GuiCanvas, b: LayoutBounds, snap: PlaybackView.Snapshot, titleSize: Float) {
        val track = snap.track ?: return
        val cover = LayoutBounds(b.x + 10f, b.y + 10f, Kind.FULL.coverSize, Kind.FULL.coverSize)
        drawCover(ctx, cover, CoverCache.trackRounded(track) ?: CoverCache.track(track))
        val x = cover.right + 10f
        val right = songContentRight(b)
        val textWidth = right - x
        drawTrackTitle(ctx, track.name, x, right, b.y + 12f, titleSize, Theme.TEXT_PRIMARY)

        val artists = track.artists.joinToString(" / ")
        val album = track.albumName.trim()
        val details = if (artists.isBlank() || album.isBlank()) {
            trim(artists.ifBlank { album }, textWidth, 11f)
        } else {
            val separator = " · "
            val available = textWidth - width(separator, 11f)
            val albumWidth = min(width(album, 11f), available / 2f)
            val artistLabel = trim(artists, available - albumWidth, 11f)
            artistLabel + separator + trim(album, available - width(artistLabel, 11f), 11f)
        }
        text(ctx, details, x, b.y + 31f, 11f, Theme.TEXT_SECONDARY)

        val duration = snap.durationMs.coerceAtLeast(0L)
        val position = snap.positionMs.coerceIn(0L, duration)
        val elapsed = fmtTime(snap.positionMs.coerceAtLeast(0L))
        val remaining = if (duration > 0L) "-${fmtTime(duration - position)}" else "--:--"
        val remainingWidth = width(remaining, 9f)
        text(ctx, elapsed, x, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
        text(ctx, remaining, right - remainingWidth, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
        val barX = x + width(elapsed, 9f) + 7f
        drawSongProgress(ctx, barX, b.y + 52f, right - barX - remainingWidth - 7f, position, duration)
    }

    private fun songContentRight(bounds: LayoutBounds): Float =
        bounds.right - if (islandAnimations.get()) 38f else 14f

    private fun drawSongProgress(ctx: GuiCanvas, x: Float, y: Float, width: Float, position: Long, duration: Long) {
        if (width <= 0f) return
        val bar = LayoutBounds(x, y, width, 3f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1.5f))
        if (position > 0L) {
            val fill = (bar.width * (position.toDouble() / duration)).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, fill, bar.height), ShapeStyle(Theme.TEXT_PRIMARY, min(1.5f, fill / 2f)))
        }
    }

    private fun drawVolume(ctx: GuiCanvas, x: Float, y: Float) {
        PlayerIcons.speaker(ctx, x + 10f, y, Theme.TEXT_PRIMARY, 0.64f,
            if (targetVolume > 0.5f) 2 else 1, targetVolume <= 0f)
        val bar = LayoutBounds(x + 23f, y - 2f, 61f, 4f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 2f))
        val fill = bar.width * animatedVolume
        if (fill > 0f) {
            ctx.drawShape(LayoutBounds(bar.x, bar.y, fill, bar.height), ShapeStyle(Theme.TEXT_PRIMARY, min(2f, fill / 2f)))
        }
    }

    private fun drawWave(ctx: GuiCanvas, x: Float, y: Float, image: BufferedImage?) {
        if (image !== waveImage) {
            waveImage = image
            waveColors = image?.let(CoverArt::paletteOf).orEmpty()
        }
        val first = Color.fromARGB(waveColors.firstOrNull() ?: CoverArt.DEFAULT_ACCENT)
        val last = waveColors.getOrNull(1)?.let(Color::fromARGB) ?: mixColor(first, Theme.TEXT_PRIMARY, 0.65f)
        for (bar in 0..4) {
            val pulse = 0.5f + 0.5f * sin(wavePhase + bar * 1.45f)
            val height = 2f + waveActivity * pulse * (14f - kotlin.math.abs(bar - 2) * 3f)
            val color = mixColor(first, last, bar / 4f)
            ctx.drawShape(LayoutBounds(x + (bar - 2) * 3.8f - 1f, y - height / 2f, 2f, height), ShapeStyle(color, 1f))
        }
    }

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?, mask: Boolean = UiStyle.continuous) {
        val radius = b.width * CoverCache.DEFAULT_CORNER_RATIO
        CoverCache.upload()
        if (tex != null) {
            if (mask) ctx.drawRoundedImage(b, tex, radius) else ctx.drawImage(b, tex)
        } else ctx.drawShape(b,ShapeStyle(Theme.TRACK,radius))
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)

    private companion object {
        const val COMPACT_MIN_WIDTH = 164f
        const val COMPACT_MAX_WIDTH = 256f
        const val FULL_MAX_WIDTH = 380f
        const val COMPACT_COVER_LEFT = 10f
        const val COMPACT_COVER_SIZE = 22f
        const val COMPACT_COVER_GAP = 15f
        const val COMPACT_TITLE_LEFT = COMPACT_COVER_LEFT + COMPACT_COVER_SIZE + COMPACT_COVER_GAP
        const val COMPACT_END_PADDING = 18f
        const val TITLE_SCROLL_SPEED = 27f
        const val TITLE_HOLD_SECONDS = 1.1
    }
}
