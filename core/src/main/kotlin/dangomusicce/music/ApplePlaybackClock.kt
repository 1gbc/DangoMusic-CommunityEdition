package dangomusicce.music

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Projects the last confirmed Apple Music position between bridge reports. */
internal class ApplePlaybackClock(
    private val reportedMs: Long = 0,
    private val receivedAtNanos: Long = 0,
    private val adjustmentMs: Long = 0,
    private val seekSequence: Long = 0,
) {
    fun positionAt(nowNanos: Long): Long {
        val elapsed = elapsedMs(nowNanos).coerceIn(0L, MAX_REPORT_GAP_MS)
        val correction = adjustmentMs * (CORRECTION_MS - elapsed).coerceAtLeast(0L) / CORRECTION_MS
        return (reportedMs + elapsed + correction).coerceAtLeast(0L)
    }

    fun report(positionMs: Long, nowNanos: Long, sequence: Long, continuing: Boolean): ApplePlaybackClock {
        if (!continuing || sequence != seekSequence) return ApplePlaybackClock(positionMs, nowNanos, seekSequence = sequence)
        if (positionMs == reportedMs) return this // Re-reading a snapshot or a stalled SDK clock.
        val elapsed = elapsedMs(nowNanos)
        val drift = positionAt(nowNanos) - positionMs
        val adjustment = if (elapsed in 0..MAX_REPORT_GAP_MS && abs(drift) <= MAX_JITTER_MS) drift else 0L
        return ApplePlaybackClock(positionMs, nowNanos, adjustment, sequence)
    }

    private fun elapsedMs(nowNanos: Long) = TimeUnit.NANOSECONDS.toMillis(nowNanos - receivedAtNanos)

    private companion object {
        // The browser extension polls every 700 ms. Bound extrapolation if reports stop.
        const val MAX_REPORT_GAP_MS = 1_000L
        const val MAX_JITTER_MS = 120L
        const val CORRECTION_MS = 250L
    }
}
