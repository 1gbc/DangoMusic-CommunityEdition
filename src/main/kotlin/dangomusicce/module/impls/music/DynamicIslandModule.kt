package dangomusicce.module.impls.music

import dangomusicce.DangoClient
import dangomusicce.DangoMusic
import dangomusicce.client.HudPointerInput
import dangomusicce.musicui.drawIslandLock
import net.minecraft.client.Minecraft
import dangomusicce.event.EventHandler
import dangomusicce.events.HudRenderEvent
import dangomusicce.font.MixedText
import dangomusicce.gfx.CjkStyle
import dangomusicce.gfx.GuiCanvas
import dangomusicce.gfx.Texture
import dangomusicce.module.Category
import dangomusicce.module.Module
import dangomusicce.module.ModuleMeta
import dangomusicce.module.values.BooleanValue
import dangomusicce.module.values.FloatValue
import dangomusicce.module.values.IntValue
import dangomusicce.module.values.ModuleValue
import dangomusicce.module.values.TextValue
import dangomusicce.music.CoverArt
import dangomusicce.music.CoverCache
import dangomusicce.music.LyricFonts
import dangomusicce.music.PlaybackView
import dangomusicce.musicui.AppleLyricsLayout
import dangomusicce.musicui.HudBackdrop
import dangomusicce.musicui.HudBackground
import dangomusicce.musicui.IslandPlayerCard
import dangomusicce.musicui.PlayerIcons
import dangomusicce.musicui.mixColor
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShadowStyle
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.Vec2
import dangomusicce.utils.Color

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

    @ModuleValue("Island background", "Choose the Dynamic Island background material")
    private val islandBackground = IntValue(0, HudBackground.entries.lastIndex, HudBackground.COVER.ordinal)

    @ModuleValue("Island layout", "Choose the Dynamic Island layout")
    private val islandLayout = IntValue(STYLE_C, STYLE_PLAYER_CARD, STYLE_A)

    @ModuleValue("Island font", "Choose a font for the Dynamic Island from the fonts folder or your system")
    private val islandFont = TextValue("")

    private val islandFontStyles = CjkStyle.ISLAND to CjkStyle.ISLAND_BOLD
    private val defaultLyricStyles = CjkStyle.ISLAND to CjkStyle.ISLAND

    internal val hasPlayerCardLayout get() = islandLayout.value == STYLE_PLAYER_CARD

    @ModuleValue("Show when HUD is hidden", "Keep the Dynamic Island visible when F1 hides the game HUD")
    private val islandWhileHudHidden = BooleanValue(false)

    internal fun allowsIslandHud(hudHidden: Boolean): Boolean = !hudHidden || islandWhileHudHidden.get()

    @ModuleValue("Island animations", "Show the playback waveform and volume hotkey feedback")
    private val islandAnimations = BooleanValue(false)

    @ModuleValue("Pos X", "Island horizontal position")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Pos Y", "Island vertical position")
    private val posY = FloatValue(0f, 1f, 0.05f, 3)

    @ModuleValue("Lock Dynamic Island", "Prevent moving the Dynamic Island with the mouse")
    private val islandLocked = BooleanValue(true)

    internal val canMoveWithPointer get() = enabled && !islandLocked.get()

    @ModuleValue("Scale", "Island size")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    private enum class Kind(
        val height: Float,
        val coverSize: Float,
        val maxWidth: Float = Float.POSITIVE_INFINITY,
        val cornerRadius: Float = Float.POSITIVE_INFINITY,
    ) {
        IDLE(32f, 0f),
        MINI(32f, COMPACT_COVER_SIZE, COMPACT_MAX_WIDTH),
        SLIM(FULL_HEIGHT, 37f, FULL_WIDTH, 5.5f),
        CARD(FULL_HEIGHT, 32f, FULL_WIDTH, 10f),
        PLAYER_CARD(IslandPlayerCard.HEIGHT, 0f, IslandPlayerCard.WIDTH, 12f),
    }

    private var context: GuiCanvas? = null
    private val backdrop = HudBackdrop { checkNotNull(context) }
    private val playerCard = IslandPlayerCard()
    private var pointerHover = false

    private fun islandLockBounds(bounds: LayoutBounds) =
        LayoutBounds(bounds.right - 31f, bounds.y + 4f, 22f, 22f)

    internal fun updateIslandHover(x: Int, y: Int, active: Boolean) {
        pointerHover = active && enabled && placement?.let {
            it.bounds.contains((x / it.scale).toInt(), (y / it.scale).toInt())
        } == true
    }

    internal fun clickIslandLock(x: Int, y: Int): Boolean {
        val current = placement ?: return false
        if (!enabled || hasPlayerCardLayout || !pointerHover ||
            !islandLockBounds(current.bounds).contains((x / current.scale).toInt(), (y / current.scale).toInt())) return false
        toggleIslandLock()
        return true
    }

    private fun toggleIslandLock() {
        finishDrag()
        islandLocked.set(!islandLocked.get())
        DangoMusic.instance.configManager.saveIfChanged()
    }


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
        if (!enabled || islandLayout.value == STYLE_A || hasPlayerCardLayout || !islandAnimations.get()) return
        targetVolume = value.coerceIn(0f, 1f)
        if (volumeReveal < 0.01f) animatedVolume = targetVolume
        volumeUntil = System.nanoTime() + 2_000_000_000L
    }

    private data class Placement(val bounds: LayoutBounds, val scale: Float, val travelX: Float, val travelY: Float)
    private class Drag(val offset: Vec2, val fromChat: Boolean) {
        var position: Vec2? = null
        var moved = false
    }
    private var placement: Placement? = null
    private var drag: Drag? = null
    private var chatWasDown = false
    private var lastTrackKey: String? = null
    private var lastTitle: String? = null
    private var titleScrollWidth = 0f
    private var titleScrollStartedAt = 0L

    private val clock = SimpleDateFormat("HH:mm")

    private val mt = MixedText().apply { hudTextShadow = true }

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
        placement = null
        drag = null
        chatWasDown = false
        lastTrackKey = null
        lastTitle = null
        titleScrollWidth = 0f
        titleScrollStartedAt = 0L
        playerCard.hide()
    }

    override fun onDisable() {
        placement = null
        playerCard.hide()
        finishDrag()
    }

    internal fun playerCardAt(x: Int, y: Int) = enabled && hasPlayerCardLayout && playerCard.contains(x, y)

    internal fun updatePlayerCardPointer(x: Int, y: Int, available: Boolean) =
        playerCard.pointerAt(x, y, enabled && hasPlayerCardLayout && available)

    internal fun pressPlayerCard(x: Int, y: Int) = playerCardAt(x, y) && playerCard.mouseDown(x, y)

    internal fun movePlayerCard(x: Int) = playerCard.mouseDrag(x)

    internal fun releasePlayerCard(x: Int, y: Int, available: Boolean) =
        playerCard.mouseUp(x, y, enabled && hasPlayerCardLayout && available, ::toggleIslandLock)

    internal fun scrollPlayerCard(x: Int, y: Int, wheel: Int) = playerCard.scroll(x, y, wheel)

    internal fun clearPlayerCardPointer() {
        pointerHover = false
        playerCard.clearPointer()
    }

    internal fun beginPointerDrag(x: Int, y: Int): Boolean = beginDrag(x, y, fromChat = false)

    internal fun movePointerDrag(x: Int, y: Int) {
        if (drag?.fromChat == false) moveDrag(x, y)
    }

    internal fun endPointerDrag() {
        if (drag?.fromChat == false) finishDrag()
    }

    private fun beginDrag(x: Int, y: Int, fromChat: Boolean): Boolean {
        if (!canMoveWithPointer || drag != null) return false
        val frame = placement ?: return false
        val px = x / frame.scale
        val py = y / frame.scale
        val bounds = frame.bounds
        if (px < bounds.left || px >= bounds.right || py < bounds.top || py >= bounds.bottom) return false
        if (shown == Kind.PLAYER_CARD && !playerCard.canStartDrag(x, y)) return false
        drag = Drag(Vec2(px - bounds.x, py - bounds.y), fromChat)
        return true
    }

    private fun moveDrag(x: Int, y: Int) {
        val active = drag ?: return
        if (!canMoveWithPointer) {
            finishDrag()
            return
        }
        val frame = placement ?: return
        val nx = if (frame.travelX > 0f) ((x / frame.scale - active.offset.x) / frame.travelX).coerceIn(0f, 1f)
            else posX.value
        val ny = if (frame.travelY > 0f) ((y / frame.scale - active.offset.y) / frame.travelY).coerceIn(0f, 1f)
            else posY.value
        active.position = Vec2(nx, ny)
        val beforeX = posX.value
        val beforeY = posY.value
        posX.setValue(nx)
        posY.setValue(ny)
        active.moved = active.moved || posX.value != beforeX || posY.value != beforeY
    }

    private fun finishDrag() {
        val active = drag ?: return
        drag = null
        if (active.moved) DangoMusic.instance.configManager.saveIfChanged()
    }

    private fun widthOf(kind: Kind, snap: PlaybackView.Snapshot, available: Float, titleSize: Float): Float {
        val title = snap.track?.name.orEmpty()
        val preferred = when {
            kind == Kind.IDLE -> 44f + idleWidth()
            kind == Kind.MINI -> (COMPACT_TITLE_LEFT + COMPACT_END_PADDING +
                titleWidth(title, titleSize)).coerceIn(COMPACT_MIN_WIDTH, COMPACT_MAX_WIDTH)
            else -> kind.maxWidth
        }
        return min(preferred, widthLimit(kind, available))
    }

    private fun widthLimit(kind: Kind, available: Float): Float = min(kind.maxWidth, available)

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
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        if (!allowsIslandHud(DangoClient.platform.hudHidden)) {
            placement = null
            playerCard.hide()
            finishDrag()
            return
        }
        renderIsland(e)
    }

    internal fun renderIsland(e: HudRenderEvent? = null) {
        placement = null
        if (!canMoveWithPointer) finishDrag()
        if (!enabled) return
        val snap = PlaybackView.snapshot()
        val now = System.nanoTime()
        val trackKey = snap.track?.key
        val title = snap.track?.name
        if (trackKey != lastTrackKey || title != lastTitle) {
            titleScrollStartedAt = now
            lastTrackKey = trackKey
            lastTitle = title
        }
        val layout = when (islandLayout.value) {
            STYLE_A -> Kind.MINI
            STYLE_B -> Kind.SLIM
            STYLE_PLAYER_CARD -> Kind.PLAYER_CARD
            else -> Kind.CARD
        }
        val animations = layout != Kind.MINI && layout != Kind.PLAYER_CARD && islandAnimations.get()
        if (!animations) {
            volumeUntil = 0L
            volumeReveal = 0f
            waveActivity = 0f
        }
        if (e == null && snap.track == null && now >= volumeUntil && volumeReveal < 0.01f) {
            playerCard.hide()
            return
        }
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) {
            playerCard.hide()
            return
        }
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

        val want = if (snap.track == null) Kind.IDLE else layout
        val selectedFont = islandFont.value
        val useDefaultLyricFont = selectedFont.isEmpty() && (want == Kind.PLAYER_CARD || shown == Kind.PLAYER_CARD)
        val fontId = if (useDefaultLyricFont) LyricFonts.DEFAULT_HUD_LYRIC_FONT else selectedFont
        val fontStyles = if (useDefaultLyricFont) defaultLyricStyles else islandFontStyles
        val activeFontStyles = fontStyles.takeIf { LyricFonts.bindStyles(fontId, it) != null }
        mt.customFontStyles = activeFontStyles.takeUnless { useDefaultLyricFont }
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val requestedScale = uiScale * scale.value
        val titleSizePx = AppleLyricsLayout.titleSizeFor(platform.windowWidth.toFloat(), platform.windowHeight.toFloat()) * scale.value
        // 小窗口先缩放 HUD，长歌名在剩余宽度内滚动。
        val minimumWidth = if (want == Kind.PLAYER_CARD) IslandPlayerCard.WIDTH + 16f else 190f
        val baseScale = min(requestedScale, platform.windowWidth / minimumWidth)
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
        val expandedCard = want == Kind.PLAYER_CARD && e != null &&
            HudPointerInput.interacting(Minecraft.getMinecraft().currentScreen)
        val tw = if (want == Kind.PLAYER_CARD && !expandedCard) min(available, IslandPlayerCard.COMPACT_WIDTH)
            else widthOf(want, snap, available, titleSizePx / baseScale)
        val th = (if (want == Kind.PLAYER_CARD && !expandedCard) IslandPlayerCard.COMPACT_HEIGHT else want.height) + extension
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        val nextWidth = w + vw * dt
        w = min(nextWidth, widthLimit(want, available))
        if (w < nextWidth) vw = 0f
        h += vh * dt
        if (w < 1f || h < 1f) {
            playerCard.hide()
            return
        }

        val k = minOf(baseScale, platform.windowWidth / w, platform.windowHeight / h)
        val titleSize = titleSizePx / k
        val travelX = (platform.windowWidth / k - w).coerceAtLeast(0f)
        val travelY = (platform.windowHeight / k - h).coerceAtLeast(0f)
        val initial = LayoutBounds(posX.value * travelX, posY.value * travelY, w, h)
        val down = e != null && canMoveWithPointer && platform.chatOpen && platform.isMouseDown(0)
        if (drag?.fromChat == true && !down) finishDrag()
        if (e?.overlapsPlayerList(initial, k) == true) {
            playerCard.hide()
            finishDrag()
            chatWasDown = down
            return
        }
        if (e != null) placement = Placement(initial, k, travelX, travelY)
        if (down && !chatWasDown) beginDrag(platform.mouseX, platform.mouseY, fromChat = true)
        chatWasDown = down
        if (drag != null && platform.isMouseDown(0)) moveDrag(platform.mouseX, platform.mouseY)
        val dragPosition = drag?.position
        val pill = initial.copy(x = (dragPosition?.x ?: posX.value) * travelX, y = (dragPosition?.y ?: posY.value) * travelY)
        if (e != null) placement = Placement(pill, k, travelX, travelY)

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        val background = HudBackground.entries[islandBackground.value]
        ctx.begin(background.capturesWorld, 0, 0)
        try {
            val r = if (shown == Kind.PLAYER_CARD) {
                val expansion = ((h - IslandPlayerCard.COMPACT_HEIGHT) /
                    (IslandPlayerCard.HEIGHT - IslandPlayerCard.COMPACT_HEIGHT)).coerceIn(0f, 1f)
                (IslandPlayerCard.COMPACT_HEIGHT / 2f) * (1f - expansion) + shown.cornerRadius * expansion
            } else min(shown.cornerRadius, h / 2f)

            ctx.pushScale(k, k, 1f)
            val glass = Theme.GLASS_WINDOW.copy(radius = r,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)))
            backdrop.draw(pill, glass, background, snap.track)

            // 内容按目标尺寸排版，在当前胶囊里居中，超出部分裁掉
            val cw = widthOf(shown, snap, available, titleSize)
            val ch = shown.height
            val visibleExtension = extension.coerceAtMost((h - ch).coerceAtLeast(0f))
            val contentX = if (shown == Kind.MINI) pill.x else pill.x + (w - cw) / 2f
            val cb = LayoutBounds(contentX, pill.y + (h - ch - visibleExtension) / 2f, cw, ch)
            ctx.pushClip(pill)

            mt.clear()
            mt.clip = pill
            if (shown != Kind.PLAYER_CARD) playerCard.hide()
            ctx.pushOpacity(contentAlpha)
            when {
                shown == Kind.IDLE -> drawIdle(ctx, cb)
                shown == Kind.MINI -> drawMini(ctx, cb, snap, titleSize, pill.x + pill.width / 2f)
                shown == Kind.PLAYER_CARD -> playerCard.draw(ctx, mt, pill, k, expandedCard, platform.windowHeight / k, snap,
                    DangoMusic.instance.moduleManager.get(LyricsModule::class.java), now, dt, islandLocked.get(), activeFontStyles)
                else -> drawFull(ctx, cb, snap, shown, titleSize)
            }
            if (animations && (shown == Kind.SLIM || shown == Kind.CARD)) {
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
            if (shown != Kind.PLAYER_CARD && pointerHover) {
                val lock = islandLockBounds(pill)
                drawIslandLock(ctx, lock, islandLocked.get(), lock.contains((platform.mouseX / k).toInt(), (platform.mouseY / k).toInt()))
            }
            ctx.popTransform()
            mt.flush(k)
            ctx.popClip()
            if (shown == Kind.PLAYER_CARD && expandedCard) {
                ctx.pushScale(k, k, 1f)
                try { playerCard.drawVolumePopup(ctx) } finally { ctx.popTransform() }
            }
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

    private fun drawMini(ctx: GuiCanvas, b: LayoutBounds, snap: PlaybackView.Snapshot, titleSize: Float, centerX: Float) {
        val track = snap.track ?: return
        val cover = LayoutBounds(b.x + COMPACT_COVER_LEFT, b.y + (b.height - COMPACT_COVER_SIZE) / 2f,
            COMPACT_COVER_SIZE, COMPACT_COVER_SIZE)
        drawCover(ctx, cover, CoverCache.track(track))
        drawTrackTitle(ctx, track.name, b.x + COMPACT_TITLE_LEFT, b.right - COMPACT_END_PADDING,
            b.y + (b.height - titleSize) / 2f + 1f, titleSize,
            if (snap.playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, centerX)
    }

    private inline fun <T> withTitleFont(draw: () -> T): T {
        val previous = mt.useNotoSansSc
        mt.useNotoSansSc = true
        return try { draw() } finally { mt.useNotoSansSc = previous }
    }

    private fun titleWidth(title: String, size: Float): Float = withTitleFont {
        width(title, size, AppleLyricsLayout.TITLE_FONT)
    }

    private fun drawTrackTitle(
        ctx: GuiCanvas, title: String, left: Float, right: Float, y: Float, size: Float,
        color: Color, centerX: Float? = null,
    ) = withTitleFont {
        val titleArea = LayoutBounds(left, y - 2f, (right - left).coerceAtLeast(0f), size + 6f)
        if (titleArea.width <= 0f || title.isEmpty()) return
        if (abs(titleScrollWidth - titleArea.width) > 0.1f) {
            titleScrollWidth = titleArea.width
            titleScrollStartedAt = lastNanos
        }
        val textWidth = width(title, size, AppleLyricsLayout.TITLE_FONT)
        val overflow = textWidth - titleArea.width
        val x = if (centerX != null && overflow <= 0f) {
            (centerX - textWidth / 2f).coerceIn(left, left - overflow)
        } else left - titleOffset(overflow, (lastNanos - titleScrollStartedAt) / 1e9)
        val previousClip = mt.clip
        val clip = previousClip?.intersect(titleArea) ?: titleArea
        if (clip.width <= 0f || clip.height <= 0f) return
        ctx.pushClip(clip)
        mt.clip = clip
        try {
            text(ctx, title, x, y, size, color, AppleLyricsLayout.TITLE_FONT)
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

    private fun drawFull(ctx: GuiCanvas, b: LayoutBounds, snap: PlaybackView.Snapshot, kind: Kind, titleSize: Float) {
        val track = snap.track ?: return
        val slim = kind == Kind.SLIM
        val coverSize = kind.coverSize
        val cover = if (slim) {
            LayoutBounds(b.x + 7f, b.y + (b.height - coverSize) / 2f, coverSize, coverSize)
        } else {
            LayoutBounds(b.x + CARD_INSET, b.y + 7f, coverSize, coverSize)
        }
        val coverRadius = if (slim) coverSize * CoverCache.DEFAULT_CORNER_RATIO else 6.5f
        drawCover(ctx, cover, CoverCache.track(track), coverRadius)

        val x = cover.right + if (slim) 10f else 12f
        val right = b.right - if (islandAnimations.get()) 38f else if (slim) 10f else CARD_INSET
        val textWidth = right - x
        val progress = if (slim) {
            LayoutBounds(x, b.bottom - 7f, textWidth, 2f)
        } else {
            LayoutBounds(b.x + CARD_INSET, b.bottom - 7f, b.width - 2f * CARD_INSET, 2.5f)
        }
        drawSongProgress(ctx, progress, snap.positionMs, snap.durationMs)
        if (textWidth <= 0f) return

        val center = if (slim) x + textWidth / 2f else null
        val artistSize = titleSize * 0.82f
        val lineGap = 3f
        val labelHeight = titleSize + lineGap + artistSize
        val titleY = b.y + ((progress.y - b.y - labelHeight) / 2f).coerceAtLeast(0f)
        drawTrackTitle(ctx, track.name, x, right, titleY, titleSize, Theme.TEXT_PRIMARY, center)

        val artists = track.artists.joinToString(" / ")
        val album = track.albumName.trim()
        val separator = " / "
        val available = textWidth - width(separator, artistSize)
        val details = if (artists.isBlank() || album.isBlank() || available < 2f * width("...", artistSize)) {
            trim(artists.ifBlank { album }, textWidth, artistSize)
        } else {
            val albumWidth = min(width(album, artistSize), available / 2f)
            val artistLabel = trim(artists, available - albumWidth, artistSize)
            artistLabel + separator + trim(album, available - width(artistLabel, artistSize), artistSize)
        }
        val detailsWidth = width(details, artistSize)
        if (detailsWidth <= textWidth) {
            val detailsX = if (center != null) center - detailsWidth / 2f else x
            text(ctx, details, detailsX, titleY + titleSize + lineGap, artistSize, Theme.TEXT_SECONDARY)
        }
    }

    private fun drawSongProgress(ctx: GuiCanvas, bar: LayoutBounds, position: Long, duration: Long) {
        if (bar.width <= 0f) return
        val radius = bar.height / 2f
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, radius))
        if (duration > 0L && position > 0L) {
            val fill = (bar.width * (position.coerceAtMost(duration).toDouble() / duration)).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, fill, bar.height), ShapeStyle(Theme.TEXT_PRIMARY, min(radius, fill / 2f)))
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

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?, radius: Float = b.width * CoverCache.DEFAULT_CORNER_RATIO) {
        CoverCache.upload()
        if (tex != null) {
            ctx.drawRoundedImage(b, tex, radius)
        } else ctx.drawShape(b, ShapeStyle(Theme.TRACK, radius))
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)

    private companion object {
        // Keep the saved IDs of the previous full (0) and compact (1) layouts.
        const val STYLE_C = 0
        const val STYLE_A = 1
        const val STYLE_B = 2
        const val STYLE_PLAYER_CARD = 3
        const val COMPACT_MIN_WIDTH = 164f
        const val COMPACT_MAX_WIDTH = 256f
        const val FULL_WIDTH = 238.5f
        const val FULL_HEIGHT = 29f / 0.6f
        const val CARD_INSET = 12f
        const val COMPACT_COVER_LEFT = 10f
        const val COMPACT_COVER_SIZE = 22f
        const val COMPACT_COVER_GAP = 15f
        const val COMPACT_TITLE_LEFT = COMPACT_COVER_LEFT + COMPACT_COVER_SIZE + COMPACT_COVER_GAP
        const val COMPACT_END_PADDING = 18f
        const val TITLE_SCROLL_SPEED = 27f
        const val TITLE_HOLD_SECONDS = 1.1
    }
}
