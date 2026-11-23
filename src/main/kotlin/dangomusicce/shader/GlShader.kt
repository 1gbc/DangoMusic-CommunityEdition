package dangomusicce.shader

import dangomusicce.platform.Imm
import dangomusicce.utils.Color
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.nio.FloatBuffer

/**
 * 自己的着色器（OpenGL 3.2 core 版本，GLSL 150）。顶点属性固定：0 = a_Pos，1 = a_UV（见 [Imm]）。
 * [bind] 时记下原来的着色器，[unbind] 时换回去（Minecraft 记着它自己绑的是哪个，不能直接改成 0）。
 */
abstract class GlShader(
    val vertPath: String,
    val fragPath: String,
) {
    private var programId: Int = 0
    private val uniforms = mutableMapOf<String, Int>()
    private var previous = 0

    fun linkProgram() {
        if (programId != 0) return

        val vsh = compileShader(vertPath, GL20.GL_VERTEX_SHADER)
        try {
            val fsh = compileShader(fragPath, GL20.GL_FRAGMENT_SHADER)
            try {
                val program = GL20.glCreateProgram()
                GL20.glAttachShader(program, vsh)
                GL20.glAttachShader(program, fsh)
                GL20.glBindAttribLocation(program, Imm.ATTR_POS, "a_Pos")
                GL20.glBindAttribLocation(program, Imm.ATTR_UV, "a_UV")
                GL20.glLinkProgram(program)
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    val log = GL20.glGetProgramInfoLog(program, 1024)
                    GL20.glDeleteProgram(program)
                    error("Shader program linking failed ($vertPath, $fragPath): $log")
                }
                programId = program
            } finally {
                GL20.glDeleteShader(fsh)
            }
        } finally {
            GL20.glDeleteShader(vsh)
        }

        uniformsFields().forEach {
            getUniformId(it)
        }
    }

    open fun uniformsFields(): Array<String> = emptyArray()

    fun getProgramID(): Int = programId

    fun bind() {
        Imm.flush()
        previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        GL20.glUseProgram(programId)
    }

    fun unbind() {
        Imm.flush()
        GL20.glUseProgram(previous)
    }

    private fun getUniformId(name: String): Int =
        uniforms.getOrPut(name) { GL20.glGetUniformLocation(programId, name) }

    private fun compileShader(srcPath: String, type: Int): Int {
        val source = this.javaClass
            .getResourceAsStream("/assets/dangomusicce/shaders/$srcPath")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("Shader resource is missing: $srcPath")

        val shaderId = GL20.glCreateShader(type)
        GL20.glShaderSource(shaderId, source)
        GL20.glCompileShader(shaderId)
        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(shaderId, 1024)
            GL20.glDeleteShader(shaderId)
            error("Shader compilation failed ($srcPath): $log")
        }
        return shaderId
    }

    protected fun setUniform2f(field: String, a: Float, b: Float) {
        GL20.glUniform2f(getUniformId(field), a, b)
    }

    protected fun setUniform4f(field: String, a: Float, b: Float, c: Float, d: Float) {
        GL20.glUniform4f(getUniformId(field), a, b, c, d)
    }

    protected fun setUniformColor(field: String, color: Color) {
        setUniform4f(field, color.r, color.g, color.b, color.a)
    }

    protected fun setUniform1f(field: String, a: Float) {
        GL20.glUniform1f(getUniformId(field), a)
    }

    protected fun setUniform1i(field: String, a: Int) {
        GL20.glUniform1i(getUniformId(field), a)
    }

    protected fun setUniform4fv(field: String, values: FloatBuffer) {
        val id = getUniformId(field)
        if (id >= 0) GL20.glUniform4fv(id, values)
    }

    protected fun setUniformMat4(field: String, mat4Buffer: FloatBuffer) {
        GL20.glUniformMatrix4fv(getUniformId(field), false, mat4Buffer)
    }
}
