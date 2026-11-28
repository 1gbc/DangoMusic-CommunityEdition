package dangomusicce.client

import dangomusicce.legacy.FreecamScreen
import dangomusicce.platform.PlayerPose
import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLivingBase
import net.minecraft.util.MathHelper

internal object RenderCameraPose {
    fun activeViewEntity(mc: Minecraft): EntityLivingBase? {
        val world = mc.theWorld ?: return null
        return mc.renderViewEntity?.takeIf { it.worldObj === world }
            ?: mc.thePlayer?.takeIf { it.worldObj === world }
    }

    /** 1.7.10 相机眼高偏移：EntityPlayerSP 的 yOffset=1.62（posY 已在眼高），EntityOtherPlayerMP 的 yOffset=0（posY 在脚底）。 */
    fun eyeY(entity: EntityLivingBase, partialTicks: Float = 1f): Double =
        entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks + (1.62 - entity.yOffset.toDouble())

    fun pose(mc: Minecraft, partialTicks: Float = 1f): PlayerPose? {
        val entity = activeViewEntity(mc) ?: return null
        val x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks
        val y = eyeY(entity, partialTicks)
        val z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks
        val yaw = entity.prevRotationYaw + MathHelper.wrapAngleTo180_float(entity.rotationYaw - entity.prevRotationYaw) * partialTicks
        val pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks
        return PlayerPose(x, y, z, yaw, pitch)
    }

    fun isExternalCameraActive(mc: Minecraft): Boolean =
        mc.renderViewEntity != null && mc.thePlayer != null && mc.renderViewEntity !== mc.thePlayer &&
            !FreecamScreen.ownsViewEntity(mc.renderViewEntity)
}
