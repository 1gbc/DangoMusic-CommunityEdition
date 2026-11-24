package dangomusicce.client

import dangomusicce.DangoClient
import dangomusicce.DangoMusic
import dangomusicce.font.MixedText
import dangomusicce.gfx.CjkStyle
import dangomusicce.gfx.GuiCanvas
import dangomusicce.i18n.I18n
import dangomusicce.module.impls.music.NeteaseMusicModule
import dangomusicce.music.CoverCache
import dangomusicce.music.LyricFonts
import dangomusicce.music.NeteaseTrack
import dangomusicce.music.PlaybackView
import dangomusicce.music.request.RequestCacheState
import dangomusicce.music.request.RequestEntry
import dangomusicce.music.request.RequestNotice
import dangomusicce.music.request.RequestNoticeKind
import dangomusicce.music.request.SongRequests
import dangomusicce.musicui.HudBackdrop
import dangomusicce.musicui.HudBackground
import dangomusicce.musicui.PlayerIcons
import dangomusicce.musicui.SidebarIcons
import dangomusicce.musicui.fmtTime
import dangomusicce.ui.GlassStyle
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShadowStyle
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.Vec2
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color
import net.minecraft.client.Minecraft
import org.lwjgl.input.Mouse
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

internal object BilibiliRequestHud {
    private const val PADDING = 12f
    private const val HEADER_HEIGHT = 138f
    private const val ROW_HEIGHT = 38f
    private const val NOTICE_PADDING = 8f
    private const val NOTICE_LINE_HEIGHT = 19f
    private const val NOTICE_MAX_LINES = 6
    private const val ADD_SEGMENT_WIDTH = 26f
    private const val SONG_TITLE_SIZE = 12f

    private val READY = Color.fromHex("#A4EBC9")
    private val FAILED = Color.fromHex("#F87794")
    private val WAITING = Color.fromHex("#FFD48B")
    private val DIM_TEXT = Theme.TEXT_SECONDARY.copy(a = 0.84f)

    private val noteList = VectorIcon.path(strokeWidth = 1.75f) {
        move(2.0, 5.5); line(13.0, 5.5)
        move(2.0, 11.0); line(11.0, 11.0)
        move(2.0, 16.5); line(6.5, 16.5)
        move(18.5, 16.0); line(18.5, 4.0); line(22.0, 5.5)
        circle(15.7, 16.9, 2.9)
    }
    private val grip = VectorIcon.path(filled = true) {
        for (x in listOf(9.0, 15.0)) {
            for (y in listOf(5.0, 12.0, 19.0)) circle(x, y, 1.7)
        }
    }
    private val trash = VectorIcon.path(strokeWidth = 1.65f) {
        move(3.5, 6.0); line(20.5, 6.0)
        move(8.0, 6.0); line(9.0, 3.0); line(15.0, 3.0); line(16.0, 6.0)
        move(6.0, 6.5); line(7.0, 21.0); line(17.0, 21.0); line(18.0, 6.5)
        move(10.0, 9.5); line(10.3, 17.5)
        move(14.0, 9.5); line(13.7, 17.5)
    }

    private val enqueueIcon = VectorIcon.path(strokeWidth = 1.7f) {
        move(3.5, 5.5); line(19.0, 5.5)
        move(3.5, 11.5); line(13.0, 11.5)
        move(3.5, 17.5); line(11.5, 17.5)
        move(18.0, 12.5); line(18.0, 22.5)
        move(13.0, 17.5); line(23.0, 17.5)
    }

    private val copyIcon = VectorIcon.path(strokeWidth = 1.7f) {
        roundRect(8.0, 7.0, 11.5, 13.0, 2.0)
        move(15.5, 4.0)
        line(5.5, 4.0)
        line(5.5, 16.0)
    }

    private fun classicLock(locked: Boolean) = VectorIcon.path(strokeWidth = 1.9f) {
        rect(5.5, 10.0, 13.0, 10.0)
        move(8.25, 10.0)
        line(8.25, 6.0)
        line(10.25, 4.0)
        line(13.75, 4.0)
        if (locked) {
            line(15.75, 6.0)
            line(15.75, 10.0)
        } else {
            line(16.5, 5.7)
        }
        move(12.0, 13.5)
        line(12.0, 16.5)
    }

    private fun appleLock(locked: Boolean) = VectorIcon.path(strokeWidth = 1.6f) {
        roundRect(5.5, 10.0, 13.0, 10.0, 2.3)
        move(8.25, 10.0)
        line(8.25, 7.0)
        if (locked) {
            curve(8.25, 2.3, 16.0, 2.3, 16.0, 7.0)
            line(16.0, 10.0)
        } else {
            curve(8.25, 2.2, 14.8, 2.2, 16.8, 5.7)
        }
        move(12.0, 14.25)
        line(12.0, 15.75)
    }

    private class Look(
        tint: String, highlight: String, radius: Float, rowRadius: Float,
        val locked: VectorIcon, val unlocked: VectorIcon,
    ) {
        private val shade = Color.fromHex(tint)
        val panel = GlassStyle(
            radius = radius, tint = shade.copy(a = 0.21f), maxLuma = 0.27f,
            saturation = 1.18f, frost = 0.22f, refraction = 9f, bevel = 10f,
            dispersion = 0.16f, highlight = 0.72f,
            shadow = ShadowStyle(Color.BLACK.copy(a = 0.27f), 12f, Vec2(0f, 3f), outside = true),
            fallback = shade.copy(a = 0.78f),
        )
        val accent = Color.fromHex(highlight)
        val selection = ShapeStyle(accent.copy(a = 0.23f), rowRadius)
        val lift = ShapeStyle(
            shade.copy(a = 0.94f), rowRadius.coerceAtLeast(4f), 1f, accent.copy(a = 0.48f),
            ShadowStyle(Color.BLACK.copy(a = 0.48f), 9f, Vec2(0f, 3f), outside = true),
        )
    }

    private val classic = Look("#401F2D", "#E6668B", 8f, 0f, classicLock(true), classicLock(false))
    private val apple = Look("#233B4C", "#74C7F0", 12f, 6f, appleLock(true), appleLock(false))

    private val text = MixedText().apply {
        useNotoSansSc = true
        hudTextShadow = true
    }
    private val gesture = RequestQueueGesture()
    private val rowPositions = mutableMapOf<Long, Float>()
    private var lifted: LiftedRow? = null
    private var panelPress: PanelPress? = null
    private val commandTargets = mutableMapOf<CommandKey, CommandControls>()
    private var copyFeedback: CopyFeedback? = null
    private var canvas: GuiCanvas? = null
    private val backdrop = HudBackdrop { checkNotNull(canvas) }
    private val hudFontStyles = CjkStyle.REQUEST_HUD to CjkStyle.REQUEST_HUD_BOLD
    private var renderedNoticeHeight = 0f
    private var panelHit: LayoutBounds? = null
    private var primaryText = Theme.TEXT_PRIMARY
    private var secondaryText = DIM_TEXT
    private var scrollRows = 0f
    private var selected: Long? = null
    private var lastFrameTime = 0L

    private data class CommandKey(val openedAt: Long, val trackId: Long) {
        val command get() = "/song $trackId"
    }
    private enum class CommandAction { ENQUEUE, COPY }

    private class CommandControls(private val bounds: LayoutBounds, queued: Boolean) {
        private val left = bounds.copy(width = ADD_SEGMENT_WIDTH)
        private val right = bounds.copy(x = left.right, width = bounds.width - ADD_SEGMENT_WIDTH)
        val enqueue = left.takeUnless { queued }
        val copy = right

        fun contains(x: Float, y: Float) = bounds.containsPoint(x, y)

        fun actionAt(x: Float, y: Float): CommandAction? = when {
            enqueue?.containsPoint(x, y) == true -> CommandAction.ENQUEUE
            copy.containsPoint(x, y) -> CommandAction.COPY
            else -> null
        }
    }

    private data class CopyFeedback(val key: CommandKey, val success: Boolean, val until: Long)

    private sealed interface PanelPress {
        data object Lock : PanelPress

        class Command(val key: CommandKey, val action: CommandAction?, val x: Float, val y: Float) : PanelPress {
            var dragged = false

            fun move(x: Float, y: Float) {
                if (abs(x - this.x) > RequestQueueGesture.DRAG_THRESHOLD ||
                    abs(y - this.y) > RequestQueueGesture.DRAG_THRESHOLD) dragged = true
            }
        }

        class Move(val x: Float, val y: Float, val offset: Vec2) : PanelPress {
            var moved = false
        }
    }

    private class LiftedRow(val id: Long, var x: Float, var y: Float, var emphasis: Float = 1f) {
        fun bounds(layout: Layout) = layout.rows.copy(
            x = layout.rows.x + x, y = layout.rows.y + y, height = layout.rowHeight,
        )
    }

    private data class QueueHit(val control: RequestQueueGesture.Hit, val row: LayoutBounds)

    private class Layout(
        val scale: Float, width: Float, height: Float, cardWidth: Float, bodyHeight: Float,
        val textScale: Float, val lineScale: Float, positionX: Float, positionY: Float, noticeHeight: Float,
    ) {
        private val headerHeight = HEADER_HEIGHT * lineScale
        val rowHeight = ROW_HEIGHT * lineScale
        val travelX = (width - cardWidth).coerceAtLeast(0f)
        val travelY = (height - bodyHeight).coerceAtLeast(0f)
        val panelHeight = bodyHeight + noticeHeight
        private val restingTop = positionY * travelY
        val panel = LayoutBounds(positionX * travelX,
            minOf(restingTop, height - panelHeight).coerceAtLeast(0f), cardWidth, panelHeight)
        val rows = LayoutBounds(panel.x + PADDING, panel.y + headerHeight, cardWidth - PADDING * 2f, bodyHeight - headerHeight - PADDING)
        val noticeTop = rows.bottom + PADDING / 2f
        val visibleRows = rows.height / rowHeight
        val lock = LayoutBounds(panel.right - PADDING - 23f, panel.y + 17f * lineScale - 12f, 23f, 24f)

        fun row(index: Float, offset: Float) =
            rows.copy(y = rows.y + (index - offset) * rowHeight, height = rowHeight)
        fun cover(row: LayoutBounds) = LayoutBounds(row.x + 22f, row.y + (rowHeight - 28f) / 2f, 28f, 28f)
        fun remove(row: LayoutBounds) = LayoutBounds(row.right - 25f, row.y + (rowHeight - 25f) / 2f, 21f, 25f)
        fun dropIndex(rowTop: Float, offset: Float, lastIndex: Int) =
            floor((rowTop - rows.y) / rowHeight + offset + 0.5f).toInt().coerceIn(0, lastIndex)
    }

    private data class NoticeView(
        val notice: RequestNotice,
        val title: String,
        val guidance: List<String>,
        val height: Float,
        val fraction: Float,
    )

    private val module get() = DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)
    private val textScale get() = module.requestHudTextSize.value / 100f
    private val lineScale get() = textScale.coerceAtLeast(1f)
    private val bodyLimit get() = module.requestHudHeight.value.toFloat().coerceAtLeast((HEADER_HEIGHT + ROW_HEIGHT) * lineScale + PADDING)

    private fun scale(mc: Minecraft): Float {
        val maxNoticeHeight = RequestNotice.MAX_VISIBLE * (NOTICE_PADDING * 2f + NOTICE_MAX_LINES * NOTICE_LINE_HEIGHT * lineScale)
        return minOf(mc.displayWidth / 1280f, mc.displayHeight / 800f).coerceIn(0.5f, 1.65f)
            .coerceAtMost(mc.displayWidth / module.requestHudWidth.value.toFloat())
            .coerceAtMost(mc.displayHeight / (bodyLimit + maxNoticeHeight))
    }

    private fun layout(mc: Minecraft, count: Int): Layout {
        val scale = scale(mc)
        val naturalHeight = (HEADER_HEIGHT + count.coerceAtLeast(1) * ROW_HEIGHT) * lineScale + PADDING
        val bodyHeight = if (module.requestHudAutoHeight.get()) minOf(naturalHeight, bodyLimit) else bodyLimit
        val layout = Layout(scale, mc.displayWidth / scale, mc.displayHeight / scale, module.requestHudWidth.value.toFloat(),
            bodyHeight, textScale, lineScale, module.requestPosX.value, module.requestPosY.value, renderedNoticeHeight)
        clampScroll(count, layout)
        return layout
    }

    private fun clampScroll(count: Int, layout: Layout) {
        scrollRows = scrollRows.coerceIn(0f, (count - layout.visibleRows).coerceAtLeast(0f))
    }

    fun isVisible(hudHidden: Boolean): Boolean = BilibiliRequests.enabled && module.allowsRequestHud(hudHidden)

    fun blocksPointer(x: Int, y: Int): Boolean = panelHit?.contains(x, y) == true

    fun render(mc: Minecraft) {
        commandTargets.clear()
        panelHit = null
        if (!isVisible(mc.gameSettings.hideGUI) || mc.theWorld == null || mc.thePlayer == null ||
            mc.displayWidth <= 0 || mc.displayHeight <= 0
        ) return
        val screen = mc.currentScreen
        if (screen != null && !HudPointerInput.owns(screen)) return
        val interactive = HudPointerInput.interacting(screen)
        val snapshot = SongRequests.snapshot()
        val entries = snapshot.entries
        val now = System.nanoTime()
        val (font, fontKey) = LyricFonts.load(module.requestHudFont.value)
        val gfx = DangoClient.gfx
        gfx.text.font(hudFontStyles.first, font, fontKey)
        gfx.text.font(hudFontStyles.second, font, fontKey)
        text.customFontStyles = hudFontStyles.takeIf { font != null }
        val rgb = module.requestHudTextColor.value
        primaryText = if (rgb == 0xFFFFFF) Theme.TEXT_PRIMARY else Color.fromRGB(rgb).copy(a = Theme.TEXT_PRIMARY.a)
        secondaryText = if (rgb == 0xFFFFFF) DIM_TEXT else primaryText.copy(a = DIM_TEXT.a * 0.86f)
        val notices = noticeViews(snapshot.notices, now)
        renderedNoticeHeight = notices.sumOf { (it.height * it.fraction).toDouble() }.toFloat()
        var layout = layout(mc, entries.size)
        val look = if (module.neteaseUsesAppleLayout) apple else classic
        val mouseX = if (interactive) Mouse.getX() / layout.scale else -1f
        val mouseY = if (interactive) (mc.displayHeight - Mouse.getY() - 1) / layout.scale else -1f
        val movingPanel = panelPress as? PanelPress.Move
        if (interactive && movingPanel != null && Mouse.isButtonDown(0) && movePanel(movingPanel, layout, mouseX, mouseY)) {
            layout = layout(mc, entries.size)
        }
        val panel = layout.panel
        panelHit = LayoutBounds(panel.x * layout.scale, panel.y * layout.scale,
            panel.width * layout.scale, panel.height * layout.scale)
        val drag = if (interactive) gesture.move(mouseX, mouseY) else null
        val dragIndex = entries.indexOfFirst { it.id == drag?.id }
        val dt = ((now - lastFrameTime) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameTime = now
        if (dragIndex >= 0 && mouseX >= layout.rows.left - 12f && mouseX < layout.rows.right + 12f) {
            val edge = 17f
            val top = ((layout.rows.top + edge - mouseY) / edge).coerceIn(0f, 1f)
            val bottom = ((mouseY - layout.rows.bottom + edge) / edge).coerceIn(0f, 1f)
            scrollRows += (bottom - top) * dt * 7f
            clampScroll(entries.size, layout)
        }
        val targetIndex = if (drag != null && dragIndex >= 0 && mouseX >= layout.rows.left && mouseX < layout.rows.right) {
            layout.dropIndex(drag.top, scrollRows, entries.lastIndex)
        } else dragIndex
        if (drag != null && dragIndex >= 0) lift(layout, drag)
        animateQueue(layout, entries, dragIndex, targetIndex, dt)
        val hover = if (dragIndex < 0 && panelPress == null) hit(layout, entries, mouseX, mouseY)?.control else null
        val ctx = canvas ?: DangoClient.gfx.newCanvas().also { canvas = it }
        val background = HudBackground.entries[module.requestHudBackground.value]
        val playback = PlaybackView.snapshot()
        ctx.begin(background.capturesWorld, mouseX.toInt(), mouseY.toInt())
        ctx.pushScale(layout.scale, layout.scale, 1f)
        text.clear()
        try {
            val radius = if (module.requestHudSmallCorners.get()) 5f else look.panel.radius
            backdrop.draw(layout.panel, look.panel.copy(radius = radius), background, playback.track, alpha = 0.94f)
            header(ctx, layout, look, mc.session.username, interactive && layout.panel.containsPoint(mouseX, mouseY),
                layout.lock.containsPoint(mouseX, mouseY))
            nowPlaying(ctx, layout, playback)
            queue(ctx, layout, look, entries, hover)
            drawNotices(ctx, layout, look, notices, now, interactive, mouseX, mouseY)
            val floating = lifted
            val entry = entries.firstOrNull { it.id == floating?.id }
            if (floating != null && entry != null) {
                // Paint the floating card after the clipped CJK runs so both fonts stay beneath it.
                text.flush(layout.scale)
                val row = floating.bounds(layout)
                if (entry.id == selected) ctx.drawShape(row, look.selection)
                ctx.pushOpacity(floating.emphasis)
                ctx.drawShape(row, look.lift)
                ctx.popOpacity()
                queueRow(ctx, layout, row, entry, hover?.target.takeIf { hover?.id == entry.id })
            }
            text.flush(layout.scale)
        } finally {
            ctx.popTransform()
            ctx.end()
        }
    }

    private fun header(ctx: GuiCanvas, layout: Layout, look: Look, player: String, showLock: Boolean, lockHovered: Boolean) {
        val left = layout.rows.x
        val y = layout.panel.y + 17f * layout.lineScale
        val titleSize = 14f * layout.textScale
        val nameSize = SONG_TITLE_SIZE * layout.textScale
        PlayerIcons.draw(ctx, noteList, left + 8f, y - 0.5f, 16f, look.accent)
        val name = text.trim(player, layout.rows.width * 0.38f, nameSize)
        val nameX = layout.lock.left - 7f - text.width(name, nameSize)
        val titleX = left + 24f
        val title = text.trim(I18n.text("Song requests"), nameX - titleX - 17f, titleSize, Theme.FONT_SEMIBOLD)
        text.text(ctx, title, titleX, y - titleSize * 0.43f, titleSize, primaryText, Theme.FONT_SEMIBOLD)
        val color = when (BilibiliRequests.state) {
            BilibiliState.CONNECTED -> READY
            BilibiliState.CONNECTING, BilibiliState.RECONNECTING -> WAITING
            BilibiliState.FAILED -> FAILED
            BilibiliState.DISCONNECTED -> DIM_TEXT
        }
        dot(ctx, nameX - 9f, y, color)
        text.text(ctx, name, nameX, y - nameSize * 0.43f, nameSize, primaryText)
        if (!showLock) return
        val locked = module.requestHudLocked.get()
        if (lockHovered) {
            ctx.drawShape(layout.lock, ShapeStyle(look.accent.copy(a = 0.17f), look.panel.radius / 2f))
        }
        PlayerIcons.draw(ctx, if (locked) look.locked else look.unlocked, layout.lock.x + layout.lock.width / 2f, y,
            16f, if (locked) look.accent else DIM_TEXT)
    }

    private fun nowPlaying(ctx: GuiCanvas, layout: Layout, playback: PlaybackView.Snapshot) {
        val left = layout.rows.x
        val line = layout.lineScale
        val titleSize = 14f * layout.textScale
        val detailSize = 11f * layout.textScale
        val top = layout.panel.y + 53f * line
        text.text(ctx, I18n.text("Current track"), left + 2f, top - 17f * line, detailSize, secondaryText)
        val art = LayoutBounds(left + 2f, top + line, 50f, 50f)
        drawCover(ctx, art, playback.track)
        val x = art.right + 10f
        val right = layout.rows.right - 2f
        val track = playback.track
        if (track == null) {
            text.text(ctx, I18n.text("Nothing playing"), x, top + 13f * line, SONG_TITLE_SIZE * layout.textScale, secondaryText)
            return
        }
        text.text(ctx, text.trim(track.name, right - x, titleSize, Theme.FONT_SEMIBOLD),
            x, top, titleSize, primaryText, Theme.FONT_SEMIBOLD)
        text.text(ctx, text.trim(track.artists.joinToString(" / "), right - x, detailSize),
            x, top + 14f * line, detailSize, secondaryText)
        val duration = playback.durationMs.coerceAtLeast(0L)
        val position = playback.positionMs.coerceAtLeast(0L)
        val elapsed = fmtTime(position)
        val total = if (duration > 0L) fmtTime(duration) else "–:––"
        val timeSize = 10f * layout.textScale
        val totalX = right - text.width(total, timeSize)
        val barLeft = x + text.width(elapsed, timeSize) + 7f
        val barY = top + 40f * line
        val bar = LayoutBounds(barLeft, barY, (totalX - barLeft - 7f).coerceAtLeast(0f), 2f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1f))
        val progress = if (duration > 0L) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        if (progress > 0f) {
            val width = bar.width * progress
            ctx.drawShape(bar.copy(width = width), ShapeStyle(Theme.TEXT_PRIMARY, minOf(width / 2f, 1f)))
        }
        val timeY = barY + 1f - timeSize / 2f
        text.text(ctx, elapsed, x, timeY, timeSize, secondaryText)
        text.text(ctx, total, totalX, timeY, timeSize, secondaryText)
    }

    private fun lift(layout: Layout, drag: RequestQueueGesture.Drag) {
        val x = drag.left - layout.rows.x
        val y = drag.top - layout.rows.y
        val current = lifted
        if (current?.id == drag.id) {
            current.x = x
            current.y = y
            current.emphasis = 1f
        } else {
            // A second drag can start while the previous card is still settling.
            if (current != null) rowPositions[current.id] = current.y / layout.rowHeight + scrollRows
            lifted = LiftedRow(drag.id, x, y)
        }
    }

    private fun animateQueue(layout: Layout, entries: List<RequestEntry>, from: Int, to: Int, dt: Float) {
        if (from < 0 && lifted == null && rowPositions.isEmpty()) return
        val remaining = exp(-dt / 0.050f)
        rowPositions.keys.retainAll(entries.map { it.id }.toSet())
        for ((index, entry) in entries.withIndex()) {
            val target = when {
                index == from -> to
                index > from && index <= to -> index - 1
                index < from && index >= to -> index + 1
                else -> index
            }.toFloat()
            val next = target + ((rowPositions[entry.id] ?: index.toFloat()) - target) * remaining
            if (abs(next - target) < 0.01f) {
                if (from >= 0) rowPositions[entry.id] = target else rowPositions.remove(entry.id)
            } else {
                rowPositions[entry.id] = next
            }
        }
        if (from >= 0) return
        val floating = lifted ?: return
        val index = entries.indexOfFirst { it.id == floating.id }
        if (index < 0) {
            lifted = null
            return
        }
        val targetY = layout.row(index.toFloat(), scrollRows).y - layout.rows.y
        floating.x *= remaining
        floating.y = targetY + (floating.y - targetY) * remaining
        floating.emphasis *= remaining
        if (abs(floating.x) < 0.5f && abs(floating.y - targetY) < 0.5f && floating.emphasis < 0.02f) {
            lifted = null
        }
    }

    private fun queue(
        ctx: GuiCanvas, layout: Layout, look: Look, entries: List<RequestEntry>, hover: RequestQueueGesture.Hit?,
    ) {
        val rows = layout.rows
        val title = I18n.text("Request queue")
        val size = SONG_TITLE_SIZE * layout.textScale
        val headerY = rows.y - 20f * layout.lineScale
        ctx.drawShape(LayoutBounds(rows.x, rows.y - 29f * layout.lineScale, rows.width, 1f), ShapeStyle(Theme.HAIRLINE))
        text.text(ctx, title, rows.x + 1f, headerY, size, primaryText, Theme.FONT_MEDIUM)
        text.text(ctx, entries.size.toString(), rows.x + text.width(title, size, Theme.FONT_MEDIUM) + 9f,
            headerY, size, secondaryText)
        if (entries.isEmpty()) {
            selected = null
            text.text(ctx, I18n.text("No song requests"), rows.x + 1f, rows.y + 12f * layout.lineScale, size, secondaryText)
            return
        }
        ctx.pushClip(rows)
        text.clip = rows
        for ((index, entry) in entries.withIndex()) {
            if (entry.id == lifted?.id) continue
            val row = layout.row(rowPositions[entry.id] ?: index.toFloat(), scrollRows)
            if (row.top >= rows.bottom || row.bottom <= rows.top) continue
            if (entry.id == selected || entry.id == hover?.id) ctx.drawShape(row, look.selection)
            if (look === classic && index < entries.lastIndex) {
                ctx.drawShape(LayoutBounds(row.x, row.bottom - 0.75f, row.width, 0.75f), ShapeStyle(Theme.HAIRLINE))
            }
            queueRow(ctx, layout, row, entry, hover?.target.takeIf { hover?.id == entry.id })
        }
        text.clip = null
        ctx.popClip()
        if (entries.size > layout.visibleRows) {
            val height = rows.height * layout.visibleRows / entries.size
            val top = rows.y + (rows.height - height) * scrollRows / (entries.size - layout.visibleRows)
            ctx.drawShape(LayoutBounds(rows.right + 4f, top, 2f, height), ShapeStyle(Theme.TEXT_TERTIARY, 1f))
        }
    }

    private fun queueRow(
        ctx: GuiCanvas, layout: Layout, row: LayoutBounds, entry: RequestEntry, hover: RequestQueueGesture.Target?,
    ) {
        val centerY = row.y + row.height / 2f
        PlayerIcons.draw(ctx, grip, row.x + 10f, centerY, 13f, DIM_TEXT)
        val cover = layout.cover(row)
        drawCover(ctx, cover, entry.track)
        if (hover == RequestQueueGesture.Target.PLAY) {
            ctx.drawShape(cover, ShapeStyle(Color.BLACK.copy(a = 0.68f), 6f))
            PlayerIcons.draw(ctx, PlayerIcons.play, cover.x + cover.width / 2f, centerY, 19f, Theme.TEXT_PRIMARY)
        }
        val textX = cover.right + 7f
        val statusX = row.right - 43f
        val available = statusX - textX - 10f
        val titleSize = SONG_TITLE_SIZE * layout.textScale
        val detailSize = 11f * layout.textScale
        text.text(ctx, text.trim(entry.track.name, available, titleSize, Theme.FONT_SEMIBOLD),
            textX, row.y + 5f * layout.lineScale, titleSize, primaryText, Theme.FONT_SEMIBOLD)
        val requester = text.trim(I18n.format("Requested by %s", entry.user), available * 0.64f, detailSize)
        val separator = " · "
        val artistWidth = available - text.width("$separator$requester", detailSize)
        val artist = text.trim(entry.track.artists.joinToString(" / "), artistWidth, detailSize)
        val details = if (artist.isNotEmpty()) "$artist$separator$requester" else requester
        text.text(ctx, details, textX, row.y + 20f * layout.lineScale, detailSize, secondaryText)
        when (entry.cache) {
            RequestCacheState.READY -> dot(ctx, statusX, centerY, READY)
            RequestCacheState.LOADING -> PlayerIcons.loadingRing(ctx, statusX, centerY, null, 14f)
            RequestCacheState.FAILED -> dot(ctx, statusX, centerY, FAILED)
        }
        val remove = layout.remove(row)
        val removeColor = if (hover == RequestQueueGesture.Target.REMOVE) FAILED else Theme.TEXT_PRIMARY
        PlayerIcons.draw(ctx, trash, remove.x + remove.width / 2f, centerY, 13f, removeColor)
    }

    private fun noticeViews(notices: List<RequestNotice>, now: Long): List<NoticeView> {
        val size = SONG_TITLE_SIZE * textScale
        val width = module.requestHudWidth.value - PADDING * 2f
        return notices.mapNotNull { notice ->
            val fraction = notice.reveal(now)
            if (fraction <= 0f) return@mapNotNull null
            val message = when (notice.kind) {
                RequestNoticeKind.SKIPPED -> I18n.format("Unable to play “%s”. Skipped.", notice.title)
                RequestNoticeKind.NOT_FOUND -> I18n.format("No matching song for “%s”.", notice.title)
                RequestNoticeKind.AMBIGUOUS -> I18n.format("“%s” matched %s songs.", notice.title, notice.matchCount)
                RequestNoticeKind.QUEUE_FULL -> I18n.text("The request queue is full.")
                RequestNoticeKind.SEARCH_ERROR -> I18n.format("Could not search for “%s”.", notice.title)
                RequestNoticeKind.INVALID_LINK -> I18n.text("Could not read the link. Try /song with the song ID.")
            }
            val guide = if (notice.kind == RequestNoticeKind.AMBIGUOUS)
                text.wrap2(I18n.text("Hold the HUD key. Left adds to the queue. Right copies the chat command."), width, size)
            else emptyList()
            val lines = 1 + guide.size + notice.candidates.size
            NoticeView(notice, text.trim(message, width, size, Theme.FONT_SEMIBOLD), guide,
                NOTICE_PADDING * 2f + lines * NOTICE_LINE_HEIGHT * lineScale, fraction)
        }
    }

    private fun drawNotices(
        ctx: GuiCanvas, layout: Layout, look: Look, notices: List<NoticeView>, now: Long,
        interactive: Boolean, mouseX: Float, mouseY: Float,
    ) {
        var y = layout.noticeTop
        val x = layout.rows.x
        val right = layout.rows.right
        val lineHeight = NOTICE_LINE_HEIGHT * layout.lineScale
        val size = SONG_TITLE_SIZE * layout.textScale
        for (view in notices) {
            val height = view.height * view.fraction
            val bounds = LayoutBounds(x, y, layout.rows.width, height)
            y += height
            if (height < 1f) continue
            ctx.pushOpacity(view.fraction)
            ctx.pushClip(bounds)
            text.clip = bounds
            try {
                ctx.drawShape(bounds.copy(height = 1f), ShapeStyle(Theme.HAIRLINE))
                var top = bounds.top + NOTICE_PADDING
                text.text(ctx, view.title, x, top, size, primaryText, Theme.FONT_SEMIBOLD)
                top += lineHeight
                for (line in view.guidance) {
                    text.text(ctx, line, x, top, size, primaryText)
                    top += lineHeight
                }
                for (candidate in view.notice.candidates) {
                    val key = CommandKey(view.notice.opensAt, candidate.id)
                    val label = listOf(candidate.artist, candidate.album).filter(String::isNotBlank).joinToString(" · ")
                    val commandWidth = text.width(key.command, size, Theme.FONT_SEMIBOLD)
                    val chipWidth = minOf(commandWidth + 28f + ADD_SEGMENT_WIDTH, bounds.width)
                    val chip = LayoutBounds(right - chipWidth, top - 2f, chipWidth, lineHeight)
                    val controls = CommandControls(chip, candidate.queued)
                    val shown = chip.intersect(bounds).height >= chip.height - 0.01f
                    if (interactive && shown) commandTargets[key] = controls
                    val hover = if (interactive && shown) controls.actionAt(mouseX, mouseY) else null
                    val feedback = copyFeedback?.takeIf { it.key == key && now < it.until }
                    val copyColor = when {
                        feedback != null -> if (feedback.success) READY else FAILED
                        hover == CommandAction.COPY -> look.accent
                        else -> primaryText
                    }
                    val addColor = when {
                        candidate.queued -> READY
                        hover == CommandAction.ENQUEUE -> look.accent
                        else -> primaryText
                    }
                    ctx.drawShape(chip, ShapeStyle(primaryText.copy(a = 0.10f), 5f))
                    val highlight = when (hover) {
                        CommandAction.ENQUEUE -> controls.enqueue
                        CommandAction.COPY -> controls.copy
                        null -> null
                    }
                    if (highlight != null) {
                        ctx.pushClip(highlight)
                        try {
                            ctx.drawShape(chip, ShapeStyle(look.accent.copy(a = 0.12f), 5f))
                        } finally {
                            ctx.popClip()
                        }
                    }
                    ctx.drawShape(LayoutBounds(controls.copy.left, chip.y + 3f, 1f, chip.height - 6f),
                        ShapeStyle(primaryText.copy(a = 0.35f)))
                    val centerY = chip.y + chip.height / 2f
                    PlayerIcons.draw(ctx, enqueueIcon, chip.x + ADD_SEGMENT_WIDTH / 2f, centerY, 13f, addColor)
                    PlayerIcons.draw(ctx, copyIcon, controls.copy.x + 11f, centerY, 13f, copyColor)
                    val displayed = when (feedback?.success) {
                        true -> I18n.text("Command copied")
                        false -> I18n.text("Copy failed")
                        null -> when (hover) {
                            CommandAction.ENQUEUE -> I18n.text("Add to queue")
                            CommandAction.COPY -> I18n.text("Copy command")
                            null -> key.command
                        }
                    }
                    text.text(ctx, text.trim(label, chip.x - x - 6f, size), x, top, size, primaryText)
                    text.text(ctx, text.trim(displayed, controls.copy.width - 24f, size, Theme.FONT_SEMIBOLD),
                        controls.copy.x + 21f, top, size, copyColor, Theme.FONT_SEMIBOLD)
                    top += lineHeight
                }
            } finally {
                text.clip = null
                ctx.popClip()
                ctx.popOpacity()
            }
        }
    }

    private fun dot(ctx: GuiCanvas, x: Float, y: Float, color: Color) {
        ctx.drawShape(LayoutBounds(x - 3f, y - 3f, 6f, 6f), ShapeStyle(color, 3f))
    }

    private fun drawCover(ctx: GuiCanvas, bounds: LayoutBounds, song: NeteaseTrack?) {
        val image = song?.let(CoverCache::track)
        if (image != null) ctx.drawRoundedImage(bounds, image, 6f)
        else {
            ctx.drawShape(bounds, ShapeStyle(Theme.TRACK, 6f))
            SidebarIcons.draw(ctx, "♫", bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f,
                Theme.TEXT_SECONDARY, 15f)
        }
    }

    private fun hit(layout: Layout, entries: List<RequestEntry>, x: Float, y: Float): QueueHit? {
        if (!layout.rows.containsPoint(x, y)) return null
        val floating = lifted
        if (floating != null && entries.any { it.id == floating.id }) {
            val row = floating.bounds(layout)
            if (row.containsPoint(x, y)) return rowHit(layout, floating.id, row, x, y)
        }
        for (index in entries.indices.reversed()) {
            val id = entries[index].id
            if (id == floating?.id) continue
            val row = layout.row(rowPositions[id] ?: index.toFloat(), scrollRows)
            if (row.containsPoint(x, y)) return rowHit(layout, id, row, x, y)
        }
        return null
    }

    private fun rowHit(layout: Layout, id: Long, row: LayoutBounds, x: Float, y: Float): QueueHit {
        val target = when {
            layout.cover(row).containsPoint(x, y) -> RequestQueueGesture.Target.PLAY
            layout.remove(row).containsPoint(x, y) -> RequestQueueGesture.Target.REMOVE
            else -> RequestQueueGesture.Target.SELECT
        }
        return QueueHit(RequestQueueGesture.Hit(id, target), row)
    }

    private fun LayoutBounds.containsPoint(x: Float, y: Float) =
        x >= left && x < right && y >= top && y < bottom

    fun press(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val entries = SongRequests.snapshot().entries
        val layout = layout(mc, entries.size)
        val x = pixelX / layout.scale
        val y = pixelY / layout.scale
        gesture.cancel()
        val queueHit = hit(layout, entries, x, y)
        val command = commandTargets.entries.firstOrNull { it.value.contains(x, y) }
        panelPress = when {
            command != null -> PanelPress.Command(command.key, command.value.actionAt(x, y), x, y)
            layout.lock.containsPoint(x, y) -> PanelPress.Lock
            queueHit != null -> {
                gesture.down(queueHit.control, x, y, queueHit.row.x, queueHit.row.y)
                null
            }
            !module.requestHudLocked.get() && layout.panel.containsPoint(x, y) ->
                PanelPress.Move(x, y, Vec2(x - layout.panel.x, y - layout.panel.y))
            else -> null
        }
    }

    fun movePointer(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val press = panelPress
        if (press is PanelPress.Move) {
            val layout = layout(mc, SongRequests.snapshot().entries.size)
            movePanel(press, layout, pixelX / layout.scale, pixelY / layout.scale)
            return
        }
        val scale = scale(mc)
        if (press is PanelPress.Command) press.move(pixelX / scale, pixelY / scale)
        else gesture.move(pixelX / scale, pixelY / scale)
    }

    private fun movePanel(press: PanelPress.Move, layout: Layout, x: Float, y: Float): Boolean {
        if (abs(x - press.x) > RequestQueueGesture.DRAG_THRESHOLD || abs(y - press.y) > RequestQueueGesture.DRAG_THRESHOLD) {
            press.moved = true
        }
        if (!press.moved) return false
        if (layout.travelX > 0f) module.requestPosX.setValue((x - press.offset.x) / layout.travelX)
        if (layout.travelY > 0f) module.requestPosY.setValue((y - press.offset.y) / layout.travelY)
        return true
    }

    fun release(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val snapshot = SongRequests.snapshot()
        val entries = snapshot.entries
        val layout = layout(mc, entries.size)
        val x = pixelX / layout.scale
        val y = pixelY / layout.scale
        when (val press = panelPress) {
            is PanelPress.Command -> {
                panelPress = null
                press.move(x, y)
                val action = press.action ?: return
                if (press.dragged || commandTargets[press.key]?.actionAt(x, y) != action) return
                if (action == CommandAction.ENQUEUE) {
                    SongRequests.enqueueCandidate(press.key.openedAt, press.key.trackId)
                } else if (snapshot.notices.any {
                    it.opensAt == press.key.openedAt && it.candidates.any { candidate -> candidate.id == press.key.trackId }
                }) {
                    val command = press.key.command
                    val platform = DangoClient.platform
                    val copied = runCatching {
                        platform.clipboard = command
                        platform.clipboard == command
                    }.getOrDefault(false)
                    copyFeedback = CopyFeedback(press.key, copied, System.nanoTime() + 1_500_000_000L)
                }
                return
            }
            is PanelPress.Move -> {
                movePanel(press, layout, x, y)
                finishPanelPress()
                return
            }
            PanelPress.Lock -> {
                panelPress = null
                if (layout.lock.containsPoint(x, y)) {
                    val locked = module.requestHudLocked
                    locked.set(!locked.get())
                    DangoMusic.instance.configManager.saveIfChanged()
                }
                return
            }
            null -> Unit
        }
        val drag = gesture.move(x, y)
        val from = entries.indexOfFirst { it.id == drag?.id }
        if (drag != null && from >= 0) lift(layout, drag)
        val drop = if (drag != null && from >= 0 && entries.size > 1 && layout.rows.containsPoint(x, y)) {
            val target = layout.dropIndex(drag.top, scrollRows, entries.lastIndex)
            val before = entries.getOrNull(if (target >= from) target + 1 else target)
            RequestQueueGesture.Drop(before?.id)
        } else null
        when (val action = gesture.up(hit(layout, entries, x, y)?.control, x, y, drop)) {
            is RequestQueueGesture.Command.Move -> SongRequests.move(action.id, action.beforeId)
            is RequestQueueGesture.Command.Click -> when (action.hit.target) {
                RequestQueueGesture.Target.SELECT -> selected = action.hit.id
                RequestQueueGesture.Target.PLAY -> ClientMusic.control { SongRequests.play(action.hit.id) }
                RequestQueueGesture.Target.REMOVE -> SongRequests.remove(action.hit.id)
            }
            null -> Unit
        }
    }

    fun scroll(mc: Minecraft, pixelX: Int, pixelY: Int, wheel: Int) {
        if (panelPress != null) return
        val count = SongRequests.snapshot().entries.size
        val layout = layout(mc, count)
        if (!layout.rows.containsPoint(pixelX / layout.scale, pixelY / layout.scale)) return
        scrollRows -= wheel.coerceIn(-120, 120) / 120f
        clampScroll(count, layout)
    }

    private fun finishPanelPress() {
        val moved = (panelPress as? PanelPress.Move)?.moved == true
        panelPress = null
        if (moved) DangoMusic.instance.configManager.saveIfChanged()
    }

    fun cancelGesture() {
        gesture.cancel()
        finishPanelPress()
    }
}
