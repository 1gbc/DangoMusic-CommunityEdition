package me.sorihiko.dangomusicgtnh.musicui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Positions and velocities are measured in covers and covers per second. */
internal class CoverflowMotion {
    var position = 0f
        private set
    var target = 0
        private set
    var pressed = false
        private set
    var dragging = false
        private set
    private var velocity = 0f
    private var startX = 0f
    private var startPosition = 0f
    private var spacing = 1f
    private var playWhenSettled = false
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun reset(index: Int = 0) {
        position = index.toFloat()
        moveTo(index)
    }

    fun moveTo(index: Int) {
        target = index
        velocity = 0f
        pressed = false
        dragging = false
        playWhenSettled = false
        samples.clear()
    }

    fun constrain(count: Int) {
        val last = (count - 1).coerceAtLeast(0)
        target = target.coerceIn(0, last)
        position = position.coerceIn(0f, last.toFloat())
    }

    fun down(x: Float, coverSpacing: Float, now: Long) {
        moveTo(position.roundToInt())
        pressed = true
        startX = x
        startPosition = position
        spacing = coverSpacing.coerceAtLeast(1f)
        samples.addLast(now to position)
    }

    fun drag(x: Float, count: Int, now: Long) {
        if (!pressed || count == 0) return
        if (abs(x - startX) > 5f) dragging = true
        if (!dragging) return
        position = (startPosition - (x - startX) / spacing).coerceIn(0f, (count - 1).toFloat())
        target = position.roundToInt()
        samples.addLast(now to position)
        while (samples.size > 1 && now - samples.first().first > 100_000_000L) samples.removeFirst()
    }

    /** Returns true for a click. A drag starts one deferred playback after settling. */
    fun up(x: Float, count: Int, now: Long): Boolean {
        if (!pressed) return false
        drag(x, count, now)
        pressed = false
        if (!dragging) return true
        dragging = false
        val first = samples.firstOrNull()
        val seconds = if (first == null) 0f else (now - first.first) / 1_000_000_000f
        velocity = if (seconds > 0.004f) ((position - first!!.second) / seconds).coerceIn(-24f, 24f) else 0f
        playWhenSettled = true
        return false
    }

    /** True exactly once after a released drag has stopped and reached an integer cover. */
    fun step(seconds: Float, count: Int): Boolean {
        if (count <= 0) {
            reset()
            return false
        }
        val last = (count - 1).toFloat()
        constrain(count)
        if (pressed) return false
        val dt = seconds.coerceIn(0f, 0.05f)
        if (abs(velocity) > 0.15f) {
            val decay = exp(-4.8f * dt)
            position = (position + velocity * (1f - decay) / 4.8f).coerceIn(0f, last)
            velocity *= decay
            if (position <= 0f || position >= last) velocity = 0f
            target = position.roundToInt()
            return false
        }
        velocity = 0f
        position += (target - position) * (1f - exp(-14f * dt))
        if (abs(target - position) >= 0.002f) return false
        position = target.toFloat()
        val completed = playWhenSettled
        playWhenSettled = false
        return completed
    }
}
