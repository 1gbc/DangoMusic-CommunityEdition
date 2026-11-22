package dangomusicce.music

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** Persistent copies of complete NetEase audio, isolated by login session. */
object NeteaseDiskCache {
    private const val MAGIC = 0x444D4301
    private const val LIMIT = 5L * 1024 * 1024 * 1024
    private val cacheName = Regex("[0-9a-f]{64}[.]audio")
    private val usageWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Dango-CacheUsage").apply { isDaemon = true }
    }
    private val usagePending = AtomicBoolean()
    @Volatile private var usageScanAt = System.nanoTime()
    @Volatile private var lastUsage: Usage? = null

    data class Usage(val bytes: Long, val fileCount: Int, val limit: Long = LIMIT)
    private data class CacheFile(val path: Path, val bytes: Long, val modified: FileTime)

    /** The settings screen can call this each frame. Directory I/O runs on the worker. */
    fun usage(): Usage? {
        val now = System.nanoTime()
        if (now - usageScanAt >= 0 && usagePending.compareAndSet(false, true)) {
            usageScanAt = now + TimeUnit.SECONDS.toNanos(2)
            usageWorker.execute {
                try {
                    synchronized(this) {
                        val files = cacheFiles()
                        lastUsage = Usage(files.sumOf { it.bytes }, files.size)
                    }
                } catch (_: IOException) {
                    // Keep the last usable sample and retry while the page is visible.
                } finally {
                    usagePending.set(false)
                }
            }
        }
        return lastUsage
    }
    val directory: Path by lazy {
        val home = Paths.get(System.getProperty("user.home"))
        if (System.getProperty("os.name").startsWith("Windows")) {
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            (local ?: home.resolve("AppData/Local")).resolve("NetEase/CloudMusic/DangoMusicCache")
        } else {
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            (cache ?: home.resolve(".cache")).resolve("dangomusicce/netease")
        }
    }

    @Volatile internal var generation = 0L
        private set

    @Synchronized
    fun clear() {
        generation++
        try {
            if (Files.isDirectory(directory)) {
                Files.newDirectoryStream(directory, "*.audio").use { files ->
                    for (file in files) {
                        if (cacheName.matches(file.fileName.toString())) Files.deleteIfExists(file)
                    }
                }
            }
            lastUsage = Usage(0, 0)
        } finally {
            usageScanAt = System.nanoTime()
        }
    }

    internal fun key(id: Long, quality: NeteaseQuality, session: String): String {
        val bytes = "$id:${quality.id}:$session".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun contains(key: String): Boolean = Files.isRegularFile(directory.resolve("$key.audio"))

    @Synchronized
    internal fun read(key: String): Pair<NeteaseApi.SongUrl, ByteArray>? {
        val file = directory.resolve("$key.audio")
        if (!Files.isRegularFile(file)) return null
        return try {
            val result = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid cache version")
                val tier = input.readUTF()
                val stream = NeteaseStream(NeteaseQuality.values().firstOrNull { it.id == tier }, input.readLong(), input.readUTF())
                val gain = input.readFloat()
                val peak = input.readFloat()
                val size = input.readInt()
                if (size !in 10_000..NeteaseApi.MAX_AUDIO_BYTES || size > Files.size(file)) throw IOException("Invalid cache length")
                val checksum = input.readLong()
                val bytes = ByteArray(size)
                input.readFully(bytes)
                if (input.read() != -1 || checksum != checksum(bytes)) throw IOException("Corrupt audio cache")
                NeteaseApi.SongUrl("", false, gain, peak, neteaseStream = stream) to bytes
            }
            runCatching { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())) }
            result
        } catch (_: IOException) {
            runCatching { Files.deleteIfExists(file) }
            usageScanAt = System.nanoTime()
            null
        }
    }

    @Synchronized
    internal fun write(key: String, url: NeteaseApi.SongUrl, bytes: ByteArray, downloadGeneration: Long) {
        if (downloadGeneration != generation) return
        val stream = url.neteaseStream ?: return
        if (url.trialOnly || url.replaced || bytes.size !in 10_000..NeteaseApi.MAX_AUDIO_BYTES ||
            Mp3Probe.kind(bytes) !in setOf("flac", "ogg-vorbis", "id3", "mpeg-like")) return
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "audio-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temp).buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(stream.quality?.id.orEmpty())
                out.writeLong(stream.bitrate)
                out.writeUTF(stream.format)
                out.writeFloat(url.gainDb)
                out.writeFloat(url.peak)
                out.writeInt(bytes.size)
                out.writeLong(checksum(bytes))
                out.write(bytes)
            }
            val target = directory.resolve("$key.audio")
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            prune()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun checksum(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun prune() {
        val entries = cacheFiles()
        var total = entries.sumOf { it.bytes }
        var count = entries.size
        if (total > LIMIT) {
            for ((file, size) in entries.sortedBy { it.modified }) {
                if (total <= LIMIT) break
                if (Files.deleteIfExists(file) || !Files.exists(file)) {
                    total -= size
                    count--
                }
            }
        }
        lastUsage = Usage(total, count)
    }

    private fun cacheFiles(): List<CacheFile> =
        if (!Files.isDirectory(directory)) emptyList() else {
            Files.newDirectoryStream(directory, "*.audio").use { files ->
                files.mapNotNull { file ->
                    if (!cacheName.matches(file.fileName.toString())) return@mapNotNull null
                    try {
                        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isRegularFile) CacheFile(file, attrs.size(), attrs.lastModifiedTime()) else null
                    } catch (_: IOException) {
                        null
                    }
                }
            }
        }
}
