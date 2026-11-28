package dangomusicce.musicui

import dangomusicce.i18n.I18n
import dangomusicce.music.PlaybackView
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color

/**
 * 精简可拖动调试台：默认停靠左上角，拖动标题栏可自由移动，
 * 用于在隐藏原版 HUD 与主窗口的情况下实时调整歌词与灵动岛/点歌台。
 */
internal class DebugDrawer(private val ui: Ui) {

    enum class DebugTab(val label: String) {
        LYRICS("Lyrics"),
        HUD("Island & Requests"),
    }

    var tab = DebugTab.LYRICS

    /** 调试台的完整矩形（绘制与命中测试共用） */
    var bounds = LayoutBounds()
    private var headerDragArea = LayoutBounds()
    private var returnBtnRect = LayoutBounds()
    private var freecamBtnRect = LayoutBounds()
    private var seekBar = LayoutBounds()

    private var posX = 10f
    private var posY = 10f
    private var draggingWindow = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var seeking = false
    private var seekFraction = 0f

    val isSeeking: Boolean get() = seeking
    val isDraggingWindow: Boolean get() = draggingWindow

    private val gripIcon = VectorIcon.path(filled = true) {
        circle(8.0, 6.0, 1.8); circle(16.0, 6.0, 1.8)
        circle(8.0, 12.0, 1.8); circle(16.0, 12.0, 1.8)
        circle(8.0, 18.0, 1.8); circle(16.0, 18.0, 1.8)
    }

    fun layout(lw: Float, lh: Float): LayoutBounds {
        val w = minOf(292f, lw * 0.36f).coerceAtLeast(minOf(236f, (lw - 16f).coerceAtLeast(160f)))
        val h = minOf(lh - 20f, maxOf(lh * 0.80f, 360f)).coerceAtLeast(minOf(220f, (lh - 16f).coerceAtLeast(140f)))
        val maxX = (lw - w - 6f).coerceAtLeast(6f)
        val maxY = (lh - h - 6f).coerceAtLeast(6f)
        posX = posX.coerceIn(6f, maxX)
        posY = posY.coerceIn(6f, maxY)
        return LayoutBounds(posX, posY, w, h)
    }

    fun contains(mx: Float, my: Float): Boolean = bounds.contains(mx.toInt(), my.toInt())

    fun draw(
        usesAppleLayout: Boolean,
        freecamEnabled: Boolean,
        onFreecam: () -> Unit,
        onExit: () -> Unit,
        drawBody: (LayoutBounds, DebugTab) -> Unit,
    ) {
        val accent = if (usesAppleLayout) Color(0.34f, 0.66f, 1f, 1f) else Color(1f, 0.22f, 0.37f, 1f)
        bounds = layout(ui.lw, ui.lh)
        val borderColor = if (draggingWindow) accent.copy(a = 0.55f) else Color(1f, 1f, 1f, 0.14f)
        ui.ctx.drawShape(bounds, ShapeStyle(Color(0.05f, 0.055f, 0.075f, 0.78f), 13f, 1f, borderColor))

        val pad = 10f
        val innerW = bounds.width - pad * 2f
        var y = bounds.y + pad

        // ---------------------------------------------------------------- 可拖动标题栏
        val title = I18n.text("Debug mode")
        val titleSize = 12f
        val titleW = ui.width(title, titleSize, Theme.FONT_SEMIBOLD)
        val returnLabel = I18n.text("Back to settings")
        val freecamLabel = I18n.text("Freecam")
        val btnSize = 10.5f
        val btnH = 20f
        val btnGap = 5f
        val returnW = ui.width(returnLabel, btnSize, Theme.FONT_SEMIBOLD) + 14f
        val freecamW = ui.width(freecamLabel, btnSize, Theme.FONT_SEMIBOLD) + 14f
        val gripW = 14f
        val stackedHeader = gripW + titleW + 8f + returnW + btnGap + freecamW > innerW

        val headerH = if (stackedHeader) 48f else 24f
        headerDragArea = LayoutBounds(bounds.x, bounds.y, bounds.width, pad + headerH + 2f)

        PlayerIcons.draw(ui.ctx, gripIcon, bounds.x + pad + 4f, y + 11f, 11f,
            if (draggingWindow || ui.hover(headerDragArea)) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)
        ui.text(title, bounds.x + pad + gripW, y + (22f - titleSize) / 2f, titleSize, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

        val btnY = if (stackedHeader) y + 25f else y + (22f - btnH) / 2f
        freecamBtnRect = LayoutBounds(bounds.right - pad - freecamW, btnY, freecamW, btnH)
        returnBtnRect = LayoutBounds(freecamBtnRect.x - btnGap - returnW, btnY, returnW, btnH)

        // 返回设置
        val returnFill = if (ui.hover(returnBtnRect)) Theme.CARD_HOVER else Theme.CARD
        ui.ctx.drawShape(returnBtnRect, ShapeStyle(returnFill, returnBtnRect.height / 2f))
        ui.text(returnLabel, returnBtnRect.x + (returnBtnRect.width - ui.width(returnLabel, btnSize, Theme.FONT_SEMIBOLD)) / 2f,
            returnBtnRect.y + (returnBtnRect.height - btnSize) / 2f, btnSize, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.hits += returnBtnRect to onExit

        // 自由漫游
        val freecamHover = freecamEnabled && ui.hover(freecamBtnRect)
        val freecamFill = when {
            !freecamEnabled -> Color(1f, 1f, 1f, 0.04f)
            freecamHover -> accent.copy(a = 0.35f)
            else -> Theme.CARD
        }
        val freecamText = when {
            !freecamEnabled -> Theme.TEXT_TERTIARY
            freecamHover -> Color(1f, 1f, 1f, 1f)
            else -> Theme.TEXT_PRIMARY
        }
        ui.ctx.drawShape(freecamBtnRect, ShapeStyle(freecamFill, freecamBtnRect.height / 2f))
        ui.text(freecamLabel, freecamBtnRect.x + (freecamBtnRect.width - ui.width(freecamLabel, btnSize, Theme.FONT_SEMIBOLD)) / 2f,
            freecamBtnRect.y + (freecamBtnRect.height - btnSize) / 2f, btnSize, freecamText, Theme.FONT_SEMIBOLD)
        if (freecamEnabled) ui.hits += freecamBtnRect to onFreecam

        y += headerH + 6f

        // ---------------------------------------------------------------- 迷你播放控制（矢量图标）
        val cardH = 52f
        val card = LayoutBounds(bounds.x + pad, y, innerW, cardH)
        ui.ctx.drawShape(card, ShapeStyle(Color(1f, 1f, 1f, 0.06f), 9f))

        val snap = PlaybackView.snapshot()
        val track = snap.track
        if (track == null) {
            seekBar = LayoutBounds()
            val hint = I18n.text("Play a song first to preview live lyrics")
            val hintSize = 10.5f
            ui.text(ui.trim(hint, card.width - 14f, hintSize),
                card.x + (card.width - minOf(card.width - 14f, ui.width(hint, hintSize))) / 2f,
                card.y + (card.height - hintSize) / 2f, hintSize, Theme.TEXT_SECONDARY)
        } else {
            val cp = 7f
            val btnW = 22f
            val btnH2 = 20f
            val tGap = 3f

            val nextBtn = LayoutBounds(card.right - cp - btnW, card.y + cp, btnW, btnH2)
            val playBtn = LayoutBounds(nextBtn.x - tGap - btnW, card.y + cp, btnW, btnH2)
            val prevBtn = LayoutBounds(playBtn.x - tGap - btnW, card.y + cp, btnW, btnH2)
            val titleSz = 11f
            val titleMaxW = (prevBtn.x - (card.x + cp) - 6f).coerceAtLeast(0f)
            val titleText = ui.trim(track.name, titleMaxW, titleSz, Theme.FONT_MEDIUM)
            ui.text(titleText, card.x + cp, card.y + cp + (btnH2 - titleSz) / 2f, titleSz, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)

            drawTransportButton(prevBtn, PlayerIcons.cardPrevious, !snap.remote) { PlaybackView.previous() }
            drawTransportButton(playBtn, if (snap.playing) PlayerIcons.cardPause else PlayerIcons.cardPlay, !snap.remote) {
                PlaybackView.togglePause()
            }
            drawTransportButton(nextBtn, PlayerIcons.cardNext, !snap.remote) { PlaybackView.next() }

            val timeStr = "${fmtTime(snap.positionMs)} / ${fmtTime(snap.durationMs)}"
            val timeSize = 9.5f
            val timeW = ui.width(timeStr, timeSize)
            val seekX = card.x + cp
            val seekH = 12f
            val seekW = (card.right - cp - timeW - 6f - seekX).coerceAtLeast(10f)
            val seekY = card.bottom - cp - seekH
            seekBar = LayoutBounds(seekX, seekY, seekW, seekH)

            val fraction = if (seeking) {
                seekFraction
            } else {
                (snap.positionMs.toFloat() / snap.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            }

            val trackH = 3.5f
            val trackY = seekY + (seekH - trackH) / 2f
            ui.ctx.drawShape(LayoutBounds(seekX, trackY, seekW, trackH), ShapeStyle(Color(1f, 1f, 1f, 0.12f), trackH / 2f))
            val fillW = seekW * fraction
            if (fillW > 0.5f) {
                ui.ctx.drawShape(LayoutBounds(seekX, trackY, fillW, trackH), ShapeStyle(accent, trackH / 2f))
            }
            ui.text(timeStr, card.right - cp - timeW, seekY + (seekH - timeSize) / 2f, timeSize, Theme.TEXT_SECONDARY)
        }

        y += cardH + 6f

        // ---------------------------------------------------------------- 分段标签栏
        val tabBar = LayoutBounds(bounds.x + pad, y, innerW, 24f)
        ui.ctx.drawShape(tabBar, ShapeStyle(Color(1f, 1f, 1f, 0.06f), 8f))
        val tabW = tabBar.width / 2f
        val tabSize = 10.5f
        for (t in DebugTab.values()) {
            val rect = LayoutBounds(tabBar.x + t.ordinal * tabW, tabBar.y, tabW, tabBar.height)
            val active = t == tab
            if (active) {
                ui.ctx.drawShape(rect, ShapeStyle(accent, 8f))
            }
            val label = I18n.text(t.label)
            ui.text(label, rect.x + (tabW - ui.width(label, tabSize, Theme.FONT_SEMIBOLD)) / 2f,
                rect.y + (rect.height - tabSize) / 2f, tabSize,
                if (active) Color(1f, 1f, 1f, 1f) else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.hits += rect to { tab = t }
        }

        y += 30f

        // ---------------------------------------------------------------- 主体内容
        val content = LayoutBounds(bounds.x + pad, y, innerW, (bounds.bottom - pad - y).coerceAtLeast(40f))
        drawBody(content, tab)
    }

    private fun drawTransportButton(b: LayoutBounds, icon: VectorIcon, enabled: Boolean, action: () -> Unit) {
        val fill = when {
            !enabled -> Color(1f, 1f, 1f, 0.04f)
            ui.hover(b) -> Theme.CARD_HOVER
            else -> Theme.CARD
        }
        ui.ctx.drawShape(b, ShapeStyle(fill, b.height / 2f))
        val color = if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
        PlayerIcons.draw(ui.ctx, icon, b.x + b.width / 2f, b.y + b.height / 2f, 11f, color)
        if (enabled) ui.hits += b to action
    }

    // ---------------------------------------------------------------- 拖动与进度控制

    fun mouseDown(mx: Float, my: Float, button: Int): Boolean {
        if (button != 0) return false
        val ix = mx.toInt()
        val iy = my.toInt()
        if (seekBar.width > 0f && seekBar.contains(ix, iy)) {
            val snap = PlaybackView.snapshot()
            if (snap.track != null && !snap.remote && snap.durationMs > 0L) {
                seeking = true
                seekFraction = ((mx - seekBar.x) / seekBar.width).coerceIn(0f, 1f)
                return true
            }
        }
        if (headerDragArea.contains(ix, iy) &&
            !returnBtnRect.contains(ix, iy) &&
            !freecamBtnRect.contains(ix, iy)
        ) {
            draggingWindow = true
            dragOffsetX = mx - bounds.x
            dragOffsetY = my - bounds.y
            return true
        }
        return false
    }

    fun mouseDragged(mx: Float, my: Float) {
        if (draggingWindow) {
            val maxX = (ui.lw - bounds.width - 6f).coerceAtLeast(6f)
            val maxY = (ui.lh - bounds.height - 6f).coerceAtLeast(6f)
            posX = (mx - dragOffsetX).coerceIn(6f, maxX)
            posY = (my - dragOffsetY).coerceIn(6f, maxY)
            return
        }
        if (seeking && seekBar.width > 0f) {
            seekFraction = ((mx - seekBar.x) / seekBar.width).coerceIn(0f, 1f)
        }
    }

    fun mouseReleased(mx: Float) {
        draggingWindow = false
        if (!seeking) return
        seeking = false
        val snap = PlaybackView.snapshot()
        if (snap.track != null && !snap.remote && snap.durationMs > 0L && seekBar.width > 0f) {
            val frac = ((mx - seekBar.x) / seekBar.width).coerceIn(0f, 1f)
            PlaybackView.seek((snap.durationMs * frac).toLong())
        }
    }

    fun cancelInteractions() {
        seeking = false
        draggingWindow = false
    }
}
