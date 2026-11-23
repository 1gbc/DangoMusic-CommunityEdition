package dangomusicce.shader.impls.ui

import dangomusicce.shader.GlShader

class GlassBlurShader : GlShader(
    "quad.vert",
    "ui/glass_blur.frag",
) {
    override fun uniformsFields(): Array<String> = arrayOf(
        "u_Texture",
        "u_Direction",
    )

    fun setup(dx: Float, dy: Float) {
        setUniform1i("u_Texture", 0)
        setUniform2f("u_Direction", dx, dy)
    }
}
