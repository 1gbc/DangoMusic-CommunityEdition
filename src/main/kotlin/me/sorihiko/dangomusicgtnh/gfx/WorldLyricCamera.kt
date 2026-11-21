package me.sorihiko.dangomusicgtnh.gfx

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
    private val angles = ViewAngles.fromViewBack(
        view.m02.toDouble(), view.m12.toDouble(), view.m22.toDouble(),
        view.m00.toDouble(), view.m20.toDouble(),
    )

    internal fun place(
        depth: Float, scale: Float, halfWidth: Float, top: Float, bottom: Float, floor: Float, facePlayer: Boolean,
    ): WorldLyricPose? {
        val inverse = cameraToWorld ?: return null
        val view = angles ?: return null
        val w = projection.m33 - projection.m23 * depth
        if (!w.isFinite() || w <= 0f || projection.m00 <= 0f || projection.m11 <= 0f) return null
        val screenX = -0.5f
        val x = (screenX * w + projection.m20 * depth - projection.m30) / projection.m00
        val y = (0.12f * w + projection.m21 * depth - projection.m31) / projection.m11
        val eye = toWorld(inverse, 0f, 0f, 0f)
        val center = toWorld(inverse, x, y, -depth)
        if (!center.x.isFinite() || !center.y.isFinite() || !center.z.isFinite()) return null

        // Turn the plane toward the lens. Near the poles, use the view angles so that
        // looking straight up or down cannot turn the entire stack on its side.
        val follow = if (facePlayer) ((80f - abs(view.pitch)) / 20f).coerceIn(0f, 1f) else 0f
        val dx = (center.x - eye.x).toDouble()
        val dy = (center.y - eye.y).toDouble()
        val dz = (center.z - eye.z).toDouble()
        val yawDelta = Math.toDegrees(atan2(-dx, dz)).toFloat() - view.yaw
        val yaw = view.yaw + (((yawDelta + 540f) % 360f) - 180f) * follow
        val facePitch = Math.toDegrees(atan2(-dy, sqrt(dx * dx + dz * dz))).toFloat()
        val pitch = view.pitch + (facePitch - view.pitch) * follow
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val right = Vector3f(-cos(yr).toFloat(), 0f, -sin(yr).toFloat())
        val down = Vector3f(
            (sin(yr) * sin(pr)).toFloat(), -cos(pr).toFloat(), (-cos(yr) * sin(pr)).toFloat(),
        )
        val corners = arrayOf(
            clipOffset(right, down, -halfWidth, top), clipOffset(right, down, halfWidth, top),
            clipOffset(right, down, -halfWidth, bottom), clipOffset(right, down, halfWidth, bottom),
        )
        val centerClip = Matrix4f.transform(projection, Vector4f(x, y, -depth, 1f), null)

        // Fit the actual rotated plane into the left half of the viewport. The bounds
        // are in clip space so resizing and changes to the game's FOV need no GUI units.
        fun verticalShift(s: Float): Float? {
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
            val lowestY = center.y + min(top * down.y, bottom * down.y) * s
            val cameraUpY = inverse.m11 / projection.m11
            val shift = if (floor.isFinite() && abs(cameraUpY) > 0.001f) {
                val groundShift = (floor - lowestY) / cameraUpY
                if (cameraUpY > 0f) maxOf(groundShift, 0f) else minOf(groundShift, 0f)
            } else 0f
            return shift.coerceIn(low, high)
        }

        var size = scale
        var shift = verticalShift(size)
        if (shift == null) {
            var low = 0f
            var high = scale
            repeat(14) {
                val mid = (low + high) * 0.5f
                if (verticalShift(mid) == null) high = mid else low = mid
            }
            size = low
            shift = verticalShift(size) ?: return null
        }
        val position = toWorld(inverse, x, y + shift / projection.m11, -depth)
        return WorldLyricPose(position, eye, right, down, yaw, pitch, size)
    }

    private fun clipOffset(right: Vector3f, down: Vector3f, x: Float, y: Float): Vector4f =
        Matrix4f.transform(viewProjection, Vector4f(right.x * x + down.x * y, right.y * x + down.y * y,
            right.z * x + down.z * y, 0f), null)

    private fun toWorld(inverse: Matrix4f, x: Float, y: Float, z: Float): Vector3f {
        val p = Matrix4f.transform(inverse, Vector4f(x, y, z, 1f), null)
        return Vector3f(p.x, p.y, p.z)
    }
}

internal class WorldLyricPose(
    private val position: Vector3f,
    val eye: Vector3f,
    private val right: Vector3f,
    private val down: Vector3f,
    val yaw: Float,
    val pitch: Float,
    val scale: Float,
) {
    /** Move and shrink the whole plane along the camera rays to keep its projection fixed. */
    fun point(x: Float, y: Float, reach: Float): Vector3f = Vector3f(
        eye.x + (position.x + (right.x * x + down.x * y) * scale - eye.x) * reach,
        eye.y + (position.y + (right.y * x + down.y * y) * scale - eye.y) * reach,
        eye.z + (position.z + (right.z * x + down.z * y) * scale - eye.z) * reach,
    )
}
