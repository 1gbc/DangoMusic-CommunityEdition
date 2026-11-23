package dangomusicce.platform

import dangomusicce.DangoLogger
import dangomusicce.music.AudioOutput
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * 用游戏自己的 OpenAL 放歌（Java Sound 不可用时使用），不切换当前环境。
 */
class GameAudioOutput private constructor(
    private val context: Long,
    private val sampleRate: Int,
    private val channels: Int,
) : AudioOutput {

    private val format = if (channels >= 2) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16
    private val frameBytes = 2 * channels.coerceIn(1, 2)
    private val chunkBytes = (sampleRate / 20).coerceAtLeast(256) * frameBytes
    private val lock = Object()

    private val source = AL10.alGenSources()
    private val freeBuffers = ArrayDeque<Int>()
    private val queuedFrames = ArrayDeque<Int>()
    private var createdBuffers = 0
    private var playedFrames = 0L

    private val pending: ByteBuffer = MemoryUtil.memAlloc(chunkBytes)

    @Volatile private var running = false
    @Volatile private var closed = false
    private var lastPositionMicros = 0L

    init {
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            MemoryUtil.memFree(pending)
            if (source != 0 && alive()) {
                AL10.alDeleteSources(source)
                AL10.alGetError()
            }
            throw IllegalStateException("OpenAL source unavailable")
        }
    }

    override val positionMicros: Long
        get() = synchronized(lock) {
            if (closed) return 0L
            if (!alive()) return lastPositionMicros
            val offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET).toLong()
            ((playedFrames + offset) * 1_000_000L / sampleRate).also { lastPositionMicros = it }
        }

    override val lost: Boolean get() = !closed && !alive()

    private fun alive() = AlContext.matches(context)

    override val isRunning: Boolean get() = running

    override fun start() = synchronized(lock) {
        running = true
        if (!closed && alive() && queuedFrames.isNotEmpty()) AL10.alSourcePlay(source)
    }

    override fun stop() = synchronized(lock) {
        running = false
        if (!closed && alive()) AL10.alSourcePause(source)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length - length % frameBytes
        while (left > 0) {
            val needSubmit = synchronized(lock) {
                if (closed) return
                val n = minOf(left, pending.remaining())
                pending.put(data, off, n)
                off += n
                left -= n
                !pending.hasRemaining()
            }
            if (needSubmit) submit()
        }
    }

    private fun submit() {
        val buffer = takeBuffer() ?: return
        synchronized(lock) {
            if (closed) {
                if (alive()) {
                    AL10.alDeleteBuffers(buffer)
                    AL10.alGetError()
                }
                return
            }
            checkContext()
            if (pending.position() == 0) {
                freeBuffers.addLast(buffer)
                return
            }
            pending.flip()
            val frames = pending.remaining() / frameBytes
            AL10.alBufferData(buffer, format, pending, sampleRate)
            pending.clear()
            AL10.alSourceQueueBuffers(source, buffer)
            queuedFrames.addLast(frames)
            if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source)
            check(AL10.alGetError() == AL10.AL_NO_ERROR) { "OpenAL playback error" }
        }
    }

    private fun takeBuffer(): Int? {
        while (true) {
            synchronized(lock) {
                if (closed) return null
                checkContext()
                reclaim()
                freeBuffers.removeFirstOrNull()?.let { return it }
                if (createdBuffers < BUFFERS) {
                    val b = AL10.alGenBuffers()
                    check(AL10.alGetError() == AL10.AL_NO_ERROR && b != 0) { "OpenAL buffer unavailable" }
                    createdBuffers++
                    return b
                }
            }
            Thread.sleep(5)
        }
    }

    private fun reclaim() {
        var done = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        while (done-- > 0) {
            freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source))
            playedFrames += queuedFrames.removeFirstOrNull() ?: 0
        }
    }

    private fun checkContext() {
        check(AlContext.matches(context)) { "Game audio was restarted" }
    }

    override fun drain() {
        val hasPending = synchronized(lock) {
            if (closed) return
            pending.position() > 0
        }
        if (hasPending) submit()
        while (true) {
            synchronized(lock) {
                if (closed) return
                checkContext()
                reclaim()
                if (queuedFrames.isEmpty()) return
                if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source)
            }
            Thread.sleep(10)
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (closed) return
            pending.clear()
            if (!alive()) return
            AL10.alSourceStop(source)
            reclaim()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        running = false
        try {
            if (AlContext.matches(context)) {
                AL10.alSourceStop(source)
                reclaim()
                AL10.alSourcei(source, AL10.AL_BUFFER, 0)
                AL10.alDeleteSources(source)
                for (b in freeBuffers) AL10.alDeleteBuffers(b)
                freeBuffers.clear()
                AL10.alGetError()
            }
        } finally {
            MemoryUtil.memFree(pending)
        }
    }

    override fun setVolume(volume: Float) = synchronized(lock) {
        if (!closed && alive()) AL10.alSourcef(source, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
    }

    companion object {
        private const val BUFFERS = 5

        fun open(sampleRate: Int, channels: Int): GameAudioOutput? {
            val context = AlContext.current()
            if (context == 0L || sampleRate <= 0 || channels <= 0) return null
            AL10.alGetError()
            val out = GameAudioOutput(context, sampleRate, channels)
            return try {
                AL10.alSourcei(out.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
                AL10.alSource3f(out.source, AL10.AL_POSITION, 0f, 0f, 0f)
                AL10.alSourcef(out.source, AL10.AL_ROLLOFF_FACTOR, 0f)
                out
            } catch (t: Throwable) {
                out.close()
                throw t
            }
        }
    }
}

private object AlContext {
    private const val UNKNOWN = -1L

    @Volatile private var missing = false
    private val address: Long by lazy {
        try {
            org.lwjgl.openal.ALC.getFunctionProvider()?.getFunctionAddress("alcGetCurrentContext") ?: 0L
        } catch (_: LinkageError) {
            0L
        }
    }

    fun current(): Long {
        if (!missing) {
            try {
                return ALC10.alcGetCurrentContext()
            } catch (_: LinkageError) {
                missing = true
                DangoLogger.warn("Dango Music: alcGetCurrentContext missing, looking it up directly")
            }
        }
        val fn = address
        if (fn != 0L) {
            try {
                return org.lwjgl.system.JNI.invokeP(fn)
            } catch (_: LinkageError) {
            }
        }
        return UNKNOWN
    }

    fun matches(context: Long): Boolean {
        if (context == UNKNOWN) return true
        val now = current()
        return now == UNKNOWN || now == context
    }
}
