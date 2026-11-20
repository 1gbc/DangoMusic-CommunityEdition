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
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.ui.VectorIcon
import me.sorihiko.dangomusicgtnh.utils.Color
import net.minecraft.client.Minecraft
import org.lwjgl.input.Mouse
import kotlin.math.floor

internal object BilibiliRequestHud {
    private const val WIDTH = 348f
    private const val MARGIN = 18f
    private const val PADDING = 13f
    private const val HEADER_HEIGHT = 161f
    private const val ROW_HEIGHT = 43f
    private const val MAX_VISIBLE_ROWS = 4

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

    private class Look(
        top: String, bottom: String, edge: String, highlight: String,
        val radius: Float, val rowRadius: Float,
    ) {
        val panel = ShapeStyle(
            Color.fromHex(top).copy(a = 0.95f), radius, 1f, Color.fromHex(edge).copy(a = 0.64f),
            ShadowStyle(Color.BLACK.copy(a = 0.27f), 15f, Vec2(0f, 5f), outside = true),
            fillEndColor = Color.fromHex(bottom).copy(a = 0.96f),
        )
        val accent = Color.fromHex(highlight)
        val selection = ShapeStyle(accent.copy(a = 0.23f), rowRadius)
    }

    private val classic = Look("#492230", "#301923", "#A34E65", "#E6668B", 14f, 0f)
    private val apple = Look("#3B5A70", "#1C3240", "#7C9DB5", "#74C7F0", 21f, 7f)
    private val capsule = ShapeStyle(
        Theme.CARD, 28f, 1f, Theme.HAIRLINE.copy(a = 0.15f), fillEndColor = Theme.CARD_HOVER,
    )

    private val text = MixedText().apply { useNotoSansSc = true }
    private val gesture = RequestQueueGesture()
    private var canvas: GuiCanvas? = null
    private var scrollRows = 0f
    private var selected: Long? = null
    private var lastFrameTime = 0L

    private class Layout(val scale: Float, width: Float, rowCount: Int) {
        val left = width / scale - MARGIN - WIDTH
        val panel = LayoutBounds(left, MARGIN, WIDTH, HEADER_HEIGHT + rowCount * ROW_HEIGHT + PADDING)
        val rows = LayoutBounds(left + PADDING, panel.y + HEADER_HEIGHT, WIDTH - PADDING * 2f, rowCount * ROW_HEIGHT)
        fun row(index: Int, offset: Float) =
            rows.copy(y = rows.y + (index - offset) * ROW_HEIGHT, height = ROW_HEIGHT)
        fun cover(row: LayoutBounds) = LayoutBounds(row.x + 24f, row.y + 6.5f, 30f, 30f)
        fun remove(row: LayoutBounds) = LayoutBounds(row.right - 25f, row.y + 9f, 21f, 25f)
        fun rowIndex(y: Float, offset: Float) = floor((y - rows.y) / ROW_HEIGHT + offset).toInt()
        fun dropIndex(y: Float, offset: Float) = rowIndex(y + ROW_HEIGHT / 2f, offset)
    }

    private fun scale(mc: Minecraft) =
        minOf(mc.displayWidth / 1280f, mc.displayHeight / 800f).coerceIn(0.5f, 1.65f)
            .coerceAtMost(mc.displayWidth / (WIDTH + MARGIN * 2f))
            .coerceAtMost(mc.displayHeight / 395f)

    private fun layout(mc: Minecraft, count: Int): Layout {
        val rowCount = count.coerceIn(1, MAX_VISIBLE_ROWS)
        clampScroll(count)
        return Layout(scale(mc), mc.displayWidth.toFloat(), rowCount)
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
        val layout = layout(mc, entries.size)
        val look = if (DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).neteaseUsesAppleLayout) apple else classic
        val mouseX = if (interactive) Mouse.getX() / layout.scale else -1f
        val mouseY = if (interactive) (mc.displayHeight - Mouse.getY() - 1) / layout.scale else -1f
        val dragged = if (interactive) gesture.move(mouseX, mouseY) else null
        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameTime = now
        if (dragged != null && mouseX >= layout.rows.left - 12f && mouseX < layout.rows.right + 12f) {
            val edge = 17f
            val top = ((layout.rows.top + edge - mouseY) / edge).coerceIn(0f, 1f)
            val bottom = ((mouseY - layout.rows.bottom + edge) / edge).coerceIn(0f, 1f)
            scrollRows += (bottom - top) * dt * 7f
            clampScroll(entries.size)
        }
        val hover = hit(layout, entries, mouseX, mouseY).takeIf { dragged == null }
        val ctx = canvas ?: DangoClient.gfx.newCanvas().also { canvas = it }
        ctx.begin(false, mouseX.toInt(), mouseY.toInt())
        ctx.pushScale(layout.scale, layout.scale, 1f)
        text.clear()
        try {
            ctx.drawShape(layout.panel, look.panel)
            header(ctx, layout, look, mc.session.username)
            nowPlaying(ctx, layout, PlaybackView.snapshot())
            queue(ctx, layout, look, entries, hover, dragged, mouseX, mouseY)
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
            text.flush(layout.scale)
        } finally {
            ctx.popTransform()
            ctx.end()
        }
    }

    private fun header(ctx: GuiCanvas, layout: Layout, look: Look, player: String) {
        val left = layout.rows.x
        val y = layout.panel.y + 21f
        PlayerIcons.draw(ctx, noteList, left + 8f, y - 0.5f, 16f, look.accent)
        text.text(ctx, I18n.text("Song requests"), left + 24f, y - 6f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val name = text.trim(player, 132f, 12f)
        val nameX = layout.rows.right - text.width(name, 12f)
        val color = when (BilibiliRequests.state) {
            BilibiliState.CONNECTED -> READY
            BilibiliState.CONNECTING, BilibiliState.RECONNECTING -> WAITING
            BilibiliState.FAILED -> FAILED
            BilibiliState.DISCONNECTED -> DIM_TEXT
        }
        dot(ctx, nameX - 9f, y, color)
        text.text(ctx, name, nameX, y - 5f, 12f, Theme.TEXT_PRIMARY)
    }

    private fun nowPlaying(ctx: GuiCanvas, layout: Layout, playback: PlaybackView.Snapshot) {
        val left = layout.rows.x
        val p = LayoutBounds(left, layout.panel.y + 60f, layout.rows.width, 65f)
        text.text(ctx, I18n.text("Current track"), left + 2f, p.y - 19f, 11f, DIM_TEXT)
        ctx.drawShape(p, capsule)
        val art = LayoutBounds(p.x + 8f, p.y + 14.5f, 36f, 36f)
        drawCover(ctx, art, playback.track)
        val x = art.right + 10f
        val right = p.right - 11f
        val track = playback.track
        if (track == null) {
            text.text(ctx, I18n.text("Nothing playing"), x, p.y + 27f, 12f, DIM_TEXT)
            return
        }
        text.text(ctx, text.trim(track.name, right - x, 14f, Theme.FONT_SEMIBOLD),
            x, p.y + 8f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text.text(ctx, text.trim(track.artists.joinToString(" / "), right - x, 11f),
            x, p.y + 25f, 11f, DIM_TEXT)
        val duration = playback.durationMs.coerceAtLeast(0L)
        val position = playback.positionMs.coerceAtLeast(0L)
        val stamp = "${fmtTime(position)} / ${if (duration > 0L) fmtTime(duration) else "–:––"}"
        val stampX = right - text.width(stamp, 10f)
        val bar = LayoutBounds(x, p.bottom - 16f, stampX - x - 7f, 2f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1f))
        val progress = if (duration > 0L) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        if (progress > 0f) {
            val width = bar.width * progress
            ctx.drawShape(bar.copy(width = width), ShapeStyle(Theme.TEXT_PRIMARY, minOf(width / 2f, 1f)))
        }
        text.text(ctx, stamp, stampX, p.bottom - 21f, 10f, DIM_TEXT)
    }

    private fun queue(
        ctx: GuiCanvas, layout: Layout, look: Look, entries: List<RequestEntry>,
        hover: RequestQueueGesture.Hit?, dragged: Long?, mouseX: Float, mouseY: Float,
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
            text.text(ctx, I18n.text("No song requests"), rows.x + 1f, rows.y + 14f, 12f, DIM_TEXT)
            return
        }
        val first = scrollRows.toInt()
        val last = minOf(entries.lastIndex, first + MAX_VISIBLE_ROWS)
        ctx.pushClip(rows)
        text.clip = rows
        for (index in first..last) {
            val entry = entries[index]
            val row = layout.row(index, scrollRows)
            if (row.y >= rows.bottom) break
            val pickedUp = entry.id == dragged
            if (pickedUp) ctx.pushOpacity(0.48f)
            if (entry.id == selected || entry.id == hover?.id) ctx.drawShape(row, look.selection)
            if (look === classic && index < entries.lastIndex) {
                ctx.drawShape(LayoutBounds(row.x, row.bottom - 0.75f, row.width, 0.75f), ShapeStyle(Theme.HAIRLINE))
            }
            queueRow(ctx, layout, row, entry, hover?.target.takeIf { hover?.id == entry.id })
            if (pickedUp) ctx.popOpacity()
        }
        if (dragged != null && entries.any { it.id == dragged } && rows.containsPoint(mouseX, mouseY)) {
            val slot = layout.dropIndex(mouseY, scrollRows).coerceIn(0, entries.size)
            val y = layout.row(slot, scrollRows).y.coerceIn(rows.top + 1f, rows.bottom - 1f)
            ctx.drawShape(LayoutBounds(rows.x, y - 1f, rows.width, 2f), ShapeStyle(look.accent, 1f))
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
            textX, row.y + 7f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        // Reserve part of the line for the requester even when the artist credit is long.
        val requester = text.trim(I18n.format("Requested by %s", entry.user), available * 0.64f, 11f)
        val artist = entry.track.artists.joinToString(" / ")
        val details = if (artist.isEmpty()) requester else {
            val separator = " · "
            val artistWidth = available - text.width("$separator$requester", 11f)
            text.trim(artist, artistWidth, 11f).takeIf { it.isNotEmpty() }?.let { "$it$separator$requester" } ?: requester
        }
        text.text(ctx, details, textX, row.y + 23f, 11f, DIM_TEXT)
        when (entry.cache) {
            RequestCacheState.READY -> dot(ctx, statusX, centerY, READY)
            RequestCacheState.LOADING -> PlayerIcons.loadingRing(ctx, statusX, centerY, null, 14f)
            RequestCacheState.FAILED -> dot(ctx, statusX, centerY, FAILED)
        }
        val remove = layout.remove(row)
        val removeColor = if (hover == RequestQueueGesture.Target.REMOVE) FAILED else Theme.TEXT_PRIMARY
        PlayerIcons.draw(ctx, trash, remove.x + remove.width / 2f, centerY, 17f, removeColor)
    }

    private fun notice(ctx: GuiCanvas, layout: Layout, look: Look, notice: TimedNotice, now: Long) {
        val opacity = notice.opacity(now)
        if (opacity <= 0f) return
        val bounds = LayoutBounds(layout.panel.x, layout.panel.bottom + 6f, WIDTH, 26f)
        ctx.pushOpacity(opacity)
        ctx.drawShape(bounds, look.panel.copy(radius = 8f))
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

    private fun hit(layout: Layout, entries: List<RequestEntry>, x: Float, y: Float): RequestQueueGesture.Hit? {
        if (!layout.rows.containsPoint(x, y)) return null
        val index = layout.rowIndex(y, scrollRows)
        val entry = entries.getOrNull(index) ?: return null
        val row = layout.row(index, scrollRows)
        val target = when {
            layout.cover(row).containsPoint(x, y) -> RequestQueueGesture.Target.PLAY
            layout.remove(row).containsPoint(x, y) -> RequestQueueGesture.Target.REMOVE
            else -> RequestQueueGesture.Target.SELECT
        }
        return RequestQueueGesture.Hit(entry.id, target)
    }

    private fun LayoutBounds.containsPoint(x: Float, y: Float) =
        x >= left && x < right && y >= top && y < bottom

    fun press(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val entries = SongRequests.snapshot().entries
        val layout = layout(mc, entries.size)
        val x = pixelX / layout.scale
        val y = pixelY / layout.scale
        gesture.down(hit(layout, entries, x, y), x, y)
    }

    fun movePointer(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val scale = scale(mc)
        gesture.move(pixelX / scale, pixelY / scale)
    }

    fun release(mc: Minecraft, pixelX: Int, pixelY: Int) {
        val entries = SongRequests.snapshot().entries
        val layout = layout(mc, entries.size)
        val x = pixelX / layout.scale
        val y = pixelY / layout.scale
        val dragged = gesture.move(x, y)
        val drop = if (dragged != null && entries.size > 1 && layout.rows.containsPoint(x, y)) {
            val slot = layout.dropIndex(y, scrollRows).coerceIn(0, entries.size)
            val before = entries.getOrNull(slot)?.id
            RequestQueueGesture.Drop(if (before == dragged) entries.getOrNull(slot + 1)?.id else before)
        } else null
        when (val action = gesture.up(hit(layout, entries, x, y), x, y, drop)) {
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
        val count = SongRequests.snapshot().entries.size
        val layout = layout(mc, count)
        if (!layout.rows.containsPoint(pixelX / layout.scale, pixelY / layout.scale)) return
        scrollRows -= wheel.coerceIn(-120, 120) / 120f
        clampScroll(count)
    }

    fun cancelGesture() = gesture.cancel()
}
