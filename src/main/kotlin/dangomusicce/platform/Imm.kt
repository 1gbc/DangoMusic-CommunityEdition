package dangomusicce.platform

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.FloatBuffer

/**
 * 高版本 OpenGL（3.2 core）立即模式模拟与固定管线替代合批层。
 *
 * [end] 用当前绑定的着色器画（顶点属性：0 = a_Pos，1 = a_UV）；
 * [endFixed] 用 [Fixed] 的矩阵与颜色合批绘制。
 */
object Imm {
    const val ATTR_POS = 0
    const val ATTR_UV = 1

    private var vao = 0
    private var vbo = 0
    private var data = FloatArray(4 * 6 * 256)
    private var count = 0
    private val quad = FloatArray(16)
    private var corner = 0
    private var u = 0f
    private var v = 0f
    private var upload: FloatBuffer = BufferUtils.createFloatBuffer(data.size)

    private var textured = true

    fun begin(textured: Boolean = true) {
        if (!textured && VanillaTextBatch.hasPending()) VanillaTextBatch.flush()
        count = 0
        corner = 0
        this.textured = textured
    }

    fun tex(u: Float, v: Float) {
        this.u = u
        this.v = v
    }

    fun vertex(x: Float, y: Float) {
        val o = corner * 4
        quad[o] = x; quad[o + 1] = y; quad[o + 2] = u; quad[o + 3] = v
        corner++
        if (corner == 4) {
            corner = 0
            ensure(count + 6)
            putCorner(0); putCorner(1); putCorner(2)
            putCorner(0); putCorner(2); putCorner(3)
        }
    }

    private fun putCorner(i: Int) {
        val src = i * 4
        val dst = count * 4
        val d = data
        d[dst] = quad[src]
        d[dst + 1] = quad[src + 1]
        d[dst + 2] = quad[src + 2]
        d[dst + 3] = quad[src + 3]
        count++
    }

    private fun ensure(vertices: Int) {
        if (vertices * 4 <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, vertices * 4))
        upload = BufferUtils.createFloatBuffer(data.size)
    }

    fun end() {
        flush()
        draw(false)
    }

    var draws = 0
        private set

    fun countDraw() {
        draws++
    }

    var glDraws = 0
        private set

    private const val BATCH_STRIDE = 10
    private var batch = FloatArray(BATCH_STRIDE * 6 * 256)
    private var batchCount = 0
    private var batchTextured = true
    private var batchVao = 0
    private var batchVbo = 0
    private var batchUpload: FloatBuffer = BufferUtils.createFloatBuffer(batch.size)
    private val mvp = Matrix4f()

    fun endFixed() {
        if (count == 0) return
        draws++
        if (batchCount > 0 && batchTextured != textured) flush()
        batchTextured = textured
        Fixed.matrix(mvp)
        val c = Fixed.color
        val r = c[0]
        val g = c[1]
        val b = c[2]
        val a = c[3]
        if ((batchCount + count) * BATCH_STRIDE > batch.size) {
            batch = batch.copyOf(maxOf(batch.size * 2, (batchCount + count) * BATCH_STRIDE))
            batchUpload = BufferUtils.createFloatBuffer(batch.size)
        }
        val m = mvp
        val out = batch
        var o = batchCount * BATCH_STRIDE
        for (i in 0 until count) {
            val x = data[i * 4]
            val y = data[i * 4 + 1]
            out[o] = m.m00() * x + m.m10() * y + m.m30()
            out[o + 1] = m.m01() * x + m.m11() * y + m.m31()
            out[o + 2] = m.m02() * x + m.m12() * y + m.m32()
            out[o + 3] = m.m03() * x + m.m13() * y + m.m33()
            out[o + 4] = data[i * 4 + 2]
            out[o + 5] = data[i * 4 + 3]
            out[o + 6] = r
            out[o + 7] = g
            out[o + 8] = b
            out[o + 9] = a
            o += BATCH_STRIDE
        }
        batchCount += count
        count = 0
    }

    fun hasBatch(): Boolean = batchCount > 0

    fun flush() {
        flushBatch()
        VanillaTextBatch.flush()
    }

    fun flushBatch() {
        if (batchCount == 0) return
        val n = batchCount
        batchCount = 0
        glDraws++
        if (batchVao == 0) {
            batchVao = GL30.glGenVertexArrays()
            batchVbo = GL15.glGenBuffers()
        }
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)

        Fixed.useBatch(batchTextured)
        GL30.glBindVertexArray(batchVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVbo)
        batchUpload.clear()
        batchUpload.put(batch, 0, n * BATCH_STRIDE)
        batchUpload.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, batchUpload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 0L)
        GL20.glEnableVertexAttribArray(1)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 16L)
        GL20.glEnableVertexAttribArray(2)
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 24L)
        McCompat.beforeDraw()
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, n)

        GL30.glBindVertexArray(prevVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer)
        GL20.glUseProgram(prevProgram)
    }

    private fun draw(fixed: Boolean) {
        if (count == 0) return
        VanillaTextBatch.flush()
        draws++
        glDraws++
        if (vao == 0) {
            vao = GL30.glGenVertexArrays()
            vbo = GL15.glGenBuffers()
        }
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)

        if (fixed) Fixed.use(textured)

        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        upload.clear()
        upload.put(data, 0, count * 4)
        upload.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(ATTR_POS)
        GL20.glVertexAttribPointer(ATTR_POS, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(ATTR_UV)
        GL20.glVertexAttribPointer(ATTR_UV, 2, GL11.GL_FLOAT, false, 16, 8L)
        McCompat.beforeDraw()
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count)

        GL30.glBindVertexArray(prevVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer)
        if (fixed) GL20.glUseProgram(prevProgram)
        count = 0
    }
}

/** 固定管线的替代品：当前颜色与模型矩阵。 */
object Fixed {
    val pose = Matrix4f()
    private val stack = ArrayDeque<Matrix4f>()
    val color = floatArrayOf(1f, 1f, 1f, 1f)

    fun push() {
        stack.addLast(Matrix4f(pose))
    }

    fun pop() {
        stack.removeLastOrNull()?.let { pose.set(it) }
    }

    fun reset(base: Matrix4f? = null) {
        stack.clear()
        if (base != null) pose.set(base) else pose.identity()
    }

    fun color(r: Float, g: Float, b: Float, a: Float) {
        color[0] = r; color[1] = g; color[2] = b; color[3] = a
    }

    fun matrix(dest: Matrix4f): Matrix4f = dest.set(McCompat.projection()).mul(McCompat.modelView()).mul(pose)

    private const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
uniform mat4 u_MVP;
out vec2 uv;
void main() {
    gl_Position = u_MVP * vec4(a_Pos, 0.0, 1.0);
    uv = a_UV;
}
"""

    private const val FRAG = """
#version 150
uniform sampler2D u_Texture;
uniform vec4 u_Color;
uniform int u_Textured;
in vec2 uv;
out vec4 fragColor;
void main() {
    vec4 c = u_Color;
    if (u_Textured != 0) c *= texture(u_Texture, uv);
    fragColor = c;
}
"""

    private var program = 0
    private var locMvp = 0
    private var locColor = 0
    private var locTextured = 0
    private var locTexture = 0
    private val matBuf = BufferUtils.createFloatBuffer(16)
    private val mvp = Matrix4f()

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader compile failed: " + GL20.glGetShaderInfoLog(id, 4096))
        }
        return id
    }

    private fun ensure() {
        if (program != 0) return
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG)
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, vs)
        GL20.glAttachShader(p, fs)
        GL20.glBindAttribLocation(p, Imm.ATTR_POS, "a_Pos")
        GL20.glBindAttribLocation(p, Imm.ATTR_UV, "a_UV")
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        program = p
        locMvp = GL20.glGetUniformLocation(p, "u_MVP")
        locColor = GL20.glGetUniformLocation(p, "u_Color")
        locTextured = GL20.glGetUniformLocation(p, "u_Textured")
        locTexture = GL20.glGetUniformLocation(p, "u_Texture")
    }

    private const val BATCH_VERT = """
#version 150
in vec4 a_Clip;
in vec2 a_UV;
in vec4 a_Color;
out vec2 uv;
out vec4 color;
void main() {
    gl_Position = a_Clip;
    uv = a_UV;
    color = a_Color;
}
"""

    private const val BATCH_FRAG = """
#version 150
uniform sampler2D u_Texture;
uniform int u_Textured;
in vec2 uv;
in vec4 color;
out vec4 fragColor;
void main() {
    vec4 c = color;
    if (u_Textured != 0) c *= texture(u_Texture, uv);
    fragColor = c;
}
"""

    private var batchProgram = 0
    private var batchLocTextured = 0
    private var batchLocTexture = 0

    fun useBatch(textured: Boolean) {
        if (batchProgram == 0) {
            val vs = compile(GL20.GL_VERTEX_SHADER, BATCH_VERT)
            val fs = compile(GL20.GL_FRAGMENT_SHADER, BATCH_FRAG)
            val p = GL20.glCreateProgram()
            GL20.glAttachShader(p, vs)
            GL20.glAttachShader(p, fs)
            GL20.glBindAttribLocation(p, 0, "a_Clip")
            GL20.glBindAttribLocation(p, 1, "a_UV")
            GL20.glBindAttribLocation(p, 2, "a_Color")
            GL20.glLinkProgram(p)
            GL20.glDeleteShader(vs)
            GL20.glDeleteShader(fs)
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                error("Fixed batch shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
            }
            batchProgram = p
            batchLocTextured = GL20.glGetUniformLocation(p, "u_Textured")
            batchLocTexture = GL20.glGetUniformLocation(p, "u_Texture")
        }
        GL20.glUseProgram(batchProgram)
        GL20.glUniform1i(batchLocTextured, if (textured) 1 else 0)
        GL20.glUniform1i(batchLocTexture, 0)
    }

    fun use(textured: Boolean) {
        ensure()
        GL20.glUseProgram(program)
        mvp.set(McCompat.projection()).mul(McCompat.modelView()).mul(pose)
        matBuf.clear()
        mvp.get(matBuf)
        GL20.glUniformMatrix4fv(locMvp, false, matBuf)
        GL20.glUniform4f(locColor, color[0], color[1], color[2], color[3])
        GL20.glUniform1i(locTextured, if (textured) 1 else 0)
        GL20.glUniform1i(locTexture, 0)
    }
}
