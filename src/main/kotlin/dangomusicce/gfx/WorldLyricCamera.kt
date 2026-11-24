package dangomusicce.gfx

import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector3f
import org.lwjgl.util.vector.Vector4f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The perspective used to render the world, including the actual camera translation and FOV. */
class WorldLyricCamera internal constructor(view: Matrix4f, private val projection: Matrix4f) {
    private val cameraToWorld = Matrix4f.invert(view, null)
    private val viewProjection = Matrix4f.mul(projection, view, null)
    internal val angles = ViewAngles.fromViewBack(
        view.m02.toDouble(), view.m12.toDouble(), view.m22.toDouble(), view.m00.toDouble(), view.m20.toDouble(),
    )

    internal fun place(
        depth: Float, scale: Float, halfWidth: Float, top: Float, bottom: Float, floor: Float, facePlayer: Boolean,
        screenOffsetX: Float = 0f, screenOffsetY: Float = 0f,
    ): WorldLyricPose? {
        val inverse = cameraToWorld ?: return null
        val w = projection.m33 - projection.m23 * depth
        if (!w.isFinite() || w <= 0f || projection.m00 <= 0f || projection.m11 <= 0f) return null
        val screenX = -0.5f
        val x = (screenX * w + projection.m20 * depth - projection.m30) / projection.m00
        val y = (0.12f * w + projection.m21 * depth - projection.m31) / projection.m11
        val eye = toWorld(inverse, 0f, 0f, 0f)
        val center = toWorld(inverse, x, y, -depth)
        if (!center.x.isFinite() || !center.y.isFinite() || !center.z.isFinite()) return null

        // Preserve the resting perspective on the left, in camera coordinates. Looking
        // toward the poles or rolling the view must not alter that tilt on screen.
        val yaw = if (facePlayer) TILT_YAW else 0.0
        val pitch = if (facePlayer) TILT_PITCH else 0.0
        val yawSin = sin(yaw)
        val yawCos = cos(yaw)
        val pitchSin = sin(pitch)
        val right = directionInWorld(inverse, yawCos, 0.0, -yawSin)
        val down = directionInWorld(inverse, -yawSin * pitchSin, -cos(pitch), -yawCos * pitchSin)
        val corners = arrayOf(
            clipOffset(right, down, -halfWidth, top), clipOffset(right, down, halfWidth, top),
            clipOffset(right, down, -halfWidth, bottom), clipOffset(right, down, halfWidth, bottom),
        )
        val centerClip = Matrix4f.transform(projection, Vector4f(x, y, -depth, 1f), null)

        // Fit the actual rotated plane into the left half of the viewport. The bounds
        // are in clip space so resizing and changes to the game's FOV need no GUI units.
        fun verticalShift(s: Float, horizontalShift: Float = 0f): Float? {
            var low = Float.NEGATIVE_INFINITY
            var high = Float.POSITIVE_INFINITY
            for (corner in corners) {
                val cornerW = centerClip.w + corner.w * s
                val cornerX = centerClip.x + corner.x * s
                if (cornerW <= 0f || cornerX < -0.94f * cornerW || cornerX > -0.05f * cornerW) return null
                val cornerY = centerClip.y + corner.y * s
                low = maxOf(low, -0.90f * cornerW - cornerY)
                high = minOf(high, 0.90f * cornerW - cornerY)
            }
            if (low > high) return null
            val lowestY = center.y + inverse.m01 * horizontalShift / projection.m00 +
                (min(top * down.y, bottom * down.y) - halfWidth * abs(right.y)) * s
            val cameraUpY = inverse.m11 / projection.m11
            val desired = screenOffsetY * w
            val shift = if (floor.isFinite() && abs(cameraUpY) > 0.001f) {
                val groundShift = (floor - lowestY) / cameraUpY
                if (cameraUpY > 0f) maxOf(groundShift, desired) else minOf(groundShift, desired)
            } else desired
            return shift.coerceIn(low, high)
        }

        var size = scale
        if (verticalShift(size) == null) {
            var low = 0f
            var high = scale
            repeat(14) {
                val mid = (low + high) * 0.5f
                if (verticalShift(mid) == null) high = mid else low = mid
            }
            size = low
        }
        // Retain the resting text size. At the edge a wide stack has less room to lag.
        var minX = Float.NEGATIVE_INFINITY
        var maxX = Float.POSITIVE_INFINITY
        for (corner in corners) {
            val cornerW = centerClip.w + corner.w * size
            val cornerX = centerClip.x + corner.x * size
            minX = maxOf(minX, -0.94f * cornerW - cornerX)
            maxX = minOf(maxX, -0.05f * cornerW - cornerX)
        }
        val horizontalShift = (screenOffsetX * w).coerceIn(minX.coerceAtMost(0f), maxX.coerceAtLeast(0f))
        val verticalShift = verticalShift(size, horizontalShift) ?: return null
        val position = toWorld(inverse, x + horizontalShift / projection.m00, y + verticalShift / projection.m11, -depth)
        return WorldLyricPose(position, eye, right, down, size)
    }

    /** Fit the rendered single line after the spring, floor lift and block avoidance have run. */
    internal fun fitFloatingLine(
        x: Double, y: Double, z: Double, yaw: Float, pitch: Float, scale: Float,
        halfWidth: Float, top: Float, bottom: Float, referenceDepth: Float, floor: Float, strength: Float,
    ): Matrix4f? {
        val inverse = cameraToWorld ?: return null
        if (projection.m00 <= 0f || projection.m11 <= 0f || projection.m23 >= 0f) return null
        val anchor = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
        val center = Matrix4f.transform(viewProjection, Vector4f(anchor.x, anchor.y, anchor.z, 1f), null)
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawSin = sin(yawRad).toFloat()
        val yawCos = cos(yawRad).toFloat()
        val pitchSin = sin(pitchRad).toFloat()
        // The same world-space glyph axes as LegacyWorld.push.
        val right = Vector3f(-yawCos, 0f, -yawSin)
        val down = Vector3f(yawSin * pitchSin, -cos(pitchRad).toFloat(), -yawCos * pitchSin)
        val corners = arrayOf(
            clipOffset(right, down, -halfWidth, top), clipOffset(right, down, halfWidth, top),
            clipOffset(right, down, -halfWidth, bottom), clipOffset(right, down, halfWidth, bottom),
        )
        val eye = toWorld(inverse, 0f, 0f, 0f)
        val referenceW = projection.m33 - projection.m23 * referenceDepth
        if (!center.w.isFinite() || !referenceW.isFinite() || referenceW <= 0f) return null

        // The spring keeps a radial distance, but perspective uses forward depth.
        // Ease in a depth floor before any corner of the text can rush toward the lens.
        val nearestW = center.w + corners.minOf { it.w } * scale
        val band = referenceW * 0.2f
        val deficit = (referenceW - nearestW).coerceAtLeast(0f)
        val extraW = if (deficit < band) deficit * deficit / (2f * band) else deficit - band * 0.5f
        if (extraW > 0f) {
            val dz = extraW / projection.m23
            Vector3f.add(anchor, directionInWorld(inverse, 0.0, 0.0, dz.toDouble()), anchor)
            val clipShift = Matrix4f.transform(projection, Vector4f(0f, 0f, dz, 0f), null)
            Vector4f.add(center, clipShift, center)
        }
        if (strength <= 0f) {
            return if (extraW > 0f) WorldLyricPose(anchor, eye, right, down, scale).transform(0f, 0f, 1f) else null
        }

        fun fallback(): Matrix4f? {
            // If the tilted text is too wide to fit, use a front-facing plane.
            val depthW = referenceW
            val edge = 0.98f * depthW
            val size = minOf(scale, edge * 0.999f / (halfWidth * projection.m00),
                1.998f * edge / ((bottom - top) * projection.m11))
            val xMargin = halfWidth * size * projection.m00
            val lowY = -edge + bottom * size * projection.m11
            val highY = edge + top * size * projection.m11
            val targetScale = depthW / abs(center.w).coerceAtLeast(0.05f)
            val targetX = (center.x * targetScale).coerceIn(-edge + xMargin, edge - xMargin)
            val targetY = (center.y * targetScale).coerceIn(lowY, highY)
            val cameraX = (targetX + projection.m20 * referenceDepth - projection.m30) / projection.m00
            val centerY = (targetY + projection.m21 * referenceDepth - projection.m31) / projection.m11
            val position = toWorld(inverse, cameraX, centerY, -referenceDepth)
            val cameraRight = directionInWorld(inverse, 1.0, 0.0, 0.0)
            val cameraDown = directionInWorld(inverse, 0.0, -1.0, 0.0)
            val cameraUpY = inverse.m11 / projection.m11
            if (floor.isFinite() && abs(cameraUpY) > 0.001f) {
                val lowest = position.y + (min(top * cameraDown.y, bottom * cameraDown.y) -
                    halfWidth * abs(cameraRight.y)) * size
                val lift = (floor - lowest).coerceAtLeast(0f) / cameraUpY
                val shiftY = (targetY + lift).coerceIn(lowY, highY) - targetY
                val shift = directionInWorld(inverse, 0.0, (shiftY / projection.m11).toDouble(), 0.0)
                Vector3f.add(position, shift, position)
            }
            val fitted = WorldLyricPose(position, eye, cameraRight, cameraDown, size).transform(0f, 0f, 1f)
            if (strength >= 1f) return fitted
            val original = WorldLyricPose(anchor, eye, right, down, scale).transform(0f, 0f, 1f)
            return blendPoses(original, fitted, strength)
        }

        // At constant camera depth, horizontal and vertical translation can move
        // the entire plane inside the viewport without changing its tilt or size.
        var minX = Float.NEGATIVE_INFINITY
        var maxX = Float.POSITIVE_INFINITY
        var minY = Float.NEGATIVE_INFINITY
        var maxY = Float.POSITIVE_INFINITY
        for (corner in corners) {
            val w = center.w + corner.w * scale
            val zClip = center.z + corner.z * scale
            if (!w.isFinite() || !zClip.isFinite() || w <= 0f || abs(zClip) >= w) return fallback()
            val xClip = center.x + corner.x * scale
            val yClip = center.y + corner.y * scale
            val edge = 0.98f * w
            minX = maxOf(minX, -edge - xClip)
            maxX = minOf(maxX, edge - xClip)
            minY = maxOf(minY, -edge - yClip)
            maxY = minOf(maxY, edge - yClip)
        }
        if (minX > maxX || minY > maxY) return fallback()
        val dx = 0f.coerceIn(minX, maxX)
        val dy = 0f.coerceIn(minY, maxY)
        if (dx == 0f && dy == 0f && extraW == 0f) return null

        val shift = directionInWorld(inverse, (dx * strength / projection.m00).toDouble(),
            (dy * strength / projection.m11).toDouble(), 0.0)
        Vector3f.add(anchor, shift, anchor)
        return WorldLyricPose(anchor, eye, right, down, scale).transform(0f, 0f, 1f)
    }

    private fun blendPoses(from: Matrix4f, to: Matrix4f, weight: Float): Matrix4f {
        fun blend(a: Float, b: Float) = a + (b - a) * weight
        return Matrix4f().apply {
            m00 = blend(from.m00, to.m00); m01 = blend(from.m01, to.m01); m02 = blend(from.m02, to.m02)
            m10 = blend(from.m10, to.m10); m11 = blend(from.m11, to.m11); m12 = blend(from.m12, to.m12)
            m20 = blend(from.m20, to.m20); m21 = blend(from.m21, to.m21); m22 = blend(from.m22, to.m22)
            m30 = blend(from.m30, to.m30); m31 = blend(from.m31, to.m31); m32 = blend(from.m32, to.m32)
        }
    }

    /** Cap the extra floor lift against the real vertical frustum, leaving room for the text. */
    internal fun limitUpwardLift(x: Double, y: Double, z: Double, requested: Double): Double {
        val center = Matrix4f.transform(viewProjection, Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), 1f), null)
        if (!center.y.isFinite() || !center.w.isFinite() || center.w <= 0f) return 0.0
        val margin = 0.85
        val upY = viewProjection.m11.toDouble()
        val upW = viewProjection.m13.toDouble()
        // All clip-space coordinates are linear in the extra world-up displacement.
        fun limit(room: Double, slope: Double): Double =
            if (slope < 0.0) (room / -slope).coerceAtLeast(0.0) else requested
        return minOf(
            requested,
            limit(margin * center.w - center.y, margin * upW - upY),
            limit(margin * center.w + center.y, margin * upW + upY),
            limit(center.w - 0.05, upW),
        )
    }

    private fun clipOffset(right: Vector3f, down: Vector3f, x: Float, y: Float): Vector4f =
        Matrix4f.transform(viewProjection, Vector4f(right.x * x + down.x * y, right.y * x + down.y * y,
            right.z * x + down.z * y, 0f), null)

    private fun directionInWorld(inverse: Matrix4f, x: Double, y: Double, z: Double): Vector3f {
        val d = Matrix4f.transform(inverse, Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), 0f), null)
        return Vector3f(d.x, d.y, d.z)
    }

    private fun toWorld(inverse: Matrix4f, x: Float, y: Float, z: Float): Vector3f {
        val p = Matrix4f.transform(inverse, Vector4f(x, y, z, 1f), null)
        return Vector3f(p.x, p.y, p.z)
    }

    private companion object {
        val TILT_YAW = Math.toRadians(32.0)
        val TILT_PITCH = Math.toRadians(4.0)
    }
}

internal class WorldLyricPose(
    private val position: Vector3f,
    val eye: Vector3f,
    private val right: Vector3f,
    private val down: Vector3f,
    val scale: Float,
) {
    private val normal = Vector3f.cross(right, down, null)
    val yaw get() = Math.toDegrees(atan2(-normal.x.toDouble(), normal.z.toDouble())).toFloat()
    val pitch get() = Math.toDegrees(atan2(-normal.y.toDouble(),
        sqrt((normal.x * normal.x + normal.z * normal.z).toDouble()))).toFloat()

    fun transform(x: Float, y: Float, reach: Float): Matrix4f {
        val origin = point(x, y, reach)
        val size = reach * scale
        return Matrix4f().apply {
            m00 = right.x * size; m01 = right.y * size; m02 = right.z * size
            m10 = down.x * size; m11 = down.y * size; m12 = down.z * size
            m20 = normal.x * size; m21 = normal.y * size; m22 = normal.z * size
            m30 = origin.x; m31 = origin.y; m32 = origin.z
        }
    }

    /** Move and shrink the whole plane along the camera rays to keep its projection fixed. */
    fun point(x: Float, y: Float, reach: Float): Vector3f = Vector3f(
        eye.x + (position.x + (right.x * x + down.x * y) * scale - eye.x) * reach,
        eye.y + (position.y + (right.y * x + down.y * y) * scale - eye.y) * reach,
        eye.z + (position.z + (right.z * x + down.z * y) * scale - eye.z) * reach,
    )
}
