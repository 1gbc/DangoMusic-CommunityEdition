package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
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
    ) {
        val count = rows?.size ?: queue.size - first
        fun slot(row: Int) = rows?.get(row)?.first ?: first + row
        fun row(y: Float) = floor((y - top) / rowHeight).toInt()
    }

    private class Press(val view: View, val row: Int, val x: Int, val y: Int, val grab: Float, val click: () -> Unit) {
        var active = false
        var target = row
        var shifts = FloatArray(0)
    }

    private var view: View? = null
    private var press: Press? = null
    val pressed get() = press != null
    val liftedRow get() = press?.takeIf { it.active && view != null }?.row ?: -1

    fun beginFrame() { view = null }

    fun cancel() { press = null }

    fun offer(
        queue: List<NeteaseTrack>,
        bounds: LayoutBounds,
        top: Float,
        rowHeight: Float,
        ui: Ui,
        rows: List<Pair<Int, NeteaseTrack>>? = null,
        first: Int = 0,
        upcoming: Boolean = false,
    ) {
        val shown = View(queue, rows, first, bounds, top, rowHeight, upcoming)
        view = shown
        val drag = press ?: return
        if (!matches(drag, shown)) {
            cancel()
            return
        }
        move(ui.mx, ui.my)
        if (!drag.active) return
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

    fun down(x: Int, y: Int, click: () -> Unit): Boolean {
        val shown = view ?: return false
        if (shown.count < 2 || !shown.bounds.contains(x, y)) return false
        val row = shown.row(y.toFloat())
        if (row !in 0 until shown.count) return false
        press = Press(shown, row, x, y, y - shown.top - row * shown.rowHeight, click)
        return true
    }

    private fun matches(drag: Press, shown: View) =
        drag.view.queue === shown.queue && drag.view.rows === shown.rows && drag.view.first == shown.first &&
            MusicPlayer.queue === shown.queue

    private fun move(x: Int, y: Int) {
        val drag = press ?: return
        if (!drag.active && (abs(x - drag.x) > 5 || abs(y - drag.y) > 5)) {
            drag.active = true
            drag.shifts = FloatArray(drag.view.count)
        }
        val shown = view ?: return
        if (drag.active) {
            drag.target = shown.row(y - drag.grab + shown.rowHeight / 2f).coerceIn(0, shown.count - 1)
        }
    }

    fun up(x: Int, y: Int): Boolean {
        val drag = press ?: return false
        move(x, y)
        cancel()
        val shown = view ?: return true
        if (!matches(drag, shown) || !shown.bounds.contains(x, y)) return true
        if (!drag.active) drag.click()
        else if (drag.row != drag.target) {
            val from = shown.slot(drag.row)
            val to = shown.slot(drag.target)
            ClientMusic.control { MusicPlayer.moveQueueTrack(shown.queue, from, to, shown.upcoming) }
        }
        return true
    }

    fun shift(row: Int): Float = if (view != null) press?.shifts?.getOrNull(row) ?: 0f else 0f

    fun liftedY(y: Int): Float = y - (press?.grab ?: 0f)

    fun markerY(): Float? {
        val shown = view ?: return null
        val drag = press?.takeIf { it.active } ?: return null
        return shown.top + drag.target * shown.rowHeight
    }

    /** Measured in rows per frame so the rate is independent of UI and row size. */
    fun edgeScroll(dt: Float, x: Int, y: Int): Float {
        val drag = press?.takeIf { it.active } ?: return 0f
        val bounds = drag.view.bounds
        if (x < bounds.left - 12f || x >= bounds.right + 12f) return 0f
        val edge = minOf(30f, bounds.height / 3f).coerceAtLeast(1f)
        val up = ((bounds.top + edge - y) / edge).coerceIn(0f, 1f)
        val down = ((y - bounds.bottom + edge) / edge).coerceIn(0f, 1f)
        return (down - up) * dt * 0.012f
    }
}
