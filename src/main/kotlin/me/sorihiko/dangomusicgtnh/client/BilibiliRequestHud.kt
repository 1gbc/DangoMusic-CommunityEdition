package me.sorihiko.dangomusicgtnh.client

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.font.MixedText
import me.sorihiko.dangomusicgtnh.gfx.GuiCanvas
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.music.request.RequestCacheState
import me.sorihiko.dangomusicgtnh.music.request.RequestEntry
import me.sorihiko.dangomusicgtnh.music.request.RequestNoticeKind
import me.sorihiko.dangomusicgtnh.music.request.SongRequests
import me.sorihiko.dangomusicgtnh.musicui.PlayerIcons
import me.sorihiko.dangomusicgtnh.musicui.SidebarIcons
import me.sorihiko.dangomusicgtnh.musicui.TimedNotice
import me.sorihiko.dangomusicgtnh.musicui.fmtTime
import me.sorihiko.dangomusicgtnh.ui.GlassStyle
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color
import net.minecraft.client.Minecraft
import org.lwjgl.input.Mouse
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

internal object BilibiliRequestHud {
    private const val WIDTH = 318f
    private const val MARGIN = 18f
    private const val PADDING = 12f
    private const val HEADER_HEIGHT = 128f
    private const val ROW_HEIGHT = 38f
    private const val MAX_VISIBLE_ROWS = 4
    private const val NOTICE_HEIGHT = 25f
    private const val NOTICE_GAP = 5f

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

    private fun classicLock(locked: Boolean) = VectorIcon.path(strokeWidth = 1.9f) {
        rect(5.5, 10.0, 13.0, 10.0)
        move(8.25, 10.0)
        line(8.25, 6.0)
        line(10.25, 4.0)
        line(13.75, 4.0)
        line(15.75, 6.0)
        line(15.75, if (locked) 10.0 else 7.0)
        move(12.0, 13.5)
        line(12.0, 16.5)
    }

    private fun appleLock(locked: Boolean) = VectorIcon.path(strokeWidth = 1.6f) {
        roundRect(5.5, 10.0, 13.0, 10.0, 2.3)
        move(8.25, 10.0)
        line(8.25, 7.0)
        curve(8.25, 2.3, 16.0, 2.3, 16.0, 7.0)
        if (locked) line(16.0, 10.0)
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

    private val text = MixedText().apply { useNotoSansSc = true }
    private val gesture = RequestQueueGesture()
    private val rowPositions = mutableMapOf<Long, Float>()
    private var lifted: LiftedRow? = null
    private var panelPress: PanelPress? = null
    private var canvas: GuiCanvas? = null
    private var scrollRows = 0f
    private var selected: Long? = null
    private var lastFrameTime = 0L

    private sealed interface PanelPress {
        data object Lock : PanelPress

        class Move(val x: Float, val y: Float, val offset: Vec2) : PanelPress {
            var moved = false
        }
    }

    private class LiftedRow(val id: Long, var x: Float, var y: Float, var emphasis: Float = 1f) {
        fun bounds(layout: Layout) = layout.rows.copy(
            x = layout.rows.x + x, y = layout.rows.y + y, height = ROW_HEIGHT,
        )
    }

    private data class QueueHit(val control: RequestQueueGesture.Hit, val row: LayoutBounds)

    private class Layout(
        val scale: Float, width: Float, val height: Float, rowCount: Int, positionX: Float, positionY: Float,
    ) {
        private val panelHeight = HEADER_HEIGHT + rowCount * ROW_HEIGHT + PADDING
        val travelX = (width - WIDTH - MARGIN * 2f).coerceAtLeast(0f)
        val travelY = (height - panelHeight - MARGIN * 2f).coerceAtLeast(0f)
        val panel = LayoutBounds(MARGIN + positionX * travelX, MARGIN + positionY * travelY, WIDTH, panelHeight)
        val rows = LayoutBounds(panel.x + PADDING, panel.y + HEADER_HEIGHT, WIDTH - PADDING * 2f, rowCount * ROW_HEIGHT)
        val lock = LayoutBounds(panel.right - PADDING - 23f, panel.y + 5f, 23f, 24f)

        fun row(index: Float, offset: Float) =
            rows.copy(y = rows.y + (index - offset) * ROW_HEIGHT, height = ROW_HEIGHT)
        fun cover(row: LayoutBounds) = LayoutBounds(row.x + 22f, row.y + 5f, 28f, 28f)
        fun remove(row: LayoutBounds) = LayoutBounds(row.right - 25f, row.y + (ROW_HEIGHT - 25f) / 2f, 21f, 25f)
        fun dropIndex(rowTop: Float, offset: Float, lastIndex: Int) =
            floor((rowTop - rows.y) / ROW_HEIGHT + offset + 0.5f).toInt().coerceIn(0, lastIndex)
    }

    private val module get() = DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)

    private fun scale(mc: Minecraft) =
        minOf(mc.displayWidth / 1280f, mc.displayHeight / 800f).coerceIn(0.5f, 1.65f)
            .coerceAtMost(mc.displayWidth / (WIDTH + MARGIN * 2f))
            .coerceAtMost(mc.displayHeight / (HEADER_HEIGHT + ROW_HEIGHT * MAX_VISIBLE_ROWS + PADDING + MARGIN * 2f))

    private fun layout(mc: Minecraft, count: Int): Layout {
        val rowCount = count.coerceIn(1, MAX_VISIBLE_ROWS)
        clampScroll(count)
        val scale = scale(mc)
        return Layout(scale, mc.displayWidth / scale, mc.displayHeight / scale,
            rowCount, module.requestPosX.value, module.requestPosY.value)
    }

    private fun clampScroll(count: Int) {
        scrollRows = scrollRows.coerceIn(0f, (count - MAX_VISIBLE_ROWS).coerceAtLeast(0).toFloat())
    }

    fun render(mc: Minecraft) {
        if (!BilibiliRequests.enabled || mc.theWorld == null || mc.thePlayer == null ||
            mc.displayWidth <= 0 || mc.displayHeight <= 0
        ) return
        val screen = mc.currentScreen
        if (screen != null && !BilibiliRequestInput.owns(screen)) return
        val interactive = BilibiliRequestInput.interacting(screen)
        val snapshot = SongRequests.snapshot()
        val entries = snapshot.entries
        var layout = layout(mc, entries.size)
        val look = if (module.neteaseUsesAppleLayout) apple else classic
        val mouseX = if (interactive) Mouse.getX() / layout.scale else -1f
        val mouseY = if (interactive) (mc.displayHeight - Mouse.getY() - 1) / layout.scale else -1f
        val movingPanel = panelPress as? PanelPress.Move
        if (interactive && movingPanel != null && Mouse.isButtonDown(0) && movePanel(movingPanel, layout, mouseX, mouseY)) {
            layout = layout(mc, entries.size)
        }
        val drag = if (interactive) gesture.move(mouseX, mouseY) else null
        val dragIndex = entries.indexOfFirst { it.id == drag?.id }
        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameTime = now
        if (dragIndex >= 0 && mouseX >= layout.rows.left - 12f && mouseX < layout.rows.right + 12f) {
            val edge = 17f
            val top = ((layout.rows.top + edge - mouseY) / edge).coerceIn(0f, 1f)
            val bottom = ((mouseY - layout.rows.bottom + edge) / edge).coerceIn(0f, 1f)
            scrollRows += (bottom - top) * dt * 7f
            clampScroll(entries.size)
        }
        val targetIndex = if (drag != null && dragIndex >= 0 && mouseX >= layout.rows.left && mouseX < layout.rows.right) {
            layout.dropIndex(drag.top, scrollRows, entries.lastIndex)
        } else dragIndex
        if (drag != null && dragIndex >= 0) lift(layout, drag)
        animateQueue(layout, entries, dragIndex, targetIndex, dt)
        val hover = if (dragIndex < 0 && panelPress == null) hit(layout, entries, mouseX, mouseY)?.control else null
        val ctx = canvas ?: DangoClient.gfx.newCanvas().also { canvas = it }
        ctx.begin(true, mouseX.toInt(), mouseY.toInt())
        ctx.pushScale(layout.scale, layout.scale, 1f)
        text.clear()
        try {
            drawPanel(ctx, layout.panel, look.panel)
            header(ctx, layout, look, mc.session.username, layout.lock.containsPoint(mouseX, mouseY))
            nowPlaying(ctx, layout, PlaybackView.snapshot())
            queue(ctx, layout, look, entries, hover)
            snapshot.notice?.let { notice ->
                val message = when (notice.kind) {
                    RequestNoticeKind.SKIPPED -> I18n.format("Unable to play “%s”. Skipped.", notice.title)
                    RequestNoticeKind.NOT_FOUND -> I18n.format("No matching song for “%s”.", notice.title)
                    RequestNoticeKind.AMBIGUOUS -> I18n.format("Multiple songs match “%s”.", notice.title)
                    RequestNoticeKind.QUEUE_FULL -> I18n.text("The request queue is full.")
                    RequestNoticeKind.SEARCH_ERROR -> I18n.format("Could not search for “%s”.", notice.title)
                }
                notice(ctx, layout, look, TimedNotice(message, notice.createdAtNanos), now)
            }
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

    private fun drawPanel(ctx: GuiCanvas, bounds: LayoutBounds, style: GlassStyle) {
        ctx.pushOpacity(0.94f)
        ctx.drawGlass(bounds, style)
        ctx.popOpacity()
    }

    private fun header(ctx: GuiCanvas, layout: Layout, look: Look, player: String, lockHovered: Boolean) {
        val left = layout.rows.x
        val y = layout.panel.y + 17f
        PlayerIcons.draw(ctx, noteList, left + 8f, y - 0.5f, 16f, look.accent)
        text.text(ctx, I18n.text("Song requests"), left + 24f, y - 6f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val name = text.trim(player, 132f, 12f)
        val nameX = layout.lock.left - 7f - text.width(name, 12f)
        val color = when (BilibiliRequests.state) {
            BilibiliState.CONNECTED -> READY
            BilibiliState.CONNECTING, BilibiliState.RECONNECTING -> WAITING
            BilibiliState.FAILED -> FAILED
            BilibiliState.DISCONNECTED -> DIM_TEXT
        }
        dot(ctx, nameX - 9f, y, color)
        text.text(ctx, name, nameX, y - 5f, 12f, Theme.TEXT_PRIMARY)
        val locked = module.requestHudLocked.get()
        if (lockHovered) {
            ctx.drawShape(layout.lock, ShapeStyle(look.accent.copy(a = 0.17f), look.panel.radius / 2f))
        }
        PlayerIcons.draw(ctx, if (locked) look.locked else look.unlocked, layout.lock.x + layout.lock.width / 2f, y,
            16f, if (locked || lockHovered) look.accent else DIM_TEXT)
    }

    private fun nowPlaying(ctx: GuiCanvas, layout: Layout, playback: PlaybackView.Snapshot) {
        val left = layout.rows.x
        val p = LayoutBounds(left, layout.panel.y + 53f, layout.rows.width, 47f)
        text.text(ctx, I18n.text("Current track"), left + 2f, p.y - 17f, 11f, DIM_TEXT)
        val art = LayoutBounds(p.x + 2f, p.y + 2f, 34f, 34f)
        drawCover(ctx, art, playback.track)
        val x = art.right + 10f
        val right = p.right - 2f
        val track = playback.track
        if (track == null) {
            text.text(ctx, I18n.text("Nothing playing"), x, p.y + 13f, 12f, DIM_TEXT)
            return
        }
        text.text(ctx, text.trim(track.name, right - x, 14f, Theme.FONT_SEMIBOLD),
            x, p.y, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text.text(ctx, text.trim(track.artists.joinToString(" / "), right - x, 11f),
            x, p.y + 15f, 11f, DIM_TEXT)
        val duration = playback.durationMs.coerceAtLeast(0L)
        val position = playback.positionMs.coerceAtLeast(0L)
        val stamp = "${fmtTime(position)} / ${if (duration > 0L) fmtTime(duration) else "–:––"}"
        val stampX = right - text.width(stamp, 10f)
        val bar = LayoutBounds(x, p.bottom - 13f, stampX - x - 7f, 2f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1f))
        val progress = if (duration > 0L) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        if (progress > 0f) {
            val width = bar.width * progress
            ctx.drawShape(bar.copy(width = width), ShapeStyle(Theme.TEXT_PRIMARY, minOf(width / 2f, 1f)))
        }
        text.text(ctx, stamp, stampX, p.bottom - 18f, 10f, DIM_TEXT)
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
            if (current != null) rowPositions[current.id] = current.y / ROW_HEIGHT + scrollRows
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
        val headerY = rows.y - 20f
        ctx.drawShape(LayoutBounds(rows.x, rows.y - 29f, rows.width, 1f), ShapeStyle(Theme.HAIRLINE))
        text.text(ctx, title, rows.x + 1f, headerY, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        text.text(ctx, entries.size.toString(), rows.x + text.width(title, 12f, Theme.FONT_MEDIUM) + 9f,
            headerY, 12f, DIM_TEXT)
        if (entries.isEmpty()) {
            selected = null
            text.text(ctx, I18n.text("No song requests"), rows.x + 1f, rows.y + 12f, 12f, DIM_TEXT)
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
        if (entries.size > MAX_VISIBLE_ROWS) {
            val height = rows.height * MAX_VISIBLE_ROWS / entries.size
            val top = rows.y + (rows.height - height) * scrollRows / (entries.size - MAX_VISIBLE_ROWS)
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
        text.text(ctx, text.trim(entry.track.name, available, 12f, Theme.FONT_SEMIBOLD),
            textX, row.y + 5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        // Reserve part of the line for the requester even when the artist credit is long.
        val requester = text.trim(I18n.format("Requested by %s", entry.user), available * 0.64f, 11f)
        val artist = entry.track.artists.joinToString(" / ")
        val details = if (artist.isEmpty()) requester else {
            val separator = " · "
            val artistWidth = available - text.width("$separator$requester", 11f)
            text.trim(artist, artistWidth, 11f).takeIf { it.isNotEmpty() }?.let { "$it$separator$requester" } ?: requester
        }
        text.text(ctx, details, textX, row.y + 20f, 11f, DIM_TEXT)
        when (entry.cache) {
            RequestCacheState.READY -> dot(ctx, statusX, centerY, READY)
            RequestCacheState.LOADING -> PlayerIcons.loadingRing(ctx, statusX, centerY, null, 14f)
            RequestCacheState.FAILED -> dot(ctx, statusX, centerY, FAILED)
        }
        val remove = layout.remove(row)
        val removeColor = if (hover == RequestQueueGesture.Target.REMOVE) FAILED else Theme.TEXT_PRIMARY
        PlayerIcons.draw(ctx, trash, remove.x + remove.width / 2f, centerY, 13f, removeColor)
    }

    private fun notice(ctx: GuiCanvas, layout: Layout, look: Look, notice: TimedNotice, now: Long) {
        val opacity = notice.opacity(now)
        if (opacity <= 0f) return
        val below = layout.panel.bottom + NOTICE_GAP
        val above = layout.panel.top - NOTICE_GAP - NOTICE_HEIGHT
        val top = when {
            below + NOTICE_HEIGHT <= layout.height -> below
            above >= 0f -> above
            else -> layout.height - NOTICE_HEIGHT
        }
        val bounds = LayoutBounds(layout.panel.x, top, WIDTH, NOTICE_HEIGHT)
        ctx.pushOpacity(opacity)
        drawPanel(ctx, bounds, look.panel.copy(radius = 7f, shadow = ShadowStyle()))
        text.text(ctx, text.trim(notice.text, WIDTH - PADDING * 2f, 11f),
            bounds.x + PADDING, bounds.y + 8f, 11f, Theme.TEXT_PRIMARY)
        ctx.popOpacity()
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
        panelPress = when {
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
        gesture.move(pixelX / scale, pixelY / scale)
    }

    private fun movePanel(press: PanelPress.Move, layout: Layout, x: Float, y: Float): Boolean {
        if (abs(x - press.x) > RequestQueueGesture.DRAG_THRESHOLD || abs(y - press.y) > RequestQueueGesture.DRAG_THRESHOLD) {
            press.moved = true
        }
        if (!press.moved) return false
        if (layout.travelX > 0f) module.requestPosX.setValue((x - press.offset.x - MARGIN) / layout.travelX)
        if (layout.travelY > 0f) module.requestPosY.setValue((y - press.offset.y - MARGIN) / layout.travelY)
        return true
    }

    fun release(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val entries = SongRequests.snapshot().entries
        val layout = layout(mc, entries.size)
        val x = pixelX / layout.scale
        val y = pixelY / layout.scale
        when (val press = panelPress) {
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
        clampScroll(count)
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
