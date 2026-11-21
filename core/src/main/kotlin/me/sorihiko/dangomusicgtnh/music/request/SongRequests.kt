package me.sorihiko.dangomusicgtnh.music.request

import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseApi
import me.sorihiko.dangomusicgtnh.music.NeteaseDiskCache
import me.sorihiko.dangomusicgtnh.music.NeteaseQuality
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class RequestCacheState { LOADING, READY, FAILED }
enum class RequestNoticeKind { SKIPPED, NOT_FOUND, AMBIGUOUS, QUEUE_FULL, SEARCH_ERROR }
data class RequestEntry(val id: Long, val track: NeteaseTrack, val user: String, val cache: RequestCacheState)
data class RequestNotice(val title: String, val createdAtNanos: Long, val kind: RequestNoticeKind)
data class RequestSnapshot(val entries: List<RequestEntry>, val notice: RequestNotice?)

/** Listener searches and audio downloads are kept off both the game and decoder threads. */
object SongRequests {
    private const val QUEUE_LIMIT = 24
    private const val SEEN_LIMIT = 512
    private val lock = Any()
    private val entries = ArrayList<RequestEntry>()
    private val seen = LinkedHashSet<String>()
    private val searches = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_LIMIT),
        { task -> Thread(task, "Dango-RequestSearch").apply { isDaemon = true } },
    )
    private val downloads = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Dango-RequestCache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private var workerActive = false
    private var generation = 0L
    private var nextId = 0L
    private var priority: Long? = null
    private var cacheQuality: NeteaseQuality? = null
    private var diskGeneration = -1L
    private var notice: RequestNotice? = null
    @Volatile var enabled = false
        private set

    fun enable(active: Boolean) = synchronized(lock) {
        if (enabled == active) return@synchronized
        enabled = active
        generation++
        if (!active) searches.queue.clear()
        if (active) wakeCache()
    }

    fun receive(messageId: String, user: String, message: String) {
        val query = SongRequestQuery.parse(message) ?: return
        synchronized(lock) {
            if (!enabled || messageId.isBlank() || messageId.length > 200 || !seen.add(messageId)) return
            if (seen.size > SEEN_LIMIT) seen.remove(seen.first())
            if (entries.size >= QUEUE_LIMIT) {
                show(query.searchText, RequestNoticeKind.QUEUE_FULL)
                return
            }
            val acceptedGeneration = generation
            val displayName = user.filterNot(Char::isISOControl).trim().take(36)
            try {
                searches.execute { resolve(acceptedGeneration, displayName, query) }
            } catch (_: RejectedExecutionException) {
                show(query.searchText, RequestNoticeKind.QUEUE_FULL)
            }
        }
    }

    private fun resolve(acceptedGeneration: Long, user: String, query: SongRequestQuery) {
        val matches = runCatching {
            val candidates = query.trackId?.let { listOfNotNull(NeteaseApi.trackById(it)) }
                ?: NeteaseApi.search(query.searchText, 25)
            candidates
                .filter { it.source == MusicSource.NETEASE && it.id > 0L && !it.cloud && query.matches(it) }
                .distinctBy { it.id }
        }
        synchronized(lock) {
            if (!enabled || generation != acceptedGeneration) return
            val result = matches.getOrNull()
            when {
                result == null -> show(query.searchText, RequestNoticeKind.SEARCH_ERROR)
                result.isEmpty() -> show(query.searchText, RequestNoticeKind.NOT_FOUND)
                result.size > 1 -> show(query.searchText, RequestNoticeKind.AMBIGUOUS)
                entries.size >= QUEUE_LIMIT -> show(query.searchText, RequestNoticeKind.QUEUE_FULL)
                else -> {
                    entries.add(RequestEntry(++nextId, result.single(), user, RequestCacheState.LOADING))
                    wakeCache()
                }
            }
        }
    }

    fun snapshot(): RequestSnapshot = synchronized(lock) {
        wakeCache()
        RequestSnapshot(entries.toList(), notice)
    }

    fun move(id: Long, beforeId: Long?) = synchronized(lock) {
        if (id == beforeId) return@synchronized
        val current = entries.firstOrNull { it.id == id } ?: return@synchronized
        val before = beforeId?.let { target -> entries.firstOrNull { it.id == target } ?: return@synchronized }
        entries.remove(current)
        entries.add(before?.let(entries::indexOf) ?: entries.size, current)
        wakeCache()
    }

    fun remove(id: Long): Boolean =
        synchronized(lock) {
            val removed = entries.removeAll { it.id == id }
            if (priority == id) priority = null
            wakeCache()
            removed
        }

    fun play(id: Long) = MusicPlayer.playRequestedSong(id)

    internal fun hasRequests(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    internal fun first(preferred: Long?): RequestEntry? = synchronized(lock) {
        wakeCache()
        entries.firstOrNull { it.id == preferred } ?: entries.firstOrNull()
    }

    internal fun get(id: Long): RequestEntry? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    internal fun following(id: Long): Long? = synchronized(lock) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) null else entries.getOrNull(index + 1)?.id
    }

    internal fun prioritize(id: Long?) = synchronized(lock) {
        priority = id
        wakeCache()
    }

    internal fun skipped(title: String) = synchronized(lock) { show(title, RequestNoticeKind.SKIPPED) }

    private fun show(title: String, kind: RequestNoticeKind) {
        notice = RequestNotice(title.take(100), System.nanoTime(), kind)
    }

    private fun head(): RequestEntry? =
        entries.firstOrNull { it.id == priority && it.cache == RequestCacheState.LOADING }
            ?: entries.firstOrNull { it.cache == RequestCacheState.LOADING }

    private fun wakeCache() {
        if (!enabled || entries.isEmpty()) return
        val currentQuality = MusicPlayer.neteaseQuality
        val currentGeneration = NeteaseDiskCache.generation
        if (cacheQuality != currentQuality || diskGeneration != currentGeneration) {
            cacheQuality = currentQuality
            diskGeneration = currentGeneration
            entries.replaceAll { it.copy(cache = RequestCacheState.LOADING) }
        }
        if (workerActive || head() == null) return
        workerActive = true
        downloads.execute(::cacheQueue)
    }

    private fun cacheQueue() {
        while (true) {
            val work = synchronized(lock) {
                val item = head()
                val quality = cacheQuality
                if (item == null || quality == null || !enabled) {
                    workerActive = false
                    return
                }
                Triple(item, quality, diskGeneration)
            }
            val (item, quality, stamp) = work
            val active = {
                synchronized(lock) {
                    enabled && cacheQuality == quality && diskGeneration == stamp && head()?.id == item.id
                }
            }
            val ready = runCatching {
                NeteaseApi.cacheRequestedAudio(item.track, quality, active)
            }.getOrDefault(false)
            synchronized(lock) {
                if (active()) {
                    val index = entries.indexOfFirst { it.id == item.id }
                    entries[index] = item.copy(cache = if (ready) RequestCacheState.READY else RequestCacheState.FAILED)
                }
                wakeCache()
            }
        }
    }
}
