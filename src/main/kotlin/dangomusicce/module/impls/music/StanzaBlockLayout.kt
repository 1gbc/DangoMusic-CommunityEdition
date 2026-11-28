package dangomusicce.module.impls.music

import dangomusicce.lyrics.LyricLine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 诗节排版（多行 3D 歌词成组漂浮）的纯布局与分块数学模块。
 * 独立于 Minecraft / OpenGL / 播放状态，便于直接做离线单元测试。
 */
internal object StanzaBlockLayout {

    /** 同一块内相邻两句的最大真实静默停顿（有逐字时间戳时，毫秒），超过即视作间奏断块 */
    const val MAX_SILENCE_MS = 8000L

    /** 纯 LRC 无逐字结束时间时，相邻两句开始时间差超过该值才视作长间奏断块（毫秒） */
    const val MAX_LRC_GAP_MS = 12000L

    /** 块内各行错峰淡入的间隔与单行淡入时长 */
    const val STAGGER_NANOS = 110_000_000L
    const val FADE_IN_NANOS = 360_000_000L

    /** 整块唱完退场淡出时长 */
    const val RETIRE_FADE_NANOS = 420_000_000L

    /** 唱到当前块最后一句时，距下一块开始不超过该时长才在对侧预告下一块 */
    const val HANDOVER_LEAD_MS = 3000L

    /** 两块之间的空档里，距下一块开始不超过该时长提前预告 */
    const val GAP_LEAD_MS = 2500L

    /** 块内相邻行之间的纵向基础间距（文字像素） */
    const val LINE_GAP = 10f

    /** 一组连续非空歌词的源下标与起止时间 */
    data class StanzaSpec(val indices: List<Int>, val startMs: Long, val endMs: Long)

    /** 单行歌词（含音译与翻译）在本地文字坐标系下的尺寸 */
    data class LineMetrics(val width: Float, val topExtent: Float, val bottomExtent: Float)

    /** 块内每一行的本地错位、纵向中心、倾斜角度与淡入延迟 */
    data class SlotLayout(val offsetX: Float, val centerY: Float, val angleDeg: Float, val delayNanos: Long)

    /** 整块歌词在本地文字坐标系下的包围盒（纵向已围绕 0 居中） */
    data class BlockGeometry(val slots: List<SlotLayout>, val halfWidth: Float, val top: Float, val bottom: Float)

    /** 一块歌词在镜头视角空间里的角度包围盒（度） */
    data class BlockAnchorBox(val yaw: Double, val halfWidthDeg: Double, val topDeg: Double, val bottomDeg: Double)

    /** 算出的整块世界摆放角与防溢屏缩放系数 */
    data class Placement(val yaw: Float, val pitch: Float, val fitScale: Float)

    fun wrapDegrees(d: Double): Double {
        var v = d % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /** 将非空歌词按最多 [maxLines] 行一组切分为诗节块；仅在真正长间奏停顿时提前断块，慢歌正常行距不断块。 */
    fun partition(lines: List<LyricLine>, maxLines: Int): List<StanzaSpec> {
        val limit = maxLines.coerceIn(2, 4)
        val out = mutableListOf<StanzaSpec>()
        val cur = mutableListOf<Int>()
        fun flush() {
            if (cur.isEmpty()) return
            val indices = cur.toList()
            out += StanzaSpec(indices, lines[indices.first()].timeMs, lines[indices.last()].timeMs)
            cur.clear()
        }
        for (i in lines.indices) {
            val line = lines[i]
            if (line.text.isBlank()) continue
            if (cur.isNotEmpty()) {
                val prevLine = lines[cur.last()]
                val longGap = if (!prevLine.words.isNullOrEmpty()) {
                    line.timeMs - prevLine.ktvEndMs(line.timeMs) > MAX_SILENCE_MS
                } else {
                    line.timeMs - prevLine.timeMs > MAX_LRC_GAP_MS
                }
                if (cur.size >= limit || longGap) flush()
            }
            cur += i
        }
        flush()
        return out
    }

    /** 查找包含源行下标 [lineIndex] 的诗节块下标；找不到返回 -1。 */
    fun stanzaIndexFor(stanzas: List<StanzaSpec>, lineIndex: Int): Int {
        if (lineIndex < 0) return -1
        for (i in stanzas.indices) {
            if (lineIndex in stanzas[i].indices) return i
        }
        return -1
    }

    /** 计算下一块诗节的下标：当前在某块内时取后一块；在空档里取第一块起始行大于 [lastStartedLineIdx] 的块。 */
    fun nextStanzaIndex(stanzas: List<StanzaSpec>, currentStanzaIdx: Int, lastStartedLineIdx: Int): Int {
        if (currentStanzaIdx >= 0) {
            val next = currentStanzaIdx + 1
            return if (next < stanzas.size) next else -1
        }
        for (i in stanzas.indices) {
            if (stanzas[i].indices.first() > lastStartedLineIdx) return i
        }
        return -1
    }

    private fun hash01(a: Long, b: Int, salt: Int): Float {
        var h = (a xor (a ushr 32)).toInt() xor (b * 73856093) xor (salt * 19349663)
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
    }

    /** 确定性的单行倾斜角度（0° = 完全水平），散落模式与诗节排版共用。 */
    fun lineTiltDeg(seed: Long, lineIdx: Int, maxTiltDeg: Float): Float {
        val maxTilt = maxTiltDeg.coerceIn(0f, 15f)
        if (maxTilt <= 0.05f) return 0f
        val angMag = maxTilt * (0.55f + hash01(seed, lineIdx, 2) * 0.45f)
        val angSign = if (hash01(seed, lineIdx, 3) < 0.5f) -1f else 1f
        return angMag * angSign
    }

    /** 确定性的有机水平错位（±10..26px，按槽位交替）与按 [maxTiltDeg] 缩放的倾斜角（0° = 水平）。 */
    fun organicTransform(blockStartMs: Long, lineIdx: Int, slot: Int, maxTiltDeg: Float = 0f): Pair<Float, Float> {
        val oxMag = 10f + hash01(blockStartMs, lineIdx, 1) * 16f
        val oxSign = if ((slot and 1) == 0) -1f else 1f
        return (oxMag * oxSign) to lineTiltDeg(blockStartMs, lineIdx, maxTiltDeg)
    }

    /** 将块内各行从上到下堆叠并围绕纵向中心 0 对齐，按旋转后的真实高度排布确保大倾角下也不重叠。 */
    fun layoutSlots(
        blockStartMs: Long,
        indices: List<Int>,
        metrics: List<LineMetrics>,
        maxTiltDeg: Float = 0f,
    ): BlockGeometry {
        if (indices.isEmpty() || metrics.isEmpty()) {
            return BlockGeometry(emptyList(), 0f, 0f, 0f)
        }
        val n = min(indices.size, metrics.size)
        val oxs = FloatArray(n)
        val angs = FloatArray(n)
        val rawCenterY = FloatArray(n)
        var cursorY = 0f
        for (i in 0 until n) {
            val m = metrics[i]
            val (ox, ang) = organicTransform(blockStartMs, indices[i], i, maxTiltDeg)
            oxs[i] = ox
            angs[i] = ang
            val rad = Math.toRadians(ang.toDouble())
            val c = abs(cos(rad)).toFloat()
            val s = abs(sin(rad)).toFloat()
            val hw = m.width * 0.5f
            val rotTop = m.topExtent * c + hw * s
            val rotBottom = m.bottomExtent * c + hw * s
            if (i > 0) cursorY += LINE_GAP
            rawCenterY[i] = cursorY + rotTop
            cursorY += rotTop + rotBottom
        }
        val midY = cursorY * 0.5f
        val slots = ArrayList<SlotLayout>(n)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (i in 0 until n) {
            val m = metrics[i]
            val ox = oxs[i]
            val ang = angs[i]
            val cy = rawCenterY[i] - midY
            slots += SlotLayout(ox, cy, ang, i * STAGGER_NANOS)

            val rad = Math.toRadians(ang.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val hw = m.width * 0.5f
            val cornersX = floatArrayOf(-hw, hw, -hw, hw)
            val cornersY = floatArrayOf(-m.topExtent, -m.topExtent, m.bottomExtent, m.bottomExtent)
            for (k in 0..3) {
                val rx = ox + cornersX[k] * c - cornersY[k] * s
                val ry = cy + cornersX[k] * s + cornersY[k] * c
                if (rx < minX) minX = rx
                if (rx > maxX) maxX = rx
                if (ry < minY) minY = ry
                if (ry > maxY) maxY = ry
            }
        }
        val halfW = max(abs(minX), abs(maxX)).coerceAtLeast(1f)
        return BlockGeometry(slots, halfW, minY, maxY)
    }

    /** Keep the upcoming block on its original side while in contact. */
    fun pushYaw(current: BlockAnchorBox, upcoming: BlockAnchorBox, side: Int, padding: Double): Double {
        val verticalGap = max(upcoming.topDeg - current.bottomDeg, current.topDeg - upcoming.bottomDeg)
        if (verticalGap >= padding) return 0.0
        val avgPitch = (current.topDeg + current.bottomDeg + upcoming.topDeg + upcoming.bottomDeg) * 0.25
        val cosPitch = cos(Math.toRadians(avgPitch)).coerceAtLeast(0.2)
        val needed = (current.halfWidthDeg + upcoming.halfWidthDeg + padding) / cosPitch
        val currentGap = side * wrapDegrees(upcoming.yaw - current.yaw)
        return side * (needed - currentGap).coerceAtLeast(0.0)
    }

    /** 两个视角块包围盒之间的最小分离角（度，负数 = 重叠）。 */
    fun separation(a: BlockAnchorBox, b: BlockAnchorBox): Double {
        val avgPitch = ((a.topDeg + a.bottomDeg) * 0.5 + (b.topDeg + b.bottomDeg) * 0.5) * 0.5
        val cosPitch = cos(Math.toRadians(avgPitch)).coerceAtLeast(0.2)
        val gx = abs(wrapDegrees(a.yaw - b.yaw)) * cosPitch - (a.halfWidthDeg + b.halfWidthDeg)
        val gy = if (a.topDeg < b.topDeg) b.topDeg - a.bottomDeg else a.topDeg - b.bottomDeg
        return max(gx, gy)
    }

    /**
     * 为整块诗节选择屏幕内的 3D 摆放角与自适应防溢屏缩放：
     * - 当存在 [previousBox] 时，强制落在其水平对侧（左 ↔ 右）并要求与上一块保持安全间距；
     * - 若对侧仍无法在不重叠的前提下放下，则返回 null（跳过提前预告，避免两块重叠）。
     */
    fun placeBlock(
        geometry: BlockGeometry,
        worldScale: Double,
        distance: Double,
        viewYaw: Float,
        viewPitch: Float,
        hHalfDeg: Double,
        vHalfDeg: Double,
        spreadDeg: Double,
        maxPitchDeg: Double,
        previousBox: BlockAnchorBox?,
        seed: Long,
    ): Placement? {
        val d = distance.coerceAtLeast(1.0)
        // 超宽/超高诗节先在 0.55..1.0 内自适应微缩，保证整块留在视口内
        val maxAvailH = max(8.0, hHalfDeg - 4.0)
        val maxAvailV = max(8.0, vHalfDeg - 4.0)
        var fitScale = 1.0
        val rawHw = Math.toDegrees(atan2(geometry.halfWidth * worldScale, d))
        val rawUp = Math.toDegrees(atan2(-geometry.top * worldScale, d))
        val rawDown = Math.toDegrees(atan2(geometry.bottom * worldScale, d))
        val rawHalfV = max(rawUp, rawDown).coerceAtLeast(0.1)
        if (rawHw > maxAvailH * 0.85) fitScale = min(fitScale, (maxAvailH * 0.85) / rawHw)
        if (rawHalfV > maxAvailV * 0.85) fitScale = min(fitScale, (maxAvailV * 0.85) / rawHalfV)
        fitScale = fitScale.coerceIn(0.35, 1.0)

        val s = worldScale * fitScale
        val hw = Math.toDegrees(atan2(geometry.halfWidth * s, d))
        val up = Math.toDegrees(atan2(geometry.top * s, d))      // <= 0
        val down = Math.toDegrees(atan2(geometry.bottom * s, d)) // >= 0

        val yawCap = max(0.0, hHalfDeg - hw - 3.0)
        val yawRange = min(max(spreadDeg, 24.0), yawCap)
        val minP = max(-55.0, viewPitch - vHalfDeg - up + 3.0)
        val maxP = min(maxPitchDeg, viewPitch + vHalfDeg - down - 3.0)
        val lowP = min(minP, maxP)
        val highP = max(minP, maxP)
        val preferredPitch = (viewPitch - 6.0).coerceIn(lowP, highP)

        if (previousBox == null) {
            val side = if (hash01(seed, 0, 7) < 0.5f) -1.0 else 1.0
            val frac = 0.35 + hash01(seed, 1, 9) * 0.35
            val yaw = (viewYaw + side * yawRange * frac).toFloat()
            return Placement(yaw, preferredPitch.toFloat(), fitScale.toFloat())
        }

        val prevDelta = wrapDegrees(previousBox.yaw - viewYaw)
        val targetSide = if (prevDelta >= 0.0) -1.0 else 1.0

        var bestYaw = Float.NaN
        var bestPitch = Float.NaN
        var bestScore = Double.NEGATIVE_INFINITY
        val yawFracs = doubleArrayOf(0.45, 0.62, 0.78, 0.92, 1.0)
        for (yf in yawFracs) {
            val offset = targetSide * yawRange * yf
            val yaw = viewYaw + offset
            for (pi in 0..4) {
                val pitch = lowP + (highP - lowP) * (pi / 4.0)
                val box = BlockAnchorBox(yaw, hw, pitch + up, pitch + down)
                val sep = separation(box, previousBox)
                if (sep < 3.5) continue
                val score = min(sep, 14.0) - 0.04 * abs(pitch - preferredPitch) - 0.02 * abs(offset)
                if (score > bestScore) {
                    bestScore = score
                    bestYaw = yaw.toFloat()
                    bestPitch = pitch.toFloat()
                }
            }
        }
        return if (bestYaw.isFinite()) Placement(bestYaw, bestPitch, fitScale.toFloat()) else null
    }
}
