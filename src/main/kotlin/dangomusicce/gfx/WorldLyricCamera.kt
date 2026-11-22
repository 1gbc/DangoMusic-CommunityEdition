package dangomusicce.gfx

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The perspective used to render the world, including the actual camera translation and FOV. */
class WorldLyricCamera internal constructor(view: Matrix4f, private val projection: Matrix4f) {
    private val cameraToWorld = view.determinant().let { det ->
        if (!det.isFinite() || abs(det) < 1e-12f) null else Matrix4f(view).invert()
    }
    private val viewProjection = Matrix4f(projection).mul(view)

    internal fun place(
        depth: Float, scale: Float, halfWidth: Float, top: Float, bottom: Float, floor: Float, facePlayer: Boolean,
    ): WorldLyricPose? {
        val inverse = cameraToWorld ?: return null
        val m00 = projection.m00()
        val m11 = projection.m11()
        val w = projection.m33() - projection.m23() * depth
        if (!w.isFinite() || w <= 0f || m00 <= 0f || m11 <= 0f) return null
        val screenX = -0.5f
        val x = (screenX * w + projection.m20() * depth - projection.m30()) / m00
        val y = (0.12f * w + projection.m21() * depth - projection.m31()) / m11
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
        val centerClip = projection.transform(Vector4f(x, y, -depth, 1f))

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
            val lowestY = center.y + (min(top * down.y, bottom * down.y) - halfWidth * abs(right.y)) * s
            val cameraUpY = inverse.m11() / m11
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
        val position = toWorld(inverse, x, y + shift / m11, -depth)
        return WorldLyricPose(position, eye, right, down, size)
    }

    private fun clipOffset(right: Vector3f, down: Vector3f, x: Float, y: Float): Vector4f =
        viewProjection.transform(
            Vector4f(right.x * x + down.x * y, right.y * x + down.y * y, right.z * x + down.z * y, 0f),
        )

    private fun directionInWorld(inverse: Matrix4f, x: Double, y: Double, z: Double): Vector3f {
        val d = inverse.transform(Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), 0f))
        return Vector3f(d.x, d.y, d.z)
    }

    private fun toWorld(inverse: Matrix4f, x: Float, y: Float, z: Float): Vector3f {
        val p = inverse.transform(Vector4f(x, y, z, 1f))
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
    private val normal = Vector3f(right).cross(down)
    val yaw get() = Math.toDegrees(atan2(-normal.x.toDouble(), normal.z.toDouble())).toFloat()
    val pitch get() = Math.toDegrees(atan2(-normal.y.toDouble(),
        sqrt((normal.x * normal.x + normal.z * normal.z).toDouble()))).toFloat()

    fun transform(x: Float, y: Float, reach: Float): Matrix4f {
        val origin = point(x, y, reach)
        val size = reach * scale
        return Matrix4f().set(
            right.x * size, right.y * size, right.z * size, 0f,
            down.x * size, down.y * size, down.z * size, 0f,
            normal.x * size, normal.y * size, normal.z * size, 0f,
            origin.x, origin.y, origin.z, 1f,
        )
    }

    /** Move and shrink the whole plane along the camera rays to keep its projection fixed. */
    fun point(x: Float, y: Float, reach: Float): Vector3f = Vector3f(
        eye.x + (position.x + (right.x * x + down.x * y) * scale - eye.x) * reach,
        eye.y + (position.y + (right.y * x + down.y * y) * scale - eye.y) * reach,
        eye.z + (position.z + (right.z * x + down.z * y) * scale - eye.z) * reach,
    )
}
