package dangomusicce.shader.impls

import dangomusicce.shader.GlShader

class QuadShader : GlShader(
    "quad.vert",
    "quad.frag",
) {
    override fun uniformsFields(): Array<String> = arrayOf("u_Texture")

    fun setup() {
        setUniform1i("u_Texture", 0)
    }
}
