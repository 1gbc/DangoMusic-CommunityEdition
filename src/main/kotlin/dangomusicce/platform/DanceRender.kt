package dangomusicce.platform

import com.mojang.blaze3d.vertex.PoseStack
import dangomusicce.music.Dance
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix3f
import org.joml.Matrix4f
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * 生物跟着节奏跳：限定仅在世界实体渲染阶段生效（排除物品栏/GUI 假人），直接修改当前栈顶矩阵并在 Post 还原，
 * 不改变 PoseStack 深度以避免其它 LOWEST 监听器取消事件时触发栈未清空崩溃。
 */
object DanceRender {
    private class ActivePose(
        val entity: LivingEntity,
        val spin: Float,
        val pose: PoseStack.Pose,
        val savedPose: Matrix4f,
        val savedNormal: Matrix3f,
    )

    private var inWorldPass = false
    private val active = ArrayDeque<ActivePose>()

    private val irisShadowActiveField by lazy {
        runCatching {
            Class.forName("net.irisshaders.iris.shadows.ShadowRenderer")
                .getField("ACTIVE")
                .takeIf { it.type == Boolean::class.javaPrimitiveType }
        }.getOrNull()
    }

    private fun isIrisShadowPass(): Boolean = irisShadowActiveField?.getBoolean(null) == true

    @JvmStatic
    fun beginWorldPass() {
        unwindAll()
        inWorldPass = true
    }

    @JvmStatic
    fun endWorldPass() {
        inWorldPass = false
        unwindAll()
    }

    @JvmStatic
    fun onLivingPre(entity: LivingEntity, poseStack: PoseStack) {
        if (!inWorldPass && !isIrisShadowPass()) return
        val pose = Dance.pose() ?: return
        val last = poseStack.last()
        if (active.any { it.entity === entity }) {
            while (active.isNotEmpty()) {
                val top = active.removeLast()
                restore(top, restorePose = (top.pose === last))
                if (top.entity === entity) break
            }
        }
        val spin = pose[0]
        val entry = ActivePose(entity, spin, last, Matrix4f(last.pose()), Matrix3f(last.normal()))
        entity.yBodyRot += spin
        entity.yBodyRotO += spin
        entity.yHeadRot += spin
        entity.yHeadRotO += spin
        squash(poseStack, pose[1])
        active.addLast(entry)
    }

    @JvmStatic
    fun onLivingPost(entity: LivingEntity) {
        if (active.none { it.entity === entity }) return
        while (active.isNotEmpty()) {
            val top = active.removeLast()
            restore(top, restorePose = true)
            if (top.entity === entity) break
        }
    }

    @JvmStatic
    fun squash(poseStack: PoseStack, squash: Float) {
        val wide = 1f / sqrt(squash)
        poseStack.scale(wide, squash, wide)
    }

    private fun unwindAll() {
        while (active.isNotEmpty()) {
            restore(active.removeLast(), restorePose = false)
        }
    }

    private fun restore(entry: ActivePose, restorePose: Boolean) {
        val e = entry.entity
        val spin = entry.spin
        e.yBodyRot -= spin
        e.yBodyRotO -= spin
        e.yHeadRot -= spin
        e.yHeadRotO -= spin
        if (restorePose) {
            entry.pose.pose().set(entry.savedPose)
            entry.pose.normal().set(entry.savedNormal)
        }
    }
}
