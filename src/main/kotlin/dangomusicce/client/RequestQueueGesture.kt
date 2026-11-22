package dangomusicce.client

import kotlin.math.abs

/** Clicks match the entry ID and control pressed, even if the queue changes before release. */
internal class RequestQueueGesture {
    enum class Target { SELECT, PLAY, REMOVE }

    data class Hit(val id: Long, val target: Target)
    data class Drag(val id: Long, val left: Float, val top: Float)
    data class Drop(val beforeId: Long?)

    sealed interface Command {
        data class Click(val hit: Hit) : Command
        data class Move(val id: Long, val beforeId: Long?) : Command
    }

    private class Press(val hit: Hit, val x: Float, val y: Float, val rowLeft: Float, val rowTop: Float) {
        var moved = false
    }

    private var press: Press? = null

    fun down(hit: Hit, x: Float, y: Float, rowLeft: Float, rowTop: Float) {
        press = Press(hit, x, y, rowLeft, rowTop)
    }

    fun cancel() {
        press = null
    }

    fun move(x: Float, y: Float): Drag? {
        val start = press ?: return null
        val dx = x - start.x
        val dy = y - start.y
        if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) start.moved = true
        if (!start.moved || start.hit.target != Target.SELECT) return null
        return Drag(start.hit.id, start.rowLeft + dx, start.rowTop + dy)
    }

    fun up(hit: Hit?, x: Float, y: Float, drop: Drop?): Command? {
        val start = press ?: return null
        val dragged = move(x, y)
        cancel()
        return when {
            dragged != null && drop != null -> Command.Move(dragged.id, drop.beforeId)
            !start.moved && start.hit == hit -> Command.Click(start.hit)
            else -> null
        }
    }

    companion object {
        const val DRAG_THRESHOLD = 5f
    }
}
