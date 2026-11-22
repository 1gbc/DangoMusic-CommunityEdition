package dangomusicce.shader.impls.ui

import dangomusicce.shader.GlShader
import dangomusicce.ui.Size
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.UiStyle

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class ImageShader : GlShader(
    "ui/image.vert",
    "ui/image.frag"
){
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Texture",
            "u_Opacity",
            "u_Bounds",
            "u_Radius",
            "u_Continuous",
            "u_FlowPhase",
            "u_CaptionShade", "u_OccluderCount", "u_OccluderRadiusRatio", "u_Brightness", "u_Placeholder", "u_HorizontalBlur",
        ) + Array(12) { "u_Occluders[$it]" }
    }

    fun setup(mat4: Matrix4f, screenSize: Size, opacity: Float, bounds: LayoutBounds, radius: Float, phase: Float,
              captionShade: Float, occluders: List<LayoutBounds> = emptyList(), brightness: Float = 1f,
              placeholder: Boolean = false, horizontalBlur: Float = 0f){
        val occluderCount = minOf(12, occluders.size)
        setUniform1i("u_OccluderCount", occluderCount)
        setUniform1f("u_HorizontalBlur", horizontalBlur)
        setUniform1f("u_OccluderRadiusRatio", radius / bounds.width.coerceAtLeast(1f))
        setUniform1f("u_Brightness", brightness)
        setUniform1f("u_Placeholder", if (placeholder) 1f else 0f)
        for (index in 0 until occluderCount) {
            val box = occluders[index]
            setUniform4f("u_Occluders[$index]", box.x, box.y, box.width, box.height)
        }
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform1i("u_Texture", 0)
        setUniform1f("u_Opacity", opacity)
        setUniform4f("u_Bounds",bounds.x,bounds.y,bounds.width,bounds.height)
        setUniform1f("u_Radius", radius)
        setUniform1f("u_FlowPhase", phase)
        setUniform1f("u_CaptionShade", captionShade)
        setUniform1f("u_Continuous",if(UiStyle.continuous) 1f else 0f)
    }
}
