package me.sorihiko.dangomusicgtnh.client

import kotlin.math.abs

/** Clicks match the entry ID and control pressed, even if the queue changes before release. */
internal class RequestQueueGesture {
    enum class Target { SELECT, PLAY, REMOVE }

    data class Hit(val id: Long, val target: Target)
    data class Drop(val beforeId: Long?)

    sealed interface Command {
        data class Click(val hit: Hit) : Command
        data class Move(val id: Long, val beforeId: Long?) : Command
    }

    private class Press(val hit: Hit, val x: Float, val y: Float) {
        var moved = false
    }

    private var press: Press? = null

    fun down(hit: Hit?, x: Float, y: Float) {
        press = hit?.let { Press(it, x, y) }
    }

    fun cancel() {
        press = null
    }

    fun move(x: Float, y: Float): Long? {
        val start = press ?: return null
        if (abs(x - start.x) > 5f || abs(y - start.y) > 5f) start.moved = true
        return start.hit.id.takeIf { start.moved && start.hit.target == Target.SELECT }
    }

    fun up(hit: Hit?, x: Float, y: Float, drop: Drop?): Command? {
        val start = press ?: return null
        val dragged = move(x, y)
        cancel()
        return when {
            dragged != null && drop != null -> Command.Move(dragged, drop.beforeId)
            !start.moved && start.hit == hit -> Command.Click(start.hit)
            else -> null
        }
    }
}
