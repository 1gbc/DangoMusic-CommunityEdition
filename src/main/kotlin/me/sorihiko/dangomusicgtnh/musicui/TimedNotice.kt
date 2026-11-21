package me.sorihiko.dangomusicgtnh.musicui

/** Shared lifetime for transient player feedback. The clock is monotonic. */
internal class TimedNotice(
    val text: String,
    private val startedAt: Long = System.nanoTime(),
    private val holdNanos: Long = 5_000_000_000L,
) {
    fun opacity(now: Long = System.nanoTime()): Float {
        if (text.isEmpty()) return 0f
        val elapsed = now - startedAt
        return (1f - (elapsed - holdNanos).toFloat() / FADE_NANOS).coerceIn(0f, 1f)
    }

    private companion object {
        const val FADE_NANOS = 350_000_000L
    }
}
