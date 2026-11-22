package dangomusicce.legacy

import dangomusicce.gfx.Texture
import dangomusicce.gfx.Textures

import dangomusicce.legacy.GlStateManager
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12

import java.awt.image.BufferedImage

/** 1.8.9 的贴图：交给原版 TextureManager 管理 */
class LegacyTexture(val location: ResourceLocation) : Texture {
    fun bind() {
        GlStateManager.bindTexture(0)
        mc.textureManager.bindTexture(location)
    }
}

object LegacyTextures : Textures {
    override fun upload(name: String, image: BufferedImage, linear: Boolean): Texture {
        val dynamic = DynamicTexture(image)
        val texture = LegacyTexture(mc.textureManager.getDynamicTextureLocation(name, dynamic))
        if (linear) {
            texture.bind()
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        }
        return texture
    }

    override fun delete(texture: Texture) {
        mc.textureManager.deleteTexture((texture as LegacyTexture).location)
    }
}
