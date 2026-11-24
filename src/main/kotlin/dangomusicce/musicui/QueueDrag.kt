package dangomusicce.musicui

import dangomusicce.client.ClientMusic
import dangomusicce.music.MusicPlayer
import dangomusicce.music.NeteaseTrack
import dangomusicce.ui.LayoutBounds
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

/** The queue remains untouched until release. The painted gap uses display rows,
 * while the commit uses the corresponding slots in that exact queue snapshot. */
internal class QueueDrag {
    private class View(
        val queue: List<NeteaseTrack>,
        val rows: List<Pair<Int, NeteaseTrack>>?,
        val first: Int,
        val bounds: LayoutBounds,
        val top: Float,
        val rowHeight: Float,
        val upcoming: Boolean,
        val canReorder: Boolean,
        val queueBacked: Boolean,
        val play: (Int) -> Unit,
    ) {
        val count = rows?.size ?: queue.size - first
        fun slot(row: Int) = rows?.get(row)?.first ?: first + row
        fun row(y: Float) = floor((y - top) / rowHeight).toInt()
    }

    private class Press(val view: View, val row: Int, val x: Int, val y: Int, val grab: Float,
                        val hit: Hit, val rowClick: Boolean) {
        var active = false
        var target = row
        var shifts = FloatArray(0)
    }

    private class Click(val queue: List<NeteaseTrack>, val slot: Int, val x: Int, val y: Int, val at: Long)

    private val rowHits = IdentityHashMap<Hit, Unit>()
    private var previousClick: Click? = null
    private var view: View? = null
    private var press: Press? = null
    val pressed get() = press != null
    val liftedRow get() = press?.takeIf {
        it.active && it.view.canReorder && it.view.count > 1 && view != null
    }?.row ?: -1

    fun beginFrame() {
        view = null
        rowHits.clear()
    }

    fun rowHit(bounds: LayoutBounds, select: () -> Unit): Hit =
        (bounds to select).also { rowHits[it] = Unit }

    fun resetClick() { previousClick = null }

    fun cancel() {
        press = null
        resetClick()
    }

    fun offer(
        queue: List<NeteaseTrack>,
        bounds: LayoutBounds,
        top: Float,
        rowHeight: Float,
        ui: Ui,
        rows: List<Pair<Int, NeteaseTrack>>? = null,
        first: Int = 0,
        upcoming: Boolean = false,
        canReorder: Boolean = true,
        queueBacked: Boolean = true,
        play: (Int) -> Unit,
    ) {
        val shown = View(queue, rows, first, bounds, top, rowHeight, upcoming, canReorder, queueBacked, play)
        view = shown
        val drag = press ?: return
        if (!matches(drag, shown)) {
            cancel()
            return
        }
        move(ui.mx, ui.my)
        if (!drag.active || !shown.canReorder) return
        for (row in drag.shifts.indices) {
            val offset = when {
                row > drag.row && row <= drag.target -> -rowHeight
                row < drag.row && row >= drag.target -> rowHeight
                else -> 0f
            }
            if (offset != 0f || drag.shifts[row] != 0f) {
                val shifted = ui.approach(drag.shifts[row], offset, 45f)
                drag.shifts[row] = if (abs(shifted - offset) < 0.1f) offset else shifted
            }
        }
    }

    fun down(x: Int, y: Int, hit: Hit): Boolean {
        val shown = view ?: return false
        if (!shown.bounds.contains(x, y)) return false
        val row = shown.row(y.toFloat())
        if (row !in 0 until shown.count) return false
        val rowClick = rowHits.containsKey(hit)
        if (!rowClick) {
            resetClick()
            if (!shown.queueBacked) return false // Catalog artwork and artist links keep their own actions.
        }
        press = Press(shown, row, x, y, y - shown.top - row * shown.rowHeight, hit, rowClick)
        return true
    }

    private fun matches(drag: Press, shown: View) =
        drag.view.queue === shown.queue && drag.view.rows === shown.rows && drag.view.first == shown.first &&
            drag.view.upcoming == shown.upcoming && drag.view.canReorder == shown.canReorder &&
            drag.view.queueBacked == shown.queueBacked && (!shown.queueBacked || MusicPlayer.queue === shown.queue)

    private fun move(x: Int, y: Int) {
        val drag = press ?: return
        if (!drag.active && (abs(x - drag.x) > 5 || abs(y - drag.y) > 5)) {
            drag.active = true
            resetClick()
            if (drag.view.canReorder) drag.shifts = FloatArray(drag.view.count)
        }
        val shown = view ?: return
        if (drag.active && shown.canReorder) {
            drag.target = shown.row(y - drag.grab + shown.rowHeight / 2f).coerceIn(0, shown.count - 1)
        }
    }

    fun up(x: Int, y: Int): Boolean {
        val drag = press ?: return false
        move(x, y)
        press = null
        val shown = view
        if (shown == null || !matches(drag, shown) || !shown.bounds.contains(x, y)) {
            resetClick()
            return true
        }
        if (!drag.active) {
            if (!drag.hit.first.contains(x, y) || shown.row(y.toFloat()) != drag.row) {
                resetClick()
            } else {
                drag.hit.second()
                if (drag.rowClick) clickRow(shown, drag.row, x, y)
            }
        } else if (shown.canReorder && drag.row != drag.target) {
            val from = shown.slot(drag.row)
            val to = shown.slot(drag.target)
            ClientMusic.control { MusicPlayer.moveQueueTrack(shown.queue, from, to, shown.upcoming) }
        }
        return true
    }

    private fun clickRow(view: View, row: Int, x: Int, y: Int) {
        val slot = view.slot(row)
        val now = System.nanoTime()
        val previous = previousClick
        if (previous != null && previous.queue === view.queue && previous.slot == slot &&
            now - previous.at <= TimeUnit.MILLISECONDS.toNanos(500) &&
            abs(previous.x - x) <= 5 && abs(previous.y - y) <= 5) {
            resetClick()
            view.play(slot)
        } else {
            previousClick = Click(view.queue, slot, x, y, now)
        }
    }

    fun shift(row: Int): Float = if (view != null) press?.shifts?.getOrNull(row) ?: 0f else 0f

    fun liftedY(y: Int): Float = y - (press?.grab ?: 0f)

    fun markerY(): Float? {
        val shown = view ?: return null
        val drag = press?.takeIf { it.active && it.view.canReorder && it.view.count > 1 } ?: return null
        return shown.top + drag.target * shown.rowHeight
    }

    /** Measured in rows per frame so the rate is independent of UI and row size. */
    fun edgeScroll(dt: Float, x: Int, y: Int): Float {
        val drag = press?.takeIf { it.active && it.view.canReorder && it.view.count > 1 } ?: return 0f
        val bounds = drag.view.bounds
        if (x < bounds.left - 12f || x >= bounds.right + 12f) return 0f
        val edge = minOf(30f, bounds.height / 3f).coerceAtLeast(1f)
        val up = ((bounds.top + edge - y) / edge).coerceIn(0f, 1f)
        val down = ((y - bounds.bottom + edge) / edge).coerceIn(0f, 1f)
        return (down - up) * dt * 0.012f
    }
}
