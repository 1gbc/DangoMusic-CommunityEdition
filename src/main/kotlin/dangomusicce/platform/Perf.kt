package dangomusicce.platform

import dangomusicce.DangoLogger
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * 性能测量（启动参数加 -Ddangomusicce.perf=1 或 -Ddangomusic.perf=1 时开启）：
 * 记录 CPU 与 GPU 时间戳统计，每 5 秒在日志输出一次平均值。
 */
internal object Perf {
    @JvmField
    val enabled = System.getProperty("dangomusicce.perf") != null || System.getProperty("dangomusic.perf") != null

    private class Section(val name: String) {
        var cpuNs = 0L
        var gpuNs = 0L
        var gpuCount = 0
        var count = 0
        var draws = 0L
        var startNs = 0L
        var startDraws = 0
        val pending = ArrayDeque<IntArray>()
        var open: IntArray? = null
    }

    private val sections = LinkedHashMap<String, Section>()
    private var since = 0L
    private var frames = 0

    fun begin(name: String) {
        if (!enabled) return
        Imm.flush()
        val s = sections.getOrPut(name) { Section(name) }
        s.startNs = System.nanoTime()
        s.startDraws = Imm.glDraws
        val q = IntArray(2)
        q[0] = GL15.glGenQueries()
        q[1] = GL15.glGenQueries()
        GL33.glQueryCounter(q[0], GL33.GL_TIMESTAMP)
        s.open = q
    }

    fun end(name: String) {
        if (!enabled) return
        Imm.flush()
        val s = sections[name] ?: return
        val q = s.open ?: return
        s.open = null
        GL33.glQueryCounter(q[1], GL33.GL_TIMESTAMP)
        s.pending.addLast(q)
        s.cpuNs += System.nanoTime() - s.startNs
        s.draws += (Imm.glDraws - s.startDraws)
        s.count++
        collect(s)
    }

    private fun collect(s: Section) {
        while (s.pending.isNotEmpty()) {
            val q = s.pending.first()
            if (GL15.glGetQueryObjecti(q[1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break
            val t0 = GL33.glGetQueryObjecti64(q[0], GL33.GL_QUERY_RESULT)
            val t1 = GL33.glGetQueryObjecti64(q[1], GL33.GL_QUERY_RESULT)
            s.gpuNs += t1 - t0
            s.gpuCount++
            GL15.glDeleteQueries(q[0])
            GL15.glDeleteQueries(q[1])
            s.pending.removeFirst()
        }
    }

    fun frame() {
        if (!enabled) return
        frames++
        val now = System.nanoTime()
        if (since == 0L) since = now
        if (now - since < 5_000_000_000L) return
        val fps = frames / ((now - since) / 1e9)
        val sb = StringBuilder()
        sb.append(String.format("Dango perf: %.0f fps (%.2f ms/frame)", fps, 1000.0 / fps))
        for (s in sections.values) {
            if (s.count == 0) continue
            sb.append(
                String.format(
                    " | %s: cpu %.2f ms, gpu %.2f ms, %d draws (x%.1f per frame)",
                    s.name,
                    s.cpuNs / 1e6 / s.count,
                    if (s.gpuCount > 0) s.gpuNs / 1e6 / s.gpuCount else 0.0,
                    s.draws / s.count,
                    s.count.toDouble() / frames,
                ),
            )
            s.cpuNs = 0; s.gpuNs = 0; s.gpuCount = 0; s.count = 0; s.draws = 0
        }
        DangoLogger.info(sb.toString())
        frames = 0
        since = now
    }
}
