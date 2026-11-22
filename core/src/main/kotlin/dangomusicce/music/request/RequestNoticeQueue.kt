package dangomusicce.music.request

import dangomusicce.music.NeteaseTrack
import kotlin.math.min

data class RequestNotice(
    val title: String,
    val kind: RequestNoticeKind,
    val candidates: List<RequestCandidate>,
    val matchCount: Int,
    val opensAt: Long,
    val closesAt: Long,
    val user: String,
) {
    val endsAt get() = closesAt + TRANSITION_NANOS

    fun reveal(now: Long): Float {
        val t = (min(now - opensAt, endsAt - now).toDouble() / TRANSITION_NANOS).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val MAX_VISIBLE = 3
    }
}

private const val TRANSITION_NANOS = 250_000_000L

/** Called under SongRequests' lock. Stagger a burst and leave room for the card's contents. */
internal class RequestNoticeQueue {
    private val pending = ArrayDeque<RequestNotice>()

    @JvmOverloads
    fun add(now: Long, holdSeconds: Int, title: String, kind: RequestNoticeKind, matches: List<RequestCandidate>, user: String = "") {
        expire(now)
        if (pending.size >= 48) return
        val holdNanos = holdSeconds.coerceIn(3, 60) * 1_000_000_000L
        val previous = pending.lastOrNull()
        val slot = pending.getOrNull(pending.size - RequestNotice.MAX_VISIBLE)
        var start = if (slot == null) now else maxOf(now, slot.endsAt)
        if (previous != null) {
            val orderedClose = previous.closesAt + STAGGER_NANOS - TRANSITION_NANOS - holdNanos
            start = maxOf(start, previous.opensAt + STAGGER_NANOS, orderedClose)
        }
        pending.addLast(RequestNotice(title, kind, matches.take(3), matches.size, start, start + TRANSITION_NANOS + holdNanos, user))
    }

    fun visible(now: Long): List<RequestNotice> {
        expire(now)
        return pending.takeWhile { it.opensAt <= now }
    }

    fun markQueued(notice: RequestNotice, trackId: Long): NeteaseTrack? {
        val index = pending.indexOf(notice)
        if (index < 0) return null
        val candidate = notice.candidates.firstOrNull { it.id == trackId && !it.queued } ?: return null
        pending[index] = notice.copy(candidates = notice.candidates.map {
            if (it === candidate) it.copy(queued = true) else it
        })
        return candidate.track
    }

    fun clear() = pending.clear()

    private fun expire(now: Long) {
        while (pending.firstOrNull()?.let { it.endsAt <= now } == true) pending.removeFirst()
    }

    private companion object {
        const val STAGGER_NANOS = 500_000_000L
    }
}
